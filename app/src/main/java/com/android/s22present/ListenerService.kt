package com.android.s22present

import android.animation.ObjectAnimator
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Display
import android.view.View
import androidx.core.view.isInvisible
import com.topjohnwu.superuser.ipc.RootService


// This service listens for various things and tells the ScreenService what it should do.
// These tasks have to be separated into two services since RootServices cannot do things that require context like register Broadcast receivers, but normal services can't do root things like turn the screen off.
class ListenerService : Service()
{
    companion object {
        var isRunning = false
        private const val DISPLAY_WAKE_LATENCY_MS = 1000L
    }
    var rootservice: Messenger? = null
    private var wakeSessionActive = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lidClosedRunnable: Runnable? = null
    private var lidOpenRunnable: Runnable? = null
    private var mainDisplay: Display? = null
    private var presentation: PresentationHandler? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var proximityListener: SensorEventListener? = null
    private var rootBound = false
    private val lidHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                ScreenService.MSG_LID_CLOSED -> {
                    Log.v("S22PresListServ", "Hall sensor: lid closed")
                    sendBroadcast(Intent("com.android.s22present.LIDCLOSED"))
                }
                ScreenService.MSG_LID_OPEN -> {
                    Log.v("S22PresListServ", "Hall sensor: lid open")
                    sendBroadcast(Intent("com.android.s22present.LIDOPEN"))
                }
                ScreenService.MSG_HALL_NOT_FOUND -> {
                    Log.w("S22PresListServ", "No hall sensor, using proximity fallback")
                    mainDisplay?.let { registerProximitySensor(it) }
                }
            }
        }
    }
    private val lidMessenger = Messenger(lidHandler)
    private val wakeTimeoutHandler = Handler(Looper.getMainLooper())
    private val wakeTimeoutRunnable = Runnable {
        wakeSessionActive = false
        Log.v("S22PresListServ", "Wake timeout, putting device to sleep")
        Globals.presentationRoot?.visibility = View.INVISIBLE
        sendToRoot(Message.obtain(null, ScreenService.CMD_SECONDARY_OFF, 0, 0))
    }
    val RootConnect = object : ServiceConnection
    {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?)
        {
            Log.i("S22PresScreenServInit", "Connecting...")
            rootservice = Messenger(service)
            if(rootservice!=null)
            {
                        Globals.rootAvailable = true
                        Globals.statusText?.text = "Running \u2713"
                        Globals.onRootStatusChanged?.invoke()
                        Log.i("S22PresScreenServInit", "Done!")
                        val hallMsg = Message.obtain(null, ScreenService.CMD_START_HALL_MONITOR)
                        hallMsg.replyTo = lidMessenger
                        rootservice?.send(hallMsg)
            } else {
                Globals.rootAvailable = false
                Globals.statusText?.text = "Not running \u2717"
                Globals.onRootStatusChanged?.invoke()
                Log.d("S22PresScreenServInit", "Failed!")
            }
        }
        override fun onServiceDisconnected(name: ComponentName?)
        {
            Globals.rootAvailable = false
            rootservice = null
            Globals.onRootStatusChanged?.invoke()
            Log.i("S22PresScreenServInit", "Disconnected.")
        }
    }
    // This function starts the ScreenService before it is actually required and binds to it.
    fun startscreenservice()
    {
        Log.i("S22PresListServInit", "Let's get the service for the screen running...")
        val intent = Intent(this, ScreenService::class.java)
        RootService.bind(intent, RootConnect)
        rootBound = true
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
    {
        if (intent?.action == WakeReceiver.ACTION_WAKE_DISPLAY) {
            Log.v("S22PresListServ", "Wake display requested")
            wakeSessionActive = true
            wakeTimeoutHandler.removeCallbacks(wakeTimeoutRunnable)
            Globals.presentationRoot?.visibility = View.VISIBLE
            sendToRoot(Message.obtain(null, ScreenService.CMD_SECONDARY_WAKE, 0, 0))
            wakeTimeoutHandler.postDelayed(
                wakeTimeoutRunnable, DISPLAY_WAKE_LATENCY_MS + Globals.wakeTimeoutMs
            )
            return START_NOT_STICKY
        }

        if (presentation != null) {
            Log.i("S22PresListServInit", "Already initialized, ignoring start")
            return START_NOT_STICKY
        }
        isRunning = true
        Log.i("S22PresListServInit", "Hello!")
        val displaymanager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display0 = displaymanager.displays[0]
        mainDisplay = display0
        val presentationDisplays = displaymanager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        val display1 = presentationDisplays.firstOrNull() ?: displaymanager.displays.getOrNull(1)
        if (display1 == null) {
            Log.e("S22PresListServInit", "No secondary display found")
            stopSelf()
            return START_NOT_STICKY
        }
        Globals.migrateSettingsIfNeeded(this)
        try {
            val prefs = getSharedPreferences(Globals.PREFS_APP, Context.MODE_PRIVATE)
            Globals.style = prefs.getInt(Globals.KEY_STYLE, Globals.DEFAULT_STYLE)
            Globals.font = prefs.getInt(Globals.KEY_FONT, Globals.DEFAULT_FONT)
            Globals.wakeTimeoutMs = Globals.wakeTimeoutMsForIndex(
                prefs.getInt(Globals.KEY_WAKE_TIMEOUT_INDEX, Globals.DEFAULT_WAKE_TIMEOUT_INDEX))
        }
        catch (e: Exception)
        {
            Log.w("S22PresListServInit", "Failed to load settings. Continuing with defaults.")
        }
        // Identify and show presentation.
        val present = PresentationHandler(this, display1)
        present.show()
        presentation = present
        Globals.statusText?.text = "Running \u2713"
        // Bind to ScreenService.
        startscreenservice()
        // Create a BroadcastReceiver.
        val screenStateReciever = object : BroadcastReceiver()
        {
            var lid = "open"
            override fun onReceive(context: Context, intent: Intent)
            {
                // When a Broadcast is Received.
                if (intent.action == "com.android.s22present.LIDOPEN")
                {
                    lid = "open"
                    wakeSessionActive = false
                    wakeTimeoutHandler.removeCallbacks(wakeTimeoutRunnable)
                    sendToRoot(Message.obtain(null, ScreenService.CMD_MAIN_ON, 0, 0))
                    turnoff()
                }
                if (intent.action == "com.android.s22present.LIDCLOSED")
                {
                    lid = "closed"
                    Log.v("S22PresListServ", "Lid closed, waking secondary briefly")
                    wakeSessionActive = true
                    wakeTimeoutHandler.removeCallbacks(wakeTimeoutRunnable)
                    Globals.presentationRoot?.visibility = View.VISIBLE
                    sendToRoot(Message.obtain(null, ScreenService.CMD_SECONDARY_WAKE, 0, 0))
                    wakeTimeoutHandler.postDelayed(
                        wakeTimeoutRunnable, DISPLAY_WAKE_LATENCY_MS + Globals.wakeTimeoutMs
                    )
                }
                if (intent.action == "com.android.s22present.NOTIFICATION_RECEIVED" && lid == "closed")
                {
                    Log.v("S22PresListServ", "Notification received with lid closed, waking secondary")
                    wakeSessionActive = true
                    wakeTimeoutHandler.removeCallbacks(wakeTimeoutRunnable)
                    Globals.presentationRoot?.visibility = View.VISIBLE
                    sendToRoot(Message.obtain(null, ScreenService.CMD_SECONDARY_WAKE, 0, 0))
                    wakeTimeoutHandler.postDelayed(
                        wakeTimeoutRunnable, DISPLAY_WAKE_LATENCY_MS + Globals.wakeTimeoutMs
                    )
                }
                if(intent.action == Intent.ACTION_SCREEN_ON && !wakeSessionActive)
                {
                      turnoff()
                }
            }
            fun turnoff()
            {
                if(lid=="open")
                {
                    val request = Message.obtain(null, ScreenService.CMD_SECONDARY_OFF, 0, 0)
                    mainHandler.postDelayed(
                        {
                            Log.v("S22PresListServ", "Action requiring screen off detected")
                            Globals.presentationRoot?.visibility = View.INVISIBLE
                            sendToRoot(request)
                        }, 500)
                }
            }
        }
        // Create a filter for the Broadcast Receiver
        val screenStateFilter = IntentFilter()
        // Add various actions to it.
        screenStateFilter.addAction(Intent.ACTION_SCREEN_ON)
        screenStateFilter.addAction(Intent.ACTION_POWER_CONNECTED)
        screenStateFilter.addAction(Intent.ACTION_POWER_DISCONNECTED)
        screenStateFilter.addAction("com.android.s22present.NOTIFICATION_RECEIVED")
        screenStateFilter.addAction("com.android.s22present.NOTIFICATION_REMOVED")
        screenStateFilter.addAction("com.android.s22present.LIDCLOSED")
        screenStateFilter.addAction("com.android.s22present.LIDOPEN")
        screenStateFilter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        // Start the Receiver with the filter we just created.
        registerReceiver(screenStateReciever, screenStateFilter, Context.RECEIVER_NOT_EXPORTED)
        screenReceiver = screenStateReciever
        Log.i("S22PresListServInit", "Listening...")
        return START_NOT_STICKY
    }
    // Unused.
    override fun onBind(intent: Intent?): IBinder? {
      return null
    }
    private fun registerProximitySensor(display0: Display) {
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val proximitySensorEventListener: SensorEventListener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_PROXIMITY) {
                    lidClosedRunnable?.let { mainHandler.removeCallbacks(it) }
                    lidOpenRunnable?.let { mainHandler.removeCallbacks(it) }
                    if (event.values[0] == 0f) {
                        val runnable = Runnable {
                            if (display0.state == Display.STATE_OFF) {
                                Log.v("S22PresSensList", "Shut")
                                Intent().also { broadcast ->
                                    broadcast.setAction("com.android.s22present.LIDCLOSED")
                                    sendBroadcast(broadcast)
                                }
                            }
                        }
                        lidClosedRunnable = runnable
                        mainHandler.postDelayed(runnable, 1500)
                    } else {
                        val runnable = Runnable {
                            if (display0.state == Display.STATE_ON) {
                                Log.v("S22PresSensList", "Open")
                                Intent().also { broadcast ->
                                    broadcast.setAction("com.android.s22present.LIDOPEN")
                                    sendBroadcast(broadcast)
                                }
                            }
                        }
                        lidOpenRunnable = runnable
                        mainHandler.postDelayed(runnable, 400)
                    }
                }
            }
        }
        proximityListener?.let { sensorManager.unregisterListener(it) }
        sensorManager.registerListener(proximitySensorEventListener, proximitySensor, SensorManager.SENSOR_DELAY_FASTEST)
        proximityListener = proximitySensorEventListener
        Log.i("S22PresListServInit", "Proximity sensor registered as lid fallback")
    }

    private fun sendToRoot(msg: Message) {
        val service = rootservice
        if (service == null) {
            Log.w("S22PresListServ", "Root service unavailable, skipping command ${msg.what}")
            return
        }
        try {
            service.send(msg)
        } catch (e: android.os.RemoteException) {
            Log.e("S22PresListServ", "Failed to send command ${msg.what} to root service", e)
            Globals.rootAvailable = false
            rootservice = null
        }
    }
    override fun onDestroy() {
        isRunning = false
        wakeTimeoutHandler.removeCallbacksAndMessages(null)
        mainHandler.removeCallbacksAndMessages(null)
        lidHandler.removeCallbacksAndMessages(null)
        screenReceiver?.let { unregisterReceiver(it) }
        screenReceiver = null
        proximityListener?.let {
            (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(it)
        }
        proximityListener = null
        presentation?.dismiss()
        presentation = null
        if (rootBound) {
            RootService.unbind(RootConnect)
            rootBound = false
        }
        rootservice = null
        super.onDestroy()
    }
}
// Notification listener. Whilst technically a service is it ran so long as it's declared in the manifest.
class NotificationService : NotificationListenerService() {
    companion object {
        private val MUSIC_APP_PACKAGES = setOf(
            "it.vfsfitvnm.vimusic",
            "com.google.android.apps.youtube.music",
            "com.spotify.music",
            "org.fossify.musicplayer"
        )
    }
    var musicactive=false
    var musicnotiftitle:String = ""
    var musicnotiftext:String = ""
    var musicnotifpkg:String = ""
    private var activeController: MediaController? = null
    private var mediaCallback: MediaController.Callback? = null
    // When listener created.
    override fun onCreate() {
        // Log.
        Log.i("S22PresNotifServInit", "Listening...")
        super.onCreate()
    }

