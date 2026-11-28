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

import android.content.Context
import android.graphics.Bitmap
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.platform.AndroidUiDispatcher
import com.android.edge.bar.freeform.FreeformWindowManager
import com.android.edge.bar.freeform.WindowSnapping
import com.android.edge.bar.freeform.data.FreeformRepository
import com.android.edge.bar.freeform.domain.FreeformConstants
import com.android.edge.bar.freeform.domain.coerceFreeformWindowSize
import com.android.edge.bar.freeform.dpToPx
import com.android.internal.R as InternalR
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

sealed class WindowEvent {
    data class DisplayCreated(val displayId: Int) : WindowEvent()
    object DisplayPaused : WindowEvent()
    object DisplayResumed : WindowEvent()
    object DisplayStopped : WindowEvent()
    
    object SurfaceSettled : WindowEvent()
    object AppLaunchComplete : WindowEvent()

    data class Drag(val deltaX: Float, val deltaY: Float) : WindowEvent()
    object DragEnd : WindowEvent()

    data class Minimize(val preferCurrentPosition: Boolean = false) : WindowEvent()
    object BubbleTap : WindowEvent()

    object ResizeEnd : WindowEvent()

    object Maximize : WindowEvent()

    object ResizeToFullscreen : WindowEvent()
    object ResizeToHalfLeft : WindowEvent()
    object ResizeToHalfRight : WindowEvent()

    data class IconLoaded(val icon: Bitmap) : WindowEvent()
    data class AppNameLoaded(val name: String) : WindowEvent()

    data class OrientationChanged(val isLandscape: Boolean) : WindowEvent()
}

sealed class FreeformEffect {
    data class ResizeDisplay(
        val width: Int,
        val height: Int,
        val completion: CompletableDeferred<Result<Unit>>
    ) : FreeformEffect()
}

enum class WindowMode {
    NORMAL,
    BUBBLE,
    DESKTOP
}

data class WindowState(
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Int,
    val height: Int,
    
    val mode: WindowMode = WindowMode.NORMAL,
    
    val displayId: Int = -1,
    val isPaused: Boolean = false,
    
    val isSurfaceReady: Boolean = false,
    val isAppLaunching: Boolean = true,
    val isDestroyed: Boolean = false,
    
    val savedX: Float = 0f,
    val savedY: Float = 0f,
    val savedWidth: Int,
    val savedHeight: Int,
    val savedBubbleX: Float = -1f,
    val savedBubbleY: Float = -1f,
    
    val appIcon: Bitmap? = null,
    val appName: String = "",
    val isResizing: Boolean = false,
    
    val snapPosition: WindowSnapping.SnapPosition? = null,
    
    val isLandscape: Boolean = false
)

