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
package com.android.edge.bar.freeform

import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.TaskStackListener
import android.content.Context
import android.content.pm.ActivityInfo
import android.util.Log

class FreeformTaskStackListener(
    private val context: Context,
    private val windowManager: FreeformWindowManager
) : TaskStackListener() {

    companion object {
        private const val TAG = "FreeformTaskStackListener"
    }

    private val activityTaskManager: ActivityTaskManager by lazy {
        context.getSystemService(Context.ACTIVITY_TASK_SERVICE) as ActivityTaskManager
    }

    private var isRegistered = false

    fun register() {
        if (!isRegistered) {
            try {
                activityTaskManager.registerTaskStackListener(this)
                isRegistered = true
                Log.d(TAG, "Task stack listener registered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register task stack listener", e)
            }
        }
    }

    fun unregister() {
        if (isRegistered) {
            try {
                activityTaskManager.unregisterTaskStackListener(this)
                isRegistered = false
                Log.d(TAG, "Task stack listener unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister task stack listener", e)
            }
        }
    }

    override fun onTaskRemoved(taskId: Int) {
        super.onTaskRemoved(taskId)
        Log.d(TAG, "Task removed: $taskId")
        try {
            val tasks = activityTaskManager.getTasks(100)
            val activePackages = tasks.mapNotNull { it.baseActivity?.packageName }.toSet()
            
            val trackedPackages = windowManager.getWindowPackages()
            for (packageName in trackedPackages) {
                if (packageName !in activePackages) {
                    Log.i(TAG, "Closing freeform window for removed task: $packageName")
                    windowManager.closeWindow(packageName)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling task removal", e)
        }
    }

    override fun onActivityPinned(packageName: String?, userId: Int, taskId: Int, stackId: Int) {
        super.onActivityPinned(packageName, userId, taskId, stackId)
        Log.d(TAG, "Activity pinned: $packageName")
    }

    override fun onActivityUnpinned() {
        super.onActivityUnpinned()
        Log.d(TAG, "Activity unpinned")
    }


    override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo?) {
        super.onTaskMovedToFront(taskInfo)
        val task = taskInfo ?: return
        val packageName = task.topActivity?.packageName ?: task.baseActivity?.packageName ?: return
        val window = windowManager.getWindow(packageName) ?: return
        val freeformDisplayId = window.stateManager.state.value.displayId

        if (freeformDisplayId >= 0 && task.displayId != freeformDisplayId) {
            // The app was launched from the normal launcher (or otherwise moved to
            // the default display). Hand the overlay over with the same grow-and-fade
            // animation used by the in-overlay "open fullscreen" action instead of
            // killing the window while the app pops in abruptly.
            Log.i(
                TAG,
                "Handing freeform window to fullscreen for $packageName: task moved to display ${task.displayId}, freeform display is $freeformDisplayId"
            )
            windowManager.getWindow(packageName)?.onLaunchedFromLauncher()
                ?: windowManager.closeWindow(packageName)
            return
        }

        // Do not raise an application-overlay window from a task-stack callback. Quickstep
        // changes task/front state while entering Overview, especially from landscape apps.
        // Raising the overlay here races the Overview transition and can bring the app back.
        Log.d(TAG, "Freeform task moved to front without changing overlay z-order: $packageName")
    }

    override fun onTaskRequestedOrientationChanged(taskId: Int, requestedOrientation: Int) {
        super.onTaskRequestedOrientationChanged(taskId, requestedOrientation)
        try {
            val tasks = activityTaskManager.getTasks(100)
            val taskInfo = tasks.find { it.taskId == taskId }
            val displayId = taskInfo?.displayId ?: return
            
            val isLandscape = resolveExplicitLandscape(requestedOrientation)
            if (isLandscape == null) {
                Log.d(TAG, "Ignoring non-explicit task orientation $requestedOrientation for task $taskId")
                return
            }
            Log.d(TAG, "Task $taskId orientation changed: $requestedOrientation (landscape=$isLandscape) on display $displayId")
            
            windowManager.notifyOrientationChanged(displayId, isLandscape)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling orientation change", e)
        }
    }

    private fun resolveExplicitLandscape(orientation: Int): Boolean? {
        return when (orientation) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE -> true

            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT -> false

            else -> null
        }
    }
}