    private fun setAlbumArtFromMetadata(metadata: MediaMetadata?) {
        val art: Bitmap? = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.description?.iconBitmap
        if (art != null) {
            Globals.albumArtView?.setImageBitmap(art)
            Globals.albumArtView?.visibility = View.VISIBLE
        } else {
            Globals.albumArtView?.visibility = View.GONE
        }
    }

    private fun updateAlbumArt(packageName: String) {
        mediaCallback?.let { activeController?.unregisterCallback(it) }
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, NotificationService::class.java)
            val controllers = msm.getActiveSessions(component)
            val controller = controllers.firstOrNull { it.packageName == packageName }
                ?: return
            activeController = controller
            setAlbumArtFromMetadata(controller.metadata)
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    setAlbumArtFromMetadata(metadata)
                }
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    when (state?.state) {
                        PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE -> {
                            clearMusicState()
                        }
                    }
                }
                override fun onSessionDestroyed() {
                    clearMusicState()
                }
            }
            mediaCallback = callback
            controller.registerCallback(callback)
        } catch (e: Exception) {
            Log.w("S22PresNotifServ", "Failed to get album art", e)
        }
    }

    private fun clearAlbumArt() {
        mediaCallback?.let { activeController?.unregisterCallback(it) }
        mediaCallback = null
        activeController = null
        Globals.albumArtView?.setImageBitmap(null)
        Globals.albumArtView?.visibility = View.GONE
    }

    private fun clearMusicState() {
        Log.v("S22PresNotifServ", "Clearing music")
        when(Globals.visual) {
            1 -> { Globals.visualbar.isInvisible = true }
            2 -> { Globals.visualsquare.isInvisible = true }
        }
        clearAlbumArt()
        musicactive = false
        musicnotiftitle = ""
        musicnotiftext = ""
        musicnotifpkg = ""
    }

    // When Notification created.
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Globals.presentationReady) return
        // Store information in variables
        val packageName = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString()
        val text = extras.getCharSequence("android.text")?.toString()
        if(title != null || text != null) {
            if (Globals.titlefield.text == "") {
                ObjectAnimator.ofFloat(Globals.datefield, "translationY", -20f)
                    .apply { duration = 500; start() }
                ObjectAnimator.ofFloat(Globals.timefield, "translationY", -20f)
                    .apply { duration = 500; start() }
                ObjectAnimator.ofFloat(Globals.titlefield, "translationY", 0f)
                    .apply { duration = 500; start() }
                ObjectAnimator.ofFloat(Globals.contentfield, "translationY", 0f)
                    .apply { duration = 500; start() }
            }
            if (title != Globals.titlefield.text) {
                Log.v("S22PresNotifServ", "Ping!")
                if (packageName in MUSIC_APP_PACKAGES) {
                    Log.v("S22PresNotifServ", "Music")
                    musicactive = true
                    musicnotiftitle = title ?: ""
                    musicnotiftext = text ?: ""
                    musicnotifpkg = packageName
                    when(Globals.visual)
                    {
                        1->{Globals.visualbar.isInvisible = false}
                        2->{Globals.visualsquare.isInvisible = false}
                    }
                    updateAlbumArt(packageName)
                }
                Globals.titlefield.text = title
                Intent().also { broadcast ->
                    broadcast.setAction("com.android.s22present.NOTIFICATION_RECEIVED")
                    sendBroadcast(broadcast)
                }

            }
            if (text != Globals.contentfield.text) {
                Globals.contentfield.text = text
            }
        }
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (!Globals.presentationReady) return
        Log.v("S22PresNotifServ", "Something got removed. pkg=${sbn?.packageName}")
            val title = sbn?.notification?.extras?.getCharSequence("android.title")?.toString()
            val text = sbn?.notification?.extras?.getCharSequence("android.text")?.toString()
            Log.v("S22PresNotifServ", "removed title='$title' musicnotiftitle='$musicnotiftitle' musicactive=$musicactive")
            if(Globals.titlefield.text == title && Globals.contentfield.text == text)
            {
                Log.v("S22PresNotifServ", "Clearing display.")
                if(musicactive && title != musicnotiftitle)
                {
                    Log.v("S22PresNotifServ", "Switching to music")
                    Globals.titlefield.text = musicnotiftitle
                    Globals.contentfield.text = musicnotiftext
                }
                else
                {
                    Globals.titlefield.text = ""
                    Globals.contentfield.text = ""
                    ObjectAnimator.ofFloat(Globals.datefield, "translationY", 0f).apply { duration = 500; start() }
                    ObjectAnimator.ofFloat(Globals.timefield, "translationY", 0f).apply { duration = 500; start() }
                    ObjectAnimator.ofFloat(Globals.titlefield, "translationY", 20f).apply { duration = 500; start() }
                    ObjectAnimator.ofFloat(Globals.contentfield, "translationY", 20f).apply { duration = 500; start() }
                }
            }
        if(musicactive && (title == musicnotiftitle || sbn?.packageName == musicnotifpkg))
        {
            clearMusicState()
        }
        super.onNotificationRemoved(sbn)
        }
    }
