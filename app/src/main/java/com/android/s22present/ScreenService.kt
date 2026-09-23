package com.android.s22present

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import android.view.SurfaceControl
import com.topjohnwu.superuser.ipc.RootService
import java.io.File
import java.io.FileOutputStream

class ScreenService : RootService()
{
    companion object {
        const val CMD_SECONDARY_OFF = 1
        const val CMD_MAIN_OFF = 2
        const val CMD_SECONDARY_WAKE = 3
        const val CMD_START_HALL_MONITOR = 4
        const val CMD_MAIN_ON = 5

        const val MSG_LID_OPEN = 100
        const val MSG_LID_CLOSED = 101
        const val MSG_HALL_NOT_FOUND = 102

        private const val HALL_STATE_PATH = "/sys/class/switch_hall/hall_switch/state"
        private const val HALL_POLL_MS = 100L
    }

    private lateinit var mMessenger: Messenger

    internal class IncomingHandler(
        private val context: Context,
    ) : Handler(Looper.getMainLooper()) {

        companion object {
            private const val MAIN_BACKLIGHT = "/sys/class/leds/lcd-backlight/brightness"
            private const val SECONDARY_BACKLIGHT = "/sys/class/leds/lcd-backlight2/brightness"
        }

        private var hallMonitorThread: Thread? = null

        private fun startHallMonitor(replyTo: Messenger) {
            hallMonitorThread?.interrupt()
            hallMonitorThread = Thread {
                try {
                    val stateFile = File(HALL_STATE_PATH)
                    if (!stateFile.exists()) {
                        replyTo.send(Message.obtain(null, MSG_HALL_NOT_FOUND))
                        return@Thread
                    }
                    var lastValue = -1
                    while (!Thread.currentThread().isInterrupted) {
                        val value = stateFile.readText().trim().toIntOrNull()
                        if (value != null && value != lastValue) {
                            val what = if (value == 0) MSG_LID_CLOSED else MSG_LID_OPEN
                            Log.v("S22PresScreenServ", "Hall sensor: state=$value")
                            replyTo.send(Message.obtain(null, what))
                            lastValue = value
                        }
                        Thread.sleep(HALL_POLL_MS)
                    }
                } catch (e: InterruptedException) {
                    Log.i("S22PresScreenServ", "Hall monitor stopped")
                } catch (e: Exception) {
                    Log.e("S22PresScreenServ", "Hall monitor failed", e)
                }
            }.apply {
                name = "hall-sensor-monitor"
                isDaemon = true
                start()
            }
        }

        // Requires SELinux: allow magisk sysfs/sysfs_graphics file { open write }
        private fun writeBacklight(path: String, value: Int) {
            try {
                FileOutputStream(path).use { fos ->
                    fos.write(value.toString().toByteArray())
                }
            } catch (e: Exception) {
                Log.w("S22PresScreenServ", "Failed to write backlight $path=$value", e)
            }
        }

        override fun handleMessage(msg: Message) {
            if (msg.what == CMD_START_HALL_MONITOR) {
                if (File(HALL_STATE_PATH).exists() && msg.replyTo != null) {
                    Log.i("S22PresScreenServ", "Hall sensor sysfs found")
                    startHallMonitor(msg.replyTo)
                } else {
                    Log.w("S22PresScreenServ", "Hall sensor not found, proximity fallback")
                    msg.replyTo?.send(Message.obtain(null, MSG_HALL_NOT_FOUND))
                }
                return
            }
            try {
                val setDisplayPowerMode = SurfaceControl::class.java.getMethod(
                    "setDisplayPowerMode", IBinder::class.java, Int::class.java
                )
                val powerModeOff = 0
                val powerModeNormal = 2
                when (msg.what) {
                    CMD_SECONDARY_WAKE -> {
                        Runtime.getRuntime().exec("input keyevent KEYCODE_WAKEUP")
                        setDisplayPowerMode.invoke(
                            null, Globals.token as IBinder?, powerModeNormal
                        )
                        Log.v("S22PresScreenServ", "Secondary display wake")
                    }
                    CMD_SECONDARY_OFF -> {
                        setDisplayPowerMode.invoke(null, Globals.token as IBinder?, powerModeOff)
                        Log.v("S22PresScreenServ", "Secondary display off")
                    }
                    CMD_MAIN_OFF -> {
                        setDisplayPowerMode.invoke(null, Globals.token1 as IBinder?, powerModeOff)
                        Log.v("S22PresScreenServ", "Main display off")
                    }
                    CMD_MAIN_ON -> {
                        setDisplayPowerMode.invoke(null, Globals.token1 as IBinder?, powerModeNormal)
                        Log.v("S22PresScreenServ", "Main display on")
                    }
                }
            } catch (e: ReflectiveOperationException) {
                Log.e("S22PresScreenServ", "SurfaceControl operation failed", e)
            } catch (e: SecurityException) {
                Log.e("S22PresScreenServ", "Permission denied", e)
            }
        }
    }

    override fun onBind(intent: Intent): IBinder
    {
        Log.i("S22PresScreenServInit", "Hello! I've been bound :3")
        mMessenger = Messenger(IncomingHandler(this))
        return mMessenger.binder
    }
}