class FreeformStateManager(
    private val context: Context,
    private val repository: FreeformRepository,
    private val scope: CoroutineScope,
    private val windowPackageName: String,
    private val freeformWindowManager: FreeformWindowManager,
    initialWidth: Int,
    initialHeight: Int,
    private var screenWidth: Int,
    private var screenHeight: Int,
    private val densityDpi: Float,
    initialX: Float = 0f,
    initialY: Float = 0f,
    private val initialMode: WindowMode = WindowMode.NORMAL
) {
    companion object {
        private const val TAG = "FreeformStateManager"
    }

    private val statusBarHeight: Int
        get() = context.resources.getDimensionPixelSize(InternalR.dimen.status_bar_height)

    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    private val _effect = MutableSharedFlow<FreeformEffect>()
    val effect: SharedFlow<FreeformEffect> = _effect.asSharedFlow()

    private val surfaceEventsManager = SurfaceEventsManager(scope)

    private val eventFlow = MutableSharedFlow<WindowEvent>(extraBufferCapacity = 64)
    private var acceptsDisplayPauseCallback = false
    private var bubblePositionAnimationJob: Job? = null
    private var bubbleNeedsResize = false

    private val _state = MutableStateFlow(WindowState(
        x = initialX,
        y = initialY,
        width = initialWidth,
        height = initialHeight,
        mode = initialMode,
        savedWidth = initialWidth,
        savedHeight = initialHeight,
        appName = windowPackageName
    ))
    val state: StateFlow<WindowState> = _state.asStateFlow()
    
    private val currentState: WindowState get() = _state.value
    
    val packageName: String get() = windowPackageName
    
    private val appSurfaceVerticalInset: Int
        get() = context.dpToPx(
            FreeformConstants.DESKTOP_TITLE_BAR_HEIGHT_DP +
                FreeformConstants.RESIZE_HANDLE_BOTTOM_HEIGHT_DP
        )

    fun getAppSurfaceDimensions(): Pair<Int, Int> {
        val state = currentState
        val minimized = state.mode == WindowMode.BUBBLE
        val width = if (minimized) state.savedWidth else state.width
        val height = if (minimized) state.savedHeight else state.height
        return width.coerceAtLeast(1) to (height - appSurfaceVerticalInset).coerceAtLeast(1)
    }

    private suspend fun resizeDisplaySurface() {
        val (displayWidth, displayHeight) = getAppSurfaceDimensions()
        val completion = CompletableDeferred<Result<Unit>>()
        _effect.emit(FreeformEffect.ResizeDisplay(displayWidth, displayHeight, completion))
        completion.await()
            .onFailure { Log.e(TAG, "Failed to resize display surface", it) }
    }
    
    init {
        scope.launch {
            eventFlow.collect { event ->
                processEvent(event)
            }
        }
        
        scope.launch {
            surfaceEventsManager.veilState.collect { veilState ->
                updateState { 
                    copy(isSurfaceReady = veilState is VeilState.Hidden) 
                }
            }
        }
        
        loadAppInfo()
    }
    
    fun dispatch(event: WindowEvent) {
        if (!eventFlow.tryEmit(event)) {
            scope.launch {
                eventFlow.emit(event)
            }
        }
    }
    
    private fun updateState(transform: WindowState.() -> WindowState) {
        _state.value = currentState.transform()
    }
    
    private suspend fun processEvent(event: WindowEvent) {
        Log.d(TAG, "Processing event: $event")
        
        when (event) {
            is WindowEvent.DisplayCreated -> handleDisplayCreated(event.displayId)
            is WindowEvent.DisplayPaused -> handleDisplayPaused()
            is WindowEvent.DisplayResumed -> handleDisplayResumed()
            is WindowEvent.DisplayStopped -> handleDisplayStopped()
            is WindowEvent.SurfaceSettled -> handleSurfaceSettled()
            is WindowEvent.AppLaunchComplete -> handleAppLaunchComplete()
            
            is WindowEvent.Drag -> handleDrag(event.deltaX, event.deltaY)
            is WindowEvent.DragEnd -> handleDragEnd()
            
            is WindowEvent.Minimize -> handleMinimize(event.preferCurrentPosition)
            is WindowEvent.BubbleTap -> handleBubbleExpand()
            
            is WindowEvent.ResizeEnd -> handleResizeEnd()
            is WindowEvent.Maximize -> handleMaximize()

            is WindowEvent.ResizeToFullscreen -> handleResizeToFullscreen()
            is WindowEvent.ResizeToHalfLeft -> handleResizeToHalf(isLeft = true)
            is WindowEvent.ResizeToHalfRight -> handleResizeToHalf(isLeft = false)

            is WindowEvent.IconLoaded -> updateState { copy(appIcon = event.icon) }
            is WindowEvent.AppNameLoaded -> updateState { copy(appName = event.name) }
            
            is WindowEvent.OrientationChanged -> handleOrientationChanged(event.isLandscape)
        }
    }
    
    private fun handleDisplayCreated(displayId: Int) {
        surfaceEventsManager.dispatch(SurfaceEvent.ShowVeilRequested(VeilReason.DISPLAY_CREATED))
        surfaceEventsManager.dispatch(SurfaceEvent.OperationStarted("display_init"))
        updateState { copy(displayId = displayId) }
        freeformWindowManager.registerDisplayId(displayId, windowPackageName)
    }
    
    private suspend fun handleOrientationChanged(isLandscape: Boolean) {
        if (currentState.isLandscape == isLandscape) return
        
        Log.d(TAG, "Orientation changed to landscape=$isLandscape (mode=${currentState.mode})")
        
        if (currentState.mode == WindowMode.BUBBLE) {
            // Keep one configured window size even while minimized. Rotating the
            // host display only rotates that saved rectangle; it does not invent
            // a second portrait/landscape size.
            updateState {
                copy(
                    savedWidth = savedHeight,
                    savedHeight = savedWidth,
                    isLandscape = isLandscape
                )
            }
            bubbleNeedsResize = !bubbleNeedsResize
            snapBubbleToSafeZone()
            return
        }

        if (currentState.mode != WindowMode.NORMAL && currentState.mode != WindowMode.DESKTOP) return
        
        Log.d(TAG, "Handling window rotation without changing configured size")
        
        surfaceEventsManager.dispatch(SurfaceEvent.ShowVeilRequested(VeilReason.RESIZE_STARTED))
        surfaceEventsManager.dispatch(SurfaceEvent.OperationStarted("orientation_change"))
        
        // Rotate the existing rectangle exactly. Do not run the normal resize
        // coercion here: screen margins/status-bar limits can shave pixels from
        // the window on every 90-degree turn and permanently drift its size.
        val newWidth = currentState.height
        val newHeight = currentState.width
        
        updateState {
            copy(
                width = newWidth,
                height = newHeight,
                isLandscape = isLandscape,
                isPaused = true
            )
        }
        clampWindowToSafeZone()
        
        resizeDisplaySurface()
        
        delay(FreeformConstants.DELAY_SURFACE_SETTLE_MS)
        surfaceEventsManager.dispatch(SurfaceEvent.OperationCompleted("orientation_change"))
        dispatch(WindowEvent.SurfaceSettled)
    }
    
    private fun handleDisplayPaused() {
        if (!acceptsDisplayPauseCallback) {
            Log.d(TAG, "Ignoring stale display pause callback in mode=${currentState.mode}")
            return
        }
        acceptsDisplayPauseCallback = false
        surfaceEventsManager.dispatch(SurfaceEvent.ShowVeilRequested(VeilReason.DISPLAY_PAUSED))
        surfaceEventsManager.dispatch(SurfaceEvent.OperationStarted("display_paused"))
        updateState { copy(isPaused = true) }
    }
    
    private fun handleDisplayResumed() {
        acceptsDisplayPauseCallback = false
        updateState { copy(isPaused = false) }
    }
    
    private fun handleDisplayStopped() {
        Log.d(TAG, "Display stopped")
        surfaceEventsManager.dispatch(SurfaceEvent.ShowVeilRequested(VeilReason.DISPLAY_STOPPED))
    }

    private suspend fun handleSurfaceSettled() {
        Log.d(TAG, "Surface settled")
        updateState { copy(isPaused = false) }
        delay(FreeformConstants.DELAY_SURFACE_SETTLE_MS)
        surfaceEventsManager.dispatch(SurfaceEvent.SurfaceReady)
        surfaceEventsManager.dispatch(SurfaceEvent.OperationCompleted("*"))
        surfaceEventsManager.dispatch(SurfaceEvent.HideVeilRequested)
    }
    
    private fun handleAppLaunchComplete() {
        Log.d(TAG, "App launch complete - hiding launch veil")
        updateState { copy(isAppLaunching = false) }
    }
    
    private fun handleDrag(deltaX: Float, deltaY: Float) {
        val minY = statusBarHeight.toFloat()
        if (currentState.mode == WindowMode.BUBBLE) {
            cancelBubblePositionAnimation()
        }
        updateState {
            val maxY = maxOf(minY, (screenHeight - height).toFloat())
            val maxBubbleX = maxOf(0f, (screenWidth - width).toFloat())
            when (mode) {
                WindowMode.BUBBLE -> {
                    copy(
                        x = (x + deltaX).coerceIn(0f, maxBubbleX),
                        y = (y + deltaY).coerceIn(minY, maxY),
                        snapPosition = null
                    )
                }
                WindowMode.NORMAL, WindowMode.DESKTOP -> copy(
                    x = x + deltaX,
                    y = (y + deltaY).coerceIn(minY, maxY),
                    snapPosition = null
                )
            }
        }

        if (currentState.mode == WindowMode.NORMAL) {
            val threshold = currentState.width / 4
            val windowRight = currentState.x + currentState.width
            val isOffRightEdge = windowRight > screenWidth + threshold
            val isOffLeftEdge = currentState.x < -threshold

            if ((isOffRightEdge || isOffLeftEdge) && !currentState.isResizing) {
                dispatch(WindowEvent.Minimize(preferCurrentPosition = true))
            }
        }
    }

    private fun handleDragEnd() {
        when (currentState.mode) {
            WindowMode.BUBBLE -> freeformWindowManager.finishBubbleDrag(windowPackageName)
            WindowMode.NORMAL, WindowMode.DESKTOP -> clampWindowToSafeZone()
        }
    }
    
    private suspend fun handleMinimize(preferCurrentPosition: Boolean) {
        if (currentState.mode != WindowMode.NORMAL) {
            Log.d(TAG, "Minimize ignored for mode=${currentState.mode}")
            return
        }

        val slot = freeformWindowManager.getNextBubbleSlot(windowPackageName)
        val bubbleSizePx = context.dpToPx(FreeformConstants.BUBBLE_SIZE_DP)
        val topOffset = statusBarHeight
        val safeEdge = context.dpToPx(FreeformConstants.SAFE_ZONE_EDGE_DP)
        val safeBottom = context.dpToPx(FreeformConstants.SAFE_ZONE_BOTTOM_DP)
        val maxBubbleY = maxOf(
            topOffset.toFloat(),
            (screenHeight - safeBottom - bubbleSizePx).toFloat()
        )

        val bubbleX: Float
        val bubbleY = freeformWindowManager.getBubbleSlotY(slot).coerceIn(topOffset.toFloat(), maxBubbleY)

        if (preferCurrentPosition) {
            val windowCenterX = currentState.x + currentState.width / 2f
            bubbleX = if (windowCenterX < screenWidth / 2f) {
                safeEdge.toFloat()
            } else {
                (screenWidth - bubbleSizePx - safeEdge).toFloat()
            }
        } else if (currentState.savedBubbleX >= 0f) {
            val savedCenterX = currentState.savedBubbleX + bubbleSizePx / 2f
            bubbleX = if (savedCenterX < screenWidth / 2f) {
                safeEdge.toFloat()
            } else {
                (screenWidth - bubbleSizePx - safeEdge).toFloat()
            }
        } else {
            bubbleX = (screenWidth - bubbleSizePx - safeEdge).toFloat()
        }

        bubbleNeedsResize = false
        updateState {
            copy(
                mode = WindowMode.BUBBLE,
                savedX = x,
                savedY = y,
                savedWidth = width,
                savedHeight = height,
                x = bubbleX,
                y = bubbleY,
                width = bubbleSizePx,
                height = bubbleSizePx
            )
        }

        // Do NOT power the virtual display off here. Display.STATE_OFF makes
        // WindowManager add a sleep token for this display, which puts every
        // Activity on it to sleep; on resume they are recreated, so the app
        // loses its task state (e.g. playback) while the process stays alive.
        // The bubble only hides the window visually; display and task stay on.
    }

    private suspend fun handleBubbleExpand() {
        if (currentState.mode != WindowMode.BUBBLE) return
        cancelBubblePositionAnimation()
        freeformWindowManager.releaseBubbleSlot(windowPackageName)

        val targetWidth = currentState.savedWidth
        val targetHeight = currentState.savedHeight
        val resizeNeeded = bubbleNeedsResize && currentState.displayId >= 0
        bubbleNeedsResize = false

        val safeBottom = context.dpToPx(FreeformConstants.SAFE_ZONE_BOTTOM_DP)
        val minY = statusBarHeight.toFloat()
        val maxY = maxOf(minY, (screenHeight - safeBottom - targetHeight).toFloat())

        val centeredX = ((screenWidth - targetWidth) / 2f).coerceAtLeast(0f)
        val centeredY = (
            minY + (screenHeight - safeBottom - minY - targetHeight) / 2f
        ).coerceIn(minY, maxY)

        updateState {
            copy(
                mode = WindowMode.NORMAL,
                x = centeredX,
                y = centeredY,
                width = targetWidth,
                height = targetHeight,
                savedBubbleX = x,
                savedBubbleY = y,
                isPaused = resizeNeeded
            )
        }

        if (resizeNeeded) {
            surfaceEventsManager.dispatch(SurfaceEvent.ShowVeilRequested(VeilReason.BUBBLE_EXPAND))
            surfaceEventsManager.dispatch(SurfaceEvent.OperationStarted("bubble_expand"))

            // A rotation while minimized changed the saved rectangle. Otherwise
            // the live surface is already the right size and needs no veil.
            resizeDisplaySurface()
            delay(FreeformConstants.DELAY_SURFACE_SETTLE_MS)
            dispatch(WindowEvent.SurfaceSettled)
            surfaceEventsManager.dispatch(SurfaceEvent.OperationCompleted("bubble_expand"))
        }
    }

    private fun handleResizeStart() {
        updateState { copy(isResizing = true) }
        surfaceEventsManager.dispatch(SurfaceEvent.ShowVeilRequested(VeilReason.RESIZE_STARTED))
        surfaceEventsManager.dispatch(SurfaceEvent.OperationStarted("resize"))
        updateState { copy(isPaused = true) }
    }
    
    private fun coerceWindowSizeForMode(
        newWidth: Int,
        newHeight: Int,
        isLandscape: Boolean = currentState.isLandscape
    ): Pair<Int, Int> {
        if (currentState.mode == WindowMode.DESKTOP) {
            val minWidth = context.dpToPx(FreeformConstants.DESKTOP_MIN_WIDTH_DP)
            val minHeight = context.dpToPx(FreeformConstants.DESKTOP_MIN_HEIGHT_DP)
            val taskbarHeight = context.dpToPx(FreeformConstants.DESKTOP_TASKBAR_HEIGHT_DP)

            val maxWidth = screenWidth.coerceAtLeast(minWidth)
            val maxHeight = (screenHeight - taskbarHeight).coerceAtLeast(minHeight)

            return newWidth.coerceIn(minWidth, maxWidth) to newHeight.coerceIn(minHeight, maxHeight)
        }

        val baseMinWidth = context.dpToPx(FreeformConstants.MIN_WINDOW_WIDTH_DP)
        val baseMinHeight = context.dpToPx(FreeformConstants.MIN_WINDOW_HEIGHT_DP.toInt())
        val minWidth = if (isLandscape) baseMinHeight else baseMinWidth
        val minHeight = if (isLandscape) baseMinWidth else baseMinHeight
        val margin = context.dpToPx(FreeformConstants.WINDOW_SCREEN_MARGIN_DP)
        val maxWidth = (screenWidth - margin * 2).coerceAtLeast(minWidth)
        val maxHeight = (screenHeight - statusBarHeight - margin).coerceAtLeast(minHeight)

        return coerceFreeformWindowSize(
            newWidth,
            newHeight,
            minWidth,
            minHeight,
            maxWidth,
            maxHeight
        )
    }

    private fun handleResize(newWidth: Int, newHeight: Int, newX: Float) {
        val (width, height) = coerceWindowSizeForMode(newWidth, newHeight)

        if (newX.isNaN()) {
            updateState { copy(width = width, height = height) }
        } else {
            updateState { copy(width = width, height = height, x = newX) }
        }
    }

    private suspend fun handleResizeEnd() {
        updateState { 
            copy(
                isResizing = false,
                savedWidth = width,
                savedHeight = height
            ) 
        }

        resizeDisplaySurface()

        delay(FreeformConstants.DELAY_SURFACE_SETTLE_MS)
        surfaceEventsManager.dispatch(SurfaceEvent.OperationCompleted("resize"))
        dispatch(WindowEvent.SurfaceSettled)
    }
    
    private suspend fun handleMaximize() {
        if (currentState.mode != WindowMode.NORMAL) return
        
        surfaceEventsManager.dispatch(SurfaceEvent.ShowVeilRequested(VeilReason.RESIZE_STARTED))
        surfaceEventsManager.dispatch(SurfaceEvent.OperationStarted("maximize"))
        
        val padding = context.dpToPx(32)
        val maxWidth = screenWidth - (padding * 2)
        val maxHeight = screenHeight - (padding * 2)
        
        val targetWidth: Int
        val targetHeight: Int
        val currentAspectRatio = currentState.width.toFloat() / currentState.height.toFloat()
        
        if (maxWidth / currentAspectRatio <= maxHeight) {
            targetWidth = maxWidth
            targetHeight = (maxWidth / currentAspectRatio).toInt()
        } else {
            targetHeight = maxHeight
            targetWidth = (maxHeight * currentAspectRatio).toInt()
        }
        
        val targetX = ((screenWidth - targetWidth) / 2).toFloat()
        val targetY = ((screenHeight - targetHeight) / 2).toFloat()
        
        updateState {
            copy(
                x = targetX,
                y = targetY,
                width = targetWidth,
                height = targetHeight,
                // Keep the saved form in sync with the visible one. Otherwise
                // minimize/expand restores an older size and recomputes a
                // different density for the same window ("loses the previous
                // form/DPI" when switching back and forth).
                savedWidth = targetWidth,
                savedHeight = targetHeight,
                isPaused = true
            )
        }
        
        resizeDisplaySurface()
        
        delay(FreeformConstants.DELAY_SURFACE_SETTLE_MS)
        surfaceEventsManager.dispatch(SurfaceEvent.OperationCompleted("maximize"))
        dispatch(WindowEvent.SurfaceSettled)
    }

    private suspend fun handleResizeToFullscreen() {
        if (currentState.mode != WindowMode.DESKTOP) return

        surfaceEventsManager.dispatch(SurfaceEvent.ShowVeilRequested(VeilReason.RESIZE_STARTED))
        surfaceEventsManager.dispatch(SurfaceEvent.OperationStarted("resize_fullscreen"))

        val taskbarHeight = context.dpToPx(FreeformConstants.DESKTOP_TASKBAR_HEIGHT_DP)
        val statusBarHeight = context.dpToPx(FreeformConstants.DESKTOP_STATUS_BAR_HEIGHT_DP)
        val targetWidth = screenWidth
        val targetHeight = screenHeight - taskbarHeight - statusBarHeight
        val targetX = 0f
        val targetY = statusBarHeight.toFloat()

        updateState {
            copy(
                x = targetX,
                y = targetY,
                width = targetWidth,
                height = targetHeight,
                savedWidth = targetWidth,
                savedHeight = targetHeight,
                isPaused = true
            )
        }

        resizeDisplaySurface()

        delay(FreeformConstants.DELAY_SURFACE_SETTLE_MS)
        surfaceEventsManager.dispatch(SurfaceEvent.OperationCompleted("resize_fullscreen"))
        dispatch(WindowEvent.SurfaceSettled)
    }

    private suspend fun handleResizeToHalf(isLeft: Boolean) {
        if (currentState.mode != WindowMode.DESKTOP) return

        surfaceEventsManager.dispatch(SurfaceEvent.ShowVeilRequested(VeilReason.RESIZE_STARTED))
        surfaceEventsManager.dispatch(SurfaceEvent.OperationStarted("resize_half"))

        val taskbarHeight = context.dpToPx(FreeformConstants.DESKTOP_TASKBAR_HEIGHT_DP)
        val statusBarHeight = context.dpToPx(FreeformConstants.DESKTOP_STATUS_BAR_HEIGHT_DP)
        val targetWidth = screenWidth / 2
        val targetHeight = screenHeight - taskbarHeight - statusBarHeight
        val targetX = if (isLeft) 0f else (screenWidth / 2).toFloat()
        val targetY = statusBarHeight.toFloat()

        updateState {
            copy(
                x = targetX,
                y = targetY,
                width = targetWidth,
                height = targetHeight,
                savedWidth = targetWidth,
                savedHeight = targetHeight,
                isPaused = true
            )
        }

        resizeDisplaySurface()

        delay(FreeformConstants.DELAY_SURFACE_SETTLE_MS)
        surfaceEventsManager.dispatch(SurfaceEvent.OperationCompleted("resize_half"))
        dispatch(WindowEvent.SurfaceSettled)
    }

    private fun snapBubbleToSafeZone() {
        if (currentState.mode != WindowMode.BUBBLE) return

        val slot = freeformWindowManager.getBubbleSlot(windowPackageName)
        val targetY = if (slot != null) {
            freeformWindowManager.getBubbleSlotY(slot)
        } else {
            currentState.y
        }
        moveBubbleToPosition(targetY, animate = false)
    }

    private fun moveBubbleToPosition(targetY: Float, animate: Boolean) {
        if (currentState.mode != WindowMode.BUBBLE) return

        val bubbleSize = currentState.width
        val isLandscape = currentState.isLandscape
        val safeTop = if (isLandscape) context.dpToPx(16) else statusBarHeight
        val safeBottom =
            if (isLandscape) context.dpToPx(16)
            else context.dpToPx(FreeformConstants.SAFE_ZONE_BOTTOM_DP)
        val safeEdge = context.dpToPx(FreeformConstants.SAFE_ZONE_EDGE_DP)

        val minY = safeTop.toFloat()
        val maxY = maxOf(minY, (screenHeight - safeBottom - bubbleSize).toFloat())
        val clampedTargetY = targetY.coerceIn(minY, maxY)
        val centerX = currentState.x + bubbleSize / 2f
        val targetX =
            if (centerX < screenWidth / 2f) {
                safeEdge.toFloat()
            } else {
                (screenWidth - bubbleSize - safeEdge).toFloat()
            }

        cancelBubblePositionAnimation()
        if (!animate) {
            updateState { copy(x = targetX, y = clampedTargetY) }
            return
        }

        val startX = currentState.x
        val startY = currentState.y
        if (kotlin.math.abs(startX - targetX) < 0.5f &&
            kotlin.math.abs(startY - clampedTargetY) < 0.5f) {
            updateState { copy(x = targetX, y = clampedTargetY) }
            return
        }

        bubblePositionAnimationJob =
            scope.launch(AndroidUiDispatcher.Main) {
                val progress = Animatable(0f)
                try {
                    progress.animateTo(
                        targetValue = 1f,
                        animationSpec =
                            spring(
                                dampingRatio = 0.78f,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                    ) {
                        val fraction = value
                        updateState {
                            copy(
                                x = startX + (targetX - startX) * fraction,
                                y = startY + (clampedTargetY - startY) * fraction,
                            )
                        }
                    }
                    updateState { copy(x = targetX, y = clampedTargetY) }
                } catch (_: CancellationException) {
                    // A new drag or reorder owns the position from this point on.
                }
            }
    }

    private fun cancelBubblePositionAnimation() {
        bubblePositionAnimationJob?.cancel()
        bubblePositionAnimationJob = null
    }

    private fun clampWindowToSafeZone() {
        val safeTop = statusBarHeight
        val safeBottom = context.dpToPx(FreeformConstants.SAFE_ZONE_BOTTOM_DP)
        
        val minY = safeTop.toFloat()
        val maxY = maxOf(minY, (screenHeight - safeBottom - 48).toFloat())
        val clampedY = currentState.y.coerceIn(minY, maxY)
        
        val halfWidth = currentState.width / 2f
        val clampedX = currentState.x.coerceIn(-halfWidth, screenWidth - halfWidth)
        
        updateState { copy(x = clampedX, y = clampedY) }
    }
    
    private fun performHapticFeedback() {
        vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
    }
    
    private fun loadAppInfo() {
        scope.launch {
            repository.getAppIcon(windowPackageName)
                .onSuccess { dispatch(WindowEvent.IconLoaded(it)) }
            repository.getAppLabel(windowPackageName)
                .onSuccess { dispatch(WindowEvent.AppNameLoaded(it)) }
        }
    }
    
    fun loadAppIcon() {
        loadAppInfo()
    }
    
    fun onDrag(deltaX: Float, deltaY: Float) {
        if (!eventFlow.tryEmit(WindowEvent.Drag(deltaX, deltaY))) {
            dispatch(WindowEvent.Drag(deltaX, deltaY))
        }
    }
    
    fun onDragEnd() {
        dispatch(WindowEvent.DragEnd)
    }
    
    fun onMinimize() {
        dispatch(WindowEvent.Minimize())
    }
    
    fun onBubbleTap() {
        dispatch(WindowEvent.BubbleTap)
    }
    
    fun onResizeStart() {
        handleResizeStart()
    }
    
    fun onResize(width: Int, height: Int) {
        handleResize(width, height, Float.NaN)
    }

    fun onResizeWithPosition(width: Int, height: Int, x: Float) {
        handleResize(width, height, x)
    }

    fun restoreWindowSize(width: Int, height: Int, isLandscape: Boolean) {
        val (coercedWidth, coercedHeight) = coerceWindowSizeForMode(width, height, isLandscape)
        updateState {
            copy(
                width = coercedWidth,
                height = coercedHeight,
                savedWidth = coercedWidth,
                savedHeight = coercedHeight,
                isLandscape = isLandscape
            )
        }
    }
    
    fun onResizeEnd() {
        dispatch(WindowEvent.ResizeEnd)
    }
    
    fun onMaximize() {
        dispatch(WindowEvent.Maximize)
    }

    fun onResizeToFullscreen() {
        dispatch(WindowEvent.ResizeToFullscreen)
    }

    fun onResizeToHalfLeft() {
        dispatch(WindowEvent.ResizeToHalfLeft)
    }

    fun onResizeToHalfRight() {
        dispatch(WindowEvent.ResizeToHalfRight)
    }

    fun setDisplayId(displayId: Int) {
        dispatch(WindowEvent.DisplayCreated(displayId))
    }
    
    fun onOrientationChanged(isLandscape: Boolean) {
        dispatch(WindowEvent.OrientationChanged(isLandscape))
    }
    
    fun onBubblePositionUpdate(newY: Float) {
        moveBubbleToPosition(newY, animate = true)
    }

    fun onScreenDimensionsChanged(width: Int, height: Int) {
        this.screenWidth = width
        this.screenHeight = height
        Log.d(TAG, "Screen dimensions updated in $windowPackageName: ${width}x${height}")
        scope.launch {
            when (currentState.mode) {
                WindowMode.BUBBLE -> snapBubbleToSafeZone()
                WindowMode.NORMAL, WindowMode.DESKTOP -> {
                    // A host-display rotation must not rewrite the user's configured
                    // freeform size. The subsequent orientation event rotates the
                    // current geometry exactly once; here we only keep the window
                    // reachable inside the new display bounds.
                    clampWindowToSafeZone()
                }
            }
        }
    }
    
    fun isSurfaceReady(): Boolean = currentState.isSurfaceReady
    
    fun markSurfaceReady() {
        dispatch(WindowEvent.SurfaceSettled)
    }
    
    fun destroy() {
        cancelBubblePositionAnimation()
        val displayId = currentState.displayId
        if (displayId >= 0) {
            freeformWindowManager.unregisterDisplayId(displayId)
        }
        updateState { copy(isDestroyed = true) }
    }
}
