package com.android.s22present

import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceControl
import android.system.Os
import android.system.OsConstants
import com.topjohnwu.superuser.ipc.RootService
import java.io.File
import java.net.SocketAddress

class ScreenService : RootService()
{
    companion object {
        // App -> root
        const val CMD_REGISTER = 1          // replyTo = client, arg1 = wake timeout ms
        const val CMD_WAKE = 2              // wake secondary for the configured timeout if lid is closed
        const val CMD_SECONDARY_OBSERVED = 3 // arg1 = 1 if Android reports the secondary ON
        const val CMD_LID = 4               // arg1 = 1 if closed (proximity fallback only)

        // Root -> app
        const val MSG_LID_OPEN = 100
        const val MSG_LID_CLOSED = 101
        const val MSG_HALL_NOT_FOUND = 102
        const val MSG_SECONDARY_STATE = 103 // arg1 = 1 if the presentation should be visible

        private val controller: DisplayController by lazy {
            val thread = HandlerThread("display-controller").apply { start() }
            DisplayController(thread).also { LidMonitor(it).start() }
        }
    }

    override fun onBind(intent: Intent): IBinder
    {
        Log.i("S22PresScreenServInit", "Hello! I've been bound :3")
        return Messenger(controller).binder
    }
}

// Single owner of secondary display power. All state is confined to its HandlerThread.
internal class DisplayController(thread: HandlerThread) : Handler(thread.looper) {

    companion object {
        private const val TAG = "S22PresDisplayCtl"
        // Rapid on/off cycling wedges the secondary panel's SPI bus (~41s power-on hang).
        private const val MIN_GAP_MS = 1500L
        // Lets the app hide the presentation before the panel stops consuming frames.
        private const val HIDE_LEAD_MS = 50L
        // Panel power-on time, added so the wake timeout counts visible time.
        private const val WAKE_LATENCY_MS = 1000L
        private const val UNKNOWN = -1
        private const val POWER_OFF = 0
        private const val POWER_ON = 2
    }

    private var client: Messenger? = null
    private var wakeTimeoutMs = 3000L
    private var lidClosed: Boolean? = null
    private var wakeUntil = 0L
    private var applied = UNKNOWN
    private var lastChangeAt = Long.MIN_VALUE / 2
    private var notifiedVisible: Boolean? = null
    private var hallMissing = false

    private val reconcileRunnable = Runnable { reconcile() }

    private val setDisplayPowerMode by lazy {
        SurfaceControl::class.java.getMethod(
            "setDisplayPowerMode", IBinder::class.java, Int::class.java
        )
    }

    override fun handleMessage(msg: Message) {
        when (msg.what) {
            ScreenService.CMD_REGISTER -> {
                client = msg.replyTo
                if (msg.arg1 > 0) wakeTimeoutMs = msg.arg1.toLong()
                notifiedVisible = null
                Log.i(TAG, "Client registered, wakeTimeout=${wakeTimeoutMs}ms lidClosed=$lidClosed")
                if (hallMissing) send(ScreenService.MSG_HALL_NOT_FOUND)
                notifyVisible(desiredOn())
            }
            ScreenService.CMD_WAKE -> {
                if (lidClosed == true) {
                    wakeUntil = SystemClock.uptimeMillis() + WAKE_LATENCY_MS + wakeTimeoutMs
                    reconcile()
                }
            }
            ScreenService.CMD_SECONDARY_OBSERVED -> onAndroidSecondaryState(msg.arg1 == 1)
            ScreenService.CMD_LID -> onLid(msg.arg1 == 1)
        }
    }

    fun setInitialLid(closed: Boolean) = post {
        if (lidClosed == null) {
            lidClosed = closed
            Log.i(TAG, "Initial lid state: closed=$closed")
        }
    }

    fun onLid(closed: Boolean) {
        if (Looper.myLooper() != looper) { post { onLid(closed) }; return }
        if (lidClosed == closed) return
        lidClosed = closed
        Log.v(TAG, "Lid ${if (closed) "closed" else "open"}")
        send(if (closed) ScreenService.MSG_LID_CLOSED else ScreenService.MSG_LID_OPEN)
        val now = SystemClock.uptimeMillis()
        if (closed) {
            wakeUntil = now + WAKE_LATENCY_MS + wakeTimeoutMs
            // Android puts the display group to sleep ~100ms after the lid closes; waking
            // immediately would be cut off by that, which is the pattern that wedges the panel.
            lastChangeAt = maxOf(lastChangeAt, now)
        } else {
            wakeUntil = 0L
        }
        reconcile()
    }

    fun onHallMissing() = post {
        hallMissing = true
        send(ScreenService.MSG_HALL_NOT_FOUND)
    }

    // Android changed the secondary's power itself (wake/sleep of the shared display group).
    private fun onAndroidSecondaryState(on: Boolean) {
        Log.v(TAG, "Android set secondary ${if (on) "ON" else "OFF"}")
        // Android sleeping while we're showing the secondary (power button, screen timeout)
        // ends the wake; its sleep on lid close happens before our wake is applied, so keep that.
        if (!on && applied == POWER_ON) wakeUntil = 0L
        applied = if (on) POWER_ON else POWER_OFF
        lastChangeAt = SystemClock.uptimeMillis()
        reconcile()
    }

