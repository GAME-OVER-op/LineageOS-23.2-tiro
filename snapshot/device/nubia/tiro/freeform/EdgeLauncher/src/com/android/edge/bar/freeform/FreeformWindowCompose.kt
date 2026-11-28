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

import android.content.Context
import android.view.Display
import android.util.Log
import android.view.SurfaceHolder
import com.android.edge.bar.freeform.presentation.FreeformStateManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import kotlin.math.roundToInt
import androidx.lifecycle.*
import com.android.edge.bar.freeform.presentation.FreeformWindowContent
import com.android.edge.bar.freeform.presentation.FreeformWindowViewModel
import com.android.edge.bar.freeform.presentation.WindowMode
import com.android.edge.bar.lifecycle.repeatWhenAttached

class FreeformWindowCompose(
    private val context: Context,
    private val packageName: String,
    private val activityName: String,
    private val userId: Int,
    private val taskId: Int = -1,
    val desktopMode: Boolean = false,
    private val targetDisplayId: Int = Display.DEFAULT_DISPLAY,
) : SurfaceHolder.Callback {

    companion object {
        private const val TAG = "FreeformWindowCompose"
    }

    private var destroyed = false

    private val windowController = WindowController(context, packageName, targetDisplayId)

    private val viewModel = FreeformWindowViewModel(
        context = context,
        packageName = packageName,
        activityName = activityName,
        userId = userId,
        taskId = taskId,
        desktopMode = desktopMode,
        onWindowDead = { destroy("App killed externally") },
    )

    val stateManager: FreeformStateManager get() = viewModel.stateManager

    init {
        viewModel.register(this)
        createFreeformView()
    }

    private fun createFreeformView() {
        val state = viewModel.windowState.value
        windowController.initWindowParams(state.width, state.height, state.x.roundToInt(), state.y.roundToInt())

        val composeView = ComposeView(context).apply {
            repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    setContent {
                        FreeformApp()
                    }
                }
            }
        }

        viewModel.overlayView = composeView

        windowController.createWindow(composeView) { success ->
            if (!success) {
                Log.e(TAG, "Failed to create freeform window")
                destroy("Failed to attach overlay")
            }
        }
    }

    @Composable
    private fun FreeformApp() {
        val isDark = isSystemInDarkTheme()
        val colorScheme = if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        
        LaunchedEffect(Unit) {
            viewModel.stateManager.loadAppIcon()
        }

        MaterialTheme(colorScheme = colorScheme) {
            FreeformWindowContent(
                stateManager = viewModel.stateManager,
                stateFlow = viewModel.windowState,
                freeformWindowManager = viewModel.freeformWindowManager,
                density = viewModel.density,
                scope = viewModel.scope,
                onCloseAndKill = { closeWindow() },
                onBack = { viewModel.onBackPress() },
                onBringToFront = { bringToFront() },
                onMaximizeFullscreen = { 
                    viewModel.launchFullscreen { 
                        destroy("Fullscreen launch finished")
                    } 
                },
                onUpdateWindowLayout = { x, y, w, h -> windowController.updateLayout(x.roundToInt(), y.roundToInt(), w, h) },
                onSetupSurfaceView = viewModel::setupSurfaceView,
                surfaceCallback = this@FreeformWindowCompose,
                inputInjector = viewModel.inputInjector
            )
        }
    }

    fun bringToFront() {
        windowController.bringToFront()
    }

    fun bringToBack() {
        windowController.bringToBack()
    }

    fun onOutsideTouch() {
        windowController.onOutsideTouch()
    }

    private fun closeWindow() {
        destroy("User closed freeform window")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (destroyed) return
        viewModel.onSurfaceAvailable(holder.surface) { displayId ->
            Log.i(TAG, "Display ready: $displayId")
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!destroyed) destroy("App surface detached")
    }

    fun requestFocus() {
        windowController.requestFocus()
    }

    fun destroy(reason: String) {
        if (destroyed) return
        destroyed = true
        Log.i(TAG, "destroy called: $reason")

        windowController.destroy(reason) {
            finishDestroy()
        }
    }

    /** Smooth handoff when the app is launched from the normal launcher while its
     * freeform window (or bubble) is still alive. Mirrors the in-overlay
     * "open fullscreen" transition instead of closing the window abruptly. */
    fun onLaunchedFromLauncher() {
        if (destroyed) return
        if (stateManager.state.value.mode == WindowMode.BUBBLE) {
            destroy("Launched from launcher while minimized")
            return
        }
        destroyed = true
        Log.i(TAG, "fullscreen handoff from launcher")

        windowController.destroyToFullscreen("Launched from launcher") {
            finishDestroy()
        }
    }

    private fun finishDestroy() {
        viewModel.destroy()
        viewModel.freeformWindowManager.unregisterWindow(packageName, this)
    }
}
