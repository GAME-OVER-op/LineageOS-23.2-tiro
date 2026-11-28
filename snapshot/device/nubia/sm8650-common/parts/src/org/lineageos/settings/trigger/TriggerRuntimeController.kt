/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.settings.trigger

import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.TaskStackListener
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display
import org.lineageos.settings.utils.getInt
import java.io.File

/** Publishes an atomic snapshot on framework events, never polls top-app/task state. */
object TriggerRuntimeController {
    private const val TAG = "TriggerRuntime"
    private val main = Handler(Looper.getMainLooper())
    private lateinit var context: Context
    private var initialized = false
    private var editor = false
    private var lastWritten: String? = null
    private var lastRotation = -1
    private val runtime = File("/data/system/nubia_triggers/runtime.conf")

    private val listener = object : TaskStackListener() {
        override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
            main.post { publish() }
        }
        override fun onTaskFocusChanged(taskId: Int, focused: Boolean) {
            if (focused) main.post { publish() }
        }
        override fun onTaskStackChanged() {
            main.post { publish() }
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_OFF || i.action == Intent.ACTION_SCREEN_ON ||
                i.action == Intent.ACTION_CONFIGURATION_CHANGED ||
                i.action == Intent.ACTION_USER_PRESENT) publish()
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = onDisplayChanged(displayId)
        override fun onDisplayRemoved(displayId: Int) = onDisplayChanged(displayId)
        override fun onDisplayChanged(displayId: Int) {
            // Ignore virtual displays and changes that are only to the refresh rate.
            if (displayId == Display.DEFAULT_DISPLAY && rotationOf(primaryDisplay()) != lastRotation) {
                publish()
            }
        }
    }

    private fun primaryDisplay(): Display? =
        context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)

    private fun rotationOf(display: Display?): Int = display?.rotation ?: -1

    @Synchronized fun initialize(c: Context) {
        if (initialized) { publish(); return }
        context = c.applicationContext
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        context.getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, main)
        try {
            ActivityTaskManager.getService().registerTaskStackListener(listener)
        } catch (e: Exception) {
            Log.e(TAG, "TaskStackListener registration failed; triggers will not activate", e)
            // Remain fail-closed if foreground cannot be determined.
        }
        initialized = true
        publish()
    }

    fun setEditing(isEditing: Boolean) { editor = isEditing; publish() }
    fun publish() {
        if (!initialized) return
        val pm = context.getSystemService(PowerManager::class.java)
        val display = primaryDisplay()
        lastRotation = rotationOf(display)
        val foreground = try {
            val root = ActivityTaskManager.getService().focusedRootTaskInfo
            // A freeform app on a virtual display can have global task focus, but
            // our synthetic contacts still go to the physical touchscreen.
            root?.takeIf { it.displayId == Display.DEFAULT_DISPLAY && it.isVisible }
                ?.topActivity?.packageName.orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read focused task; triggers will remain inactive", e)
            ""
        }
        val content = buildString {
            appendLine("version=1")
            appendLine("enabled=${if (getInt(context, TriggerController.KEY_TRIGGER_ENABLE, 0) == 1) 1 else 0}")
            appendLine("screen=${if (display != null && pm.isInteractive) 1 else 0}")
            appendLine("editing=${if (editor) 1 else 0}")
            appendLine("rotation=$lastRotation")
            appendLine("foreground=$foreground")
            TriggerProfileStore.load().forEach { p ->
                fun b(v: TriggerProfileStore.Binding): String =
                    "${if (v.enabled) 1 else 0}|${if (v.valid) 1 else 0}|${v.x}|${v.y}"
                appendLine("profile=${p.pkg}|${b(p.left)}|${b(p.right)}|${p.rotation}")
            }
        }
        if (content == lastWritten && runtime.isFile) return
        if (TriggerProfileStore.atomicWrite(runtime, content)) lastWritten = content
    }
}
