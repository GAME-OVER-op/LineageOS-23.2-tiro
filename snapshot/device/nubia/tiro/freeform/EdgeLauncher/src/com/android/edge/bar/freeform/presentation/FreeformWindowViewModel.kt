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
package com.android.edge.bar.freeform.presentation

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import com.android.edge.bar.freeform.*
import com.android.edge.bar.freeform.data.*
import com.android.edge.bar.freeform.domain.FreeformConstants
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

class FreeformWindowViewModel(
    private val context: Context,
    private val packageName: String,
    private val activityName: String,
    private val userId: Int,
    private val taskId: Int = -1,
    private val desktopMode: Boolean = false,
    private val onWindowDead: (() -> Unit)? = null,
    private val serviceScope: CoroutineScope? = null
) {
    companion object {
        private const val TAG = "FreeformWindowViewModel"
    }

    private val handler = Handler(Looper.getMainLooper())

    val scope = serviceScope?.plus(SupervisorJob() + Dispatchers.Main)
        ?: CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val repository: FreeformRepository = FreeformRepositoryImpl(context)
    val inputInjector = InputInjector(context)
    val freeformWindowManager = FreeformWindowManager.getInstance(context)

    private val displayMetrics = context.resources.displayMetrics

    val config = FreeformConfig(context = context, densityScale = 0.85f, isDesktopMode = desktopMode)

    val initialX: Float = ((displayMetrics.widthPixels - config.width) / 2).toFloat()
    val initialY: Float = ((displayMetrics.heightPixels - config.height) / 2).toFloat()
    val screenWidth: Int = displayMetrics.widthPixels
    val screenHeight: Int = displayMetrics.heightPixels
    val density: Float = displayMetrics.density

    private var surfaceView: SurfaceView? = null
    private var appliedDisplayWidth = 0
    private var appliedDisplayHeight = 0
    private var appliedDisplayDensityDpi = 0

    var overlayView: View? = null

    val stateManager: FreeformStateManager by lazy {
        FreeformStateManager(
            context = context,
            repository = repository,
            scope = scope,
            windowPackageName = packageName,
            freeformWindowManager = freeformWindowManager,
            initialWidth = config.width,
            initialHeight = config.height,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            densityDpi = density,
            initialX = initialX,
            initialY = initialY,
            initialMode = if (desktopMode) WindowMode.DESKTOP else WindowMode.NORMAL
        )
    }
    
    private val windowEventListener = object : FreeformWindowManager.WindowEventListener {
        override fun onWindowOrientationChanged(packageName: String, isLandscape: Boolean) {
            if (packageName == this@FreeformWindowViewModel.packageName) {
                // StateManager applies the orientation asynchronously and the final
                // dimensions are persisted after the virtual-display resize. Saving
                // here races that update and stores the old size with the new
                // orientation, which causes size drift on the next rotation.
                stateManager.onOrientationChanged(isLandscape)
            }
        }
    }
    
    init {
        restoreInitialWindowState()
        scope.launch {
            stateManager.effect.collect { effect ->
                when (effect) {
                    is FreeformEffect.ResizeDisplay -> {
                        val result = try {
                            resizeVirtualDisplay(effect.width, effect.height)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Result.failure(e)
                        }
                        effect.completion.complete(result)
                    }
                }
            }
        }
        freeformWindowManager.addListener(windowEventListener)
    }
    
    val windowState: StateFlow<WindowState> get() = stateManager.state

    fun setupSurfaceView(view: SurfaceView) {
        surfaceView = view
        val (width, height) = stateManager.getAppSurfaceDimensions()
        // The host may transiently lay this view out at bubble size (or stretch it
        // during a drag). Only resize the output buffer when the app display resizes.
        view.holder.setFixedSize(width, height)
        view.isClickable = true
        view.isFocusable = true
        view.isFocusableInTouchMode = true

        view.setOnTouchListener { v, event ->
            val currentState = stateManager.state.value
            val displayId = currentState.displayId
            if (displayId != Display.INVALID_DISPLAY && displayId > 0) {
                // Keep the injector in sync with the current virtual display;
                // a stale id silently drops every touch after a resize/recreate.
                inputInjector.setDisplayId(displayId)
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    inputInjector.setScale(v.width, v.height, appliedDisplayWidth, appliedDisplayHeight)
                }

                val result = inputInjector.injectTouchEvent(event)
                if (!result && event.actionMasked == MotionEvent.ACTION_DOWN) {
                    Log.w(TAG, "Failed to inject touch event to displayId=$displayId")
                }
            } else {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    Log.w(TAG, "Touch ignored - invalid displayId: $displayId")
                }
            }
            true
        }
    }

    private suspend fun resizeVirtualDisplay(
        width: Int,
        height: Int,
        force: Boolean = false
    ): Result<Unit> {
        val currentState = stateManager.state.value
        val displayId = currentState.displayId
        if (displayId < 0) {
            return Result.failure(IllegalStateException("Invalid displayId: $displayId"))
        }
        
        val token = (repository as? FreeformRepositoryImpl)?.getAppToken()
            ?: return Result.failure(IllegalStateException("Missing freeform app token"))

        val targetDensity = config.densityForWidth(width)

        // The cached "applied" values only describe what WE last sent to the display.
        // After a task is moved onto a freshly created display (returning from
        // fullscreen) or a form change is reverted, the Activity can still be
        // carrying the previous display's density even though these fields match
        // the target. In those cases the resize must be forced so the new density
        // is actually applied to the task; the early return would leave a
        // too-large/too-small UI until the app is restarted.
        if (!force &&
                appliedDisplayWidth == width &&
                appliedDisplayHeight == height &&
                appliedDisplayDensityDpi == targetDensity) {
            saveCurrentWindowState()
            return Result.success(Unit)
        }

        surfaceView?.holder?.setFixedSize(width, height)
        return repository.resizeDisplay(token, width, height, targetDensity)
            .onSuccess {
                appliedDisplayWidth = width
                appliedDisplayHeight = height
                appliedDisplayDensityDpi = targetDensity
                surfaceView?.let { inputInjector.setScale(it.width, it.height, width, height) }
                Log.d(TAG, "Virtual display resized to ${width}x${height} at ${targetDensity}dpi")
                saveCurrentWindowState()
            }
            .onFailure {
                surfaceView?.holder?.setFixedSize(appliedDisplayWidth, appliedDisplayHeight)
                Log.e(TAG, "Failed to resize virtual display", it)
            }
    }

    private fun resolveInitialOrientation(packageName: String, activityName: String): Int {
        return try {
            val ensureComponent = ComponentName(packageName, activityName)
            val activityInfo = context.packageManager.getActivityInfo(ensureComponent, 0)
            activityInfo.screenOrientation
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve initial orientation for $packageName/$activityName", e)
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun isLandscapeOrientation(orientation: Int): Boolean {
        return when (orientation) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE -> true
            else -> false
        }
    }
    
    private fun isPortraitOrientation(orientation: Int): Boolean {
        return when (orientation) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT -> true
            else -> false
        }
    }
    
    private fun resolveInitialLandscape(packageName: String, activityName: String): Boolean {
        val orientation = resolveInitialOrientation(packageName, activityName)
        if (isLandscapeOrientation(orientation)) return true
        if (isPortraitOrientation(orientation)) return false
        return false
    }

    
    private fun getPrefs() = context.getSharedPreferences("freeform_window_prefs", Context.MODE_PRIVATE)
    private fun getPrefKey(suffix: String): String {
        val modePrefix = if (desktopMode) "desktop_" else ""
        return "${packageName}_${activityName}_$modePrefix$suffix"
    }

    private fun saveWindowState(width: Int, height: Int, isLandscape: Boolean) {
        getPrefs().edit().apply {
            putInt(getPrefKey("width"), width)
            putInt(getPrefKey("height"), height)
            putBoolean(getPrefKey("is_landscape"), isLandscape)
            apply()
        }
    }

    private fun saveCurrentWindowState() {
        val state = stateManager.state.value
        if (state.mode == WindowMode.BUBBLE) {
            saveWindowState(state.savedWidth, state.savedHeight, state.isLandscape)
        } else {
            saveWindowState(state.width, state.height, state.isLandscape)
        }
    }
    
    private fun clearWindowState() {
         getPrefs().edit().apply {
            remove(getPrefKey("width"))
            remove(getPrefKey("height"))
            remove(getPrefKey("is_landscape"))
            apply()
        }
    }

    private data class PersistedState(val width: Int, val height: Int, val isLandscape: Boolean)

    private fun restoreWindowState(): PersistedState? {
        val prefs = getPrefs()
        if (!prefs.contains(getPrefKey("width"))) return null
        
        val width = prefs.getInt(getPrefKey("width"), config.width)
        val height = prefs.getInt(getPrefKey("height"), config.height)
        val isLandscape = prefs.getBoolean(getPrefKey("is_landscape"), false)
        return PersistedState(width, height, isLandscape)
    }

    private fun restoreInitialWindowState() {
        val orientation = resolveInitialOrientation(packageName, activityName)
        var isLandscape = isLandscapeOrientation(orientation)
        val isPortrait = isPortraitOrientation(orientation)
        
        var windowWidth = config.width
        var windowHeight = config.height
        
        val persisted = restoreWindowState()
        if (persisted != null) {
            windowWidth = persisted.width
            windowHeight = persisted.height
            isLandscape = persisted.isLandscape
            Log.i(TAG, "Restored persisted state: ${windowWidth}x${windowHeight}, isLandscape=$isLandscape")
        } else {
            Log.i(TAG, "Resolved orientation: $orientation (landscape=$isLandscape, portrait=$isPortrait) for $packageName/$activityName")
            if (isLandscape && windowWidth < windowHeight) {
                val temp = windowHeight
                windowWidth = (windowHeight * 1.3f).toInt()
                windowHeight = temp.coerceAtMost(config.width)
                Log.i(TAG, "Landscape app detected, adjusted dimensions to ${windowWidth}x${windowHeight}")
            } else if (!isLandscape && windowWidth > windowHeight) {
                val temp = windowWidth
                windowWidth = windowHeight
                windowHeight = temp
                Log.i(TAG, "Portrait adjustment, swapped to ${windowWidth}x${windowHeight}")
            }
        }
        
        stateManager.restoreWindowSize(windowWidth, windowHeight, isLandscape)
        val restoredState = stateManager.state.value
        saveWindowState(restoredState.width, restoredState.height, isLandscape)
    }

    fun onSurfaceAvailable(surface: Surface, onDisplayReady: (Int) -> Unit) {
        val (displayWidth, displayHeight) = stateManager.getAppSurfaceDimensions()
        val displayDensity = config.densityForWidth(displayWidth)

        Log.i(
            TAG,
            "Surface ready for virtual display ${displayWidth}x${displayHeight} at ${displayDensity}dpi"
        )

        appliedDisplayWidth = displayWidth
        appliedDisplayHeight = displayHeight
        appliedDisplayDensityDpi = displayDensity
        surfaceView?.let { inputInjector.setScale(it.width, it.height, displayWidth, displayHeight) }

        scope.launch {
            var attachFinished = false
            var attachTimeoutJob: Job? = null

            suspend fun finishAttach(displayId: Int, onDisplayReady: (Int) -> Unit) {
                if (attachFinished) return
                attachFinished = true
                attachTimeoutJob?.cancel()
                stateManager.markSurfaceReady()
                onDisplayReady(displayId)
                delay(FreeformConstants.DELAY_APP_LAUNCH_VEIL_MS)
                stateManager.dispatch(WindowEvent.AppLaunchComplete)
            }

            fun failAttach(message: String, throwable: Throwable? = null) {
                if (attachFinished) return
                attachFinished = true
                attachTimeoutJob?.cancel()
                if (throwable != null) {
                    Log.e(TAG, message, throwable)
                } else {
                    Log.e(TAG, message)
                }
                stateManager.dispatch(WindowEvent.AppLaunchComplete)
                handler.post { onWindowDead?.invoke() }
            }

            suspend fun launchAppOnDisplay(displayId: Int, onDisplayReady: (Int) -> Unit) {
                repository.launchApp(
                    packageName = packageName,
                    activityName = activityName,
                    displayId = displayId,
                    userId = userId
                ).onSuccess {
                    Log.i(TAG, "App launched: $packageName/$activityName on display $displayId")
                    delay(FreeformConstants.DELAY_SURFACE_SETTLE_MS)
                    val launchedTaskId = repository.findRunningTaskId(packageName, activityName)
                    if (launchedTaskId == -1 || !repository.isTaskOnDisplay(launchedTaskId, displayId)) {
                        failAttach("App launch did not attach $packageName to display $displayId")
                        return
                    }
                    finishAttach(displayId, onDisplayReady)
                }.onFailure {
                    failAttach("Failed to launch app", it)
                }
            }

            attachTimeoutJob = launch {
                delay(FreeformConstants.DELAY_ATTACH_TIMEOUT_MS)
                failAttach("Timed out waiting for $packageName to attach to freeform display")
            }

            val callback = object : FreeformRepository.FreeformCallback {
                override fun onDisplayAdded(displayId: Int) {
                    scope.launch {
                        if (attachFinished || displayReleaseStarted) return@launch
                        Log.i(TAG, "onDisplayAdded: displayId=$displayId, taskId=$taskId")
                        stateManager.setDisplayId(displayId)
                        inputInjector.setDisplayId(displayId)
                        if (taskId != -1) {
                            repository.moveRootTaskToDisplay(taskId, displayId)
                                .onSuccess {
                                    Log.i(TAG, "Moved task $taskId to display $displayId")
                                    delay(FreeformConstants.DELAY_SURFACE_SETTLE_MS)
                                    if (repository.isTaskOnDisplay(taskId, displayId)) {
                                        // The task arrives from the default display carrying its
                                        // fullscreen density. Our cached applied* fields still
                                        // match the freshly created display, so a normal resize
                                        // would be skipped and the app would keep the wrong UI
                                        // scale. Force the density onto the moved task.
                                        // resizeVirtualDisplay expects the app-surface size,
                                        // not the outer window size: the window also contains
                                        // the title bar and the bottom resize handle, which
                                        // are not part of the TextureView. Passing the window
                                        // size here makes the virtual display taller than its
                                        // surface, so apps render vertically squeezed (circles
                                        // look like horizontal ovals) until the next resize.
                                        val (displayWidth, displayHeight) = stateManager.getAppSurfaceDimensions()
                                        resizeVirtualDisplay(
                                            displayWidth,
                                            displayHeight,
                                            force = true,
                                        )
                                        finishAttach(displayId, onDisplayReady)
                                    } else {
                                        failAttach("Moved task $taskId did not attach to display $displayId")
                                    }
                                }
                                .onFailure { e ->
                                    Log.e(TAG, "Failed to move task $taskId, falling back to launch", e)
                                    launchAppOnDisplay(displayId, onDisplayReady)
                                }
                        } else {
                            launchAppOnDisplay(displayId, onDisplayReady)
                        }
                    }
                }

                override fun onDisplayPaused() {
                    Log.d(TAG, "Display paused")
                    stateManager.dispatch(WindowEvent.DisplayPaused)
                }

                override fun onDisplayResumed() {
                    Log.d(TAG, "Display resumed")
                    stateManager.dispatch(WindowEvent.DisplayResumed)
                }

                override fun onDisplayStopped() {
                    handler.post {
                        if (!displayReleaseStarted) {
                            Log.d(TAG, "Display stopped - cleaning up window")
                            stateManager.dispatch(WindowEvent.DisplayStopped)
                            onWindowDead?.invoke()
                        }
                    }
                }
            }

            val result = repository.createDisplay(surface, displayWidth, displayHeight, displayDensity, callback)
            if (result < 0) {
                failAttach("Failed to create display")
            } else {
                Log.d(TAG, "Display creation initiated...")
            }
        }
    }

    fun onBackPress() {
        val currentState = stateManager.state.value
        val displayId = currentState.displayId
        
        if (displayId < 0) {
            Log.w(TAG, "Cannot inject back press: invalid displayId $displayId")
            return
        }
        
        scope.launch {
            repository.injectBackKey(displayId)
                .onSuccess { Log.d(TAG, "Injected back button press to display $displayId") }
                .onFailure { Log.w(TAG, "Failed to inject back button press to display $displayId", it) }
        }
    }

    fun requestFocus() {
        overlayView?.requestFocus()
    }

    fun destroy() {
        stateManager.destroy()
        releaseVirtualDisplay()

        surfaceView = null  // SurfaceHolder owns and releases its surface.

        scope.cancel()
        freeformWindowManager.removeListener(windowEventListener)
    }

    /**
     * Releases the virtual display exactly once. Both [destroy] and the
     * fullscreen transition need this, and racing two releases (or releasing
     * after a new window has already attached) left the moved task with a
     * stale density. The caller that starts a fullscreen task must complete
     * this before launching, so the Activity is never created while the
     * scaled virtual display is still alive.
     */
    private var displayReleaseStarted = false

    private suspend fun releaseVirtualDisplaySuspend() {
        if (displayReleaseStarted) return
        displayReleaseStarted = true
        val token = (repository as? FreeformRepositoryImpl)?.getAppToken() ?: return
        repository.releaseDisplay(token)
            .onSuccess { Log.i(TAG, "Display released successfully") }
            .onFailure { Log.e(TAG, "Failed to release display", it) }
    }

    private fun releaseVirtualDisplay() {
        if (displayReleaseStarted) return
        displayReleaseStarted = true
        val token = (repository as? FreeformRepositoryImpl)?.getAppToken() ?: return
        val releaseScope = serviceScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main)
        releaseScope.launch(NonCancellable) {
            repository.releaseDisplay(token)
                .onSuccess { Log.i(TAG, "Display released successfully") }
                .onFailure { Log.e(TAG, "Failed to release display", it) }
        }
    }

    fun register(window: FreeformWindowCompose) {
        freeformWindowManager.registerWindow(packageName, window)
    }
    
    fun launchFullscreen(onComplete: () -> Unit) {
        scope.launch {
            try {
                // Release the scaled virtual display BEFORE starting the fullscreen
                // task. Previously the task was launched first and destroy() released
                // the display asynchronously; the Activity was created while a
                // scaled-density display still existed and inherited a wrong UI
                // scale (too small / too large) that survived even a restart.
                releaseVirtualDisplaySuspend()

                val intent = Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(packageName, activityName)
                    // A fresh task is required: CLEAR_TOP/SINGLE_TOP reuses the
                    // freeform ActivityRecord, which keeps the virtual display's
                    // scaled density (145/206) after the task is moved to the
                    // default display, so the app UI stays too large. Reusing it
                    // also makes ActivityStarter abort our launch options.
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                }
                // makeScaleUpAnimation already adds the source view's
                // on-screen location; passing it again as startX/startY offsets
                // the animation twice and puts it outside the display.
                val source = overlayView
                val options = if (source != null && source.isAttachedToWindow) {
                    ActivityOptions.makeScaleUpAnimation(
                        source,
                        0,
                        0,
                        source.width.coerceAtLeast(1),
                        source.height.coerceAtLeast(1),
                    )
                } else {
                    ActivityOptions.makeBasic()
                }
                options.setLaunchDisplayId(Display.DEFAULT_DISPLAY)
                context.startActivity(intent, options.toBundle())
                Log.i(TAG, "Launched $packageName/$activityName in fullscreen")

                // Keep the overlay alive until the scale-up animation has
                // finished. Closing it in finally made the window disappear
                // first and the app pop in with no visible transition.
                delay(450)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch activity in fullscreen", e)
            } finally {
                onComplete()
            }
        }
    }
}