    private fun desiredOn(): Boolean =
        lidClosed == true && SystemClock.uptimeMillis() < wakeUntil

    private fun reconcile() {
        removeCallbacks(reconcileRunnable)
        val now = SystemClock.uptimeMillis()
        val wantOn = desiredOn()
        val want = if (wantOn) POWER_ON else POWER_OFF

        if (applied == want) {
            notifyVisible(wantOn)
            scheduleWakeExpiry(now)
            return
        }
        val wait = lastChangeAt + MIN_GAP_MS - now
        if (wait > 0) {
            postDelayed(reconcileRunnable, wait)
            return
        }
        if (wantOn) {
            // Count the wake timeout from when the panel actually turns on, not from the request.
            wakeUntil = maxOf(wakeUntil, now + WAKE_LATENCY_MS + wakeTimeoutMs)
            notifyVisible(true)
            Runtime.getRuntime().exec(arrayOf("input", "keyevent", "KEYCODE_WAKEUP"))
            setPower(POWER_ON)
        } else {
            if (notifiedVisible != false) {
                notifyVisible(false)
                postDelayed(reconcileRunnable, HIDE_LEAD_MS)
                return
            }
            setPower(POWER_OFF)
        }
        applied = want
        lastChangeAt = now
        scheduleWakeExpiry(now)
    }

    private fun scheduleWakeExpiry(now: Long) {
        if (desiredOn()) postDelayed(reconcileRunnable, wakeUntil - now)
    }

    private fun setPower(mode: Int) {
        try {
            setDisplayPowerMode.invoke(null, Globals.token as IBinder?, mode)
            Log.v(TAG, "Secondary power ${if (mode == POWER_ON) "ON" else "OFF"}")
        } catch (e: ReflectiveOperationException) {
            Log.e(TAG, "SurfaceControl operation failed", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied", e)
        }
    }

    private fun notifyVisible(visible: Boolean) {
        if (notifiedVisible == visible) return
        notifiedVisible = visible
        send(ScreenService.MSG_SECONDARY_STATE, if (visible) 1 else 0)
    }

    private fun send(what: Int, arg1: Int = 0) {
        val c = client ?: return
        try {
            c.send(Message.obtain(null, what, arg1, 0))
        } catch (e: RemoteException) {
            Log.w(TAG, "Client gone", e)
            client = null
        }
    }
}

// Receives lid changes as kernel uevents from the switch_hall device, falling back to sysfs polling.
// (The hall_sensor input device advertises SW_LID but never emits events on this phone.)
internal class LidMonitor(private val controller: DisplayController) {

    companion object {
        private const val TAG = "S22PresLidMon"
        private const val HALL_STATE_PATH = "/sys/class/switch_hall/hall_switch/state"
        private const val HALL_POLL_MS = 100L
        private const val NETLINK_KOBJECT_UEVENT = 15
        private const val UEVENT_KERNEL_GROUP = 1
    }

    fun start() {
        Thread(::run, "lid-monitor").apply { isDaemon = true; start() }
    }

    private fun run() {
        val sysfs = File(HALL_STATE_PATH)
        // sysfs: 1 = open, 0 = closed
        sysfs.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()?.let {
            controller.setInitialLid(it == 0)
        }

        try {
            readUevents()
            return
        } catch (e: Exception) {
            Log.e(TAG, "uevent socket failed, falling back to sysfs polling", e)
        }

        if (!sysfs.exists()) {
            Log.w(TAG, "Hall sensor not found, proximity fallback")
            controller.onHallMissing()
            return
        }
        pollSysfs(sysfs)
    }

    // Kernel uevent: NUL-separated "KEY=value" fields, e.g.
    // SUBSYSTEM=switch_hall, SWITCH_NAME=hall_switch, SWITCH_STATE=0 (0 = closed, 1 = open).
    private fun readUevents() {
        val fd = Os.socket(OsConstants.AF_NETLINK, OsConstants.SOCK_DGRAM, NETLINK_KOBJECT_UEVENT)
        try {
            val address = Class.forName("android.system.NetlinkSocketAddress")
                .getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .newInstance(0, UEVENT_KERNEL_GROUP) as SocketAddress
            Os.bind(fd, address)
            Log.i(TAG, "Listening for switch_hall uevents")
            val buf = ByteArray(8192)
            while (true) {
                val n = Os.read(fd, buf, 0, buf.size)
                if (n <= 0) continue
                val fields = String(buf, 0, n, Charsets.US_ASCII).split('\u0000')
                if ("SUBSYSTEM=switch_hall" !in fields) continue
                val state = fields.firstOrNull { it.startsWith("SWITCH_STATE=") }
                    ?.substringAfter('=')?.toIntOrNull() ?: continue
                controller.onLid(state == 0)
            }
        } finally {
            Os.close(fd)
        }
    }

    private fun pollSysfs(sysfs: File) {
        Log.i(TAG, "Polling $sysfs")
        var last = -1
        while (true) {
            val value = sysfs.readTextOrNull()?.trim()?.toIntOrNull()
            if (value != null && value != last) {
                if (last != -1) controller.onLid(value == 0)
                last = value
            }
            Thread.sleep(HALL_POLL_MS)
        }
    }

    private fun File.readTextOrNull(): String? = try { readText() } catch (e: Exception) { null }
}
