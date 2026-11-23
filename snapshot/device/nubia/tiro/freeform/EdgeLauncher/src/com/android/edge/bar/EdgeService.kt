/*
 * Copyright (C) 2025 AxionOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.edge.bar

import android.app.ActivityTaskManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.IBinder
import android.os.UserHandle
import android.view.Display
import android.view.WindowManager
import com.android.edge.bar.freeform.FreeformTaskStackListener
import com.android.edge.bar.freeform.FreeformWindowCompose
import com.android.edge.bar.freeform.FreeformWindowManager

/**
 * Freeform-only host for Axion's original EdgeLauncher window implementation.
 * Sidebar/settings code is deliberately excluded; the window, resize and bubble behavior is kept.
 */
class EdgeService : Service() {
    companion object {
        private const val MAX_TASKS = 100
        private const val INVALID_TASK_ID = -1

        const val ACTION_LAUNCH_FREEFORM = "com.android.edge.bar.ACTION_LAUNCH_FREEFORM"
        const val ACTION_LAUNCH_DESKTOP_FREEFORM =
            "com.android.edge.bar.ACTION_LAUNCH_DESKTOP_FREEFORM"
        const val ACTION_BRING_ALL_TO_BACK = "com.android.edge.bar.ACTION_BRING_ALL_TO_BACK"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_ACTIVITY_NAME = "activity_name"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var activityTaskManager: ActivityTaskManager
    private lateinit var freeformWM: FreeformWindowManager
    private lateinit var taskStackListener: FreeformTaskStackListener

    private var screenWidth = 0
    private var screenHeight = 0
    private var initialized = false

    override fun onCreate() {
        super.onCreate()
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) {
            stopSelf()
            return
        }
        initializeFreeform()
    }

    private fun initializeFreeform() {
        if (initialized) return

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        activityTaskManager =
            getSystemService(Context.ACTIVITY_TASK_SERVICE) as ActivityTaskManager
        freeformWM = FreeformWindowManager.getInstance(this)
        taskStackListener = FreeformTaskStackListener(this, freeformWM)
        taskStackListener.register()

        val bounds = windowManager.currentWindowMetrics.bounds
        screenWidth = bounds.width()
        screenHeight = bounds.height()
        freeformWM.updateScreenDimensions(screenWidth, screenHeight)
        initialized = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) {
            stopSelf()
            return START_NOT_STICKY
        }
        initializeFreeform()

        when (intent?.action) {
            ACTION_LAUNCH_FREEFORM -> {
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                val activityName = intent.getStringExtra(EXTRA_ACTIVITY_NAME)
                if (packageName != null) launchFreeform(packageName, activityName)
            }

            ACTION_LAUNCH_DESKTOP_FREEFORM -> {
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                val activityName = intent.getStringExtra(EXTRA_ACTIVITY_NAME)
                val targetDisplayId =
                    intent.getIntExtra("target_display_id", Display.DEFAULT_DISPLAY)
                if (packageName != null) {
                    launchDesktopFreeform(packageName, activityName, targetDisplayId)
                }
            }

            ACTION_BRING_ALL_TO_BACK -> freeformWM.bringAllWindowsToBack()
        }

        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!initialized) return

        val bounds = windowManager.currentWindowMetrics.bounds
        val newWidth = bounds.width()
        val newHeight = bounds.height()
        if (newWidth == screenWidth && newHeight == screenHeight) return

        screenWidth = newWidth
        screenHeight = newHeight
        // The physical display may rotate temporarily while Quickstep/Overview is entering.
        // Only update the host bounds here. The freeform app orientation is driven exclusively
        // by onTaskRequestedOrientationChanged() for the task on its virtual display.
        freeformWM.updateScreenDimensions(newWidth, newHeight)
    }

    override fun onDestroy() {
        if (::taskStackListener.isInitialized) taskStackListener.unregister()
        if (::freeformWM.isInitialized) freeformWM.closeAllWindows()
        initialized = false
        super.onDestroy()
    }

    private fun launchFreeform(packageName: String, activityName: String? = null) {
        val existing = freeformWM.getWindow(packageName)
        if (existing != null) {
            if (existing.desktopMode) {
                freeformWM.closeWindow(packageName)
            } else {
                freeformWM.bringToFront(packageName)
                return
            }
        }

        val targetActivity = activityName ?: run {
            packageManager.getLaunchIntentForPackage(packageName)?.component?.className
        } ?: return

        val taskId = findRunningTaskId(packageName)
        FreeformWindowCompose(this, packageName, targetActivity, UserHandle.myUserId(), taskId)
    }

    private fun launchDesktopFreeform(
        packageName: String,
        activityName: String? = null,
        targetDisplayId: Int = Display.DEFAULT_DISPLAY,
    ) {
        val existing = freeformWM.getWindow(packageName)
        if (existing != null) {
            if (!existing.desktopMode) {
                freeformWM.closeWindow(packageName)
            } else {
                freeformWM.bringToFront(packageName)
                return
            }
        }

        val targetActivity = activityName ?: run {
            packageManager.getLaunchIntentForPackage(packageName)?.component?.className
        } ?: return

        val taskId = findRunningTaskId(packageName)
        FreeformWindowCompose(
            this,
            packageName,
            targetActivity,
            UserHandle.myUserId(),
            taskId,
            desktopMode = true,
            targetDisplayId = targetDisplayId,
        )
    }

    private fun findRunningTaskId(packageName: String): Int {
        return try {
            activityTaskManager.getTasks(MAX_TASKS)
                .filter { task ->
                    task.topActivity?.packageName == packageName ||
                        task.baseActivity?.packageName == packageName ||
                        task.baseIntent?.component?.packageName == packageName
                }
                // Prefer the newest task; stale fullscreen copies carry an old
                // scaled density and produce a wrong-sized UI on return.
                .maxByOrNull { it.taskId }
                ?.taskId ?: INVALID_TASK_ID
        } catch (_: Exception) {
            INVALID_TASK_ID
        }
    }
}
