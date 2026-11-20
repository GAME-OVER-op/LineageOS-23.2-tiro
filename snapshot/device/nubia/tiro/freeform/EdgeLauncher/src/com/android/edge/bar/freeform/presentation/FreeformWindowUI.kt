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

import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn as composeScaleIn
import androidx.compose.animation.scaleOut as composeScaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.edge.bar.freeform.InputInjector
import com.android.edge.bar.freeform.FreeformWindowManager
import com.android.edge.bar.freeform.presentation.components.*
import com.android.edge.bar.freeform.domain.FreeformConstants.MIN_WINDOW_WIDTH_DP
import com.android.edge.bar.freeform.domain.FreeformConstants.MIN_WINDOW_HEIGHT_DP
import com.android.edge.bar.freeform.domain.FreeformConstants.BUBBLE_SIZE_DP
import com.android.edge.bar.freeform.domain.FreeformConstants.DESKTOP_MIN_WIDTH_DP
import com.android.edge.bar.freeform.domain.FreeformConstants.DESKTOP_MIN_HEIGHT_DP
import com.android.edge.bar.freeform.domain.FreeformConstants.DESKTOP_CORNER_RADIUS_DP
import com.android.edge.bar.freeform.domain.FreeformConstants.DESKTOP_TASKBAR_HEIGHT_DP
import com.android.edge.bar.freeform.domain.FreeformConstants.WINDOW_SCREEN_MARGIN_DP
import com.android.edge.bar.freeform.domain.coerceFreeformWindowSize
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.roundToInt

@Composable
fun FreeformWindowContent(
    stateManager: FreeformStateManager,
    stateFlow: StateFlow<WindowState>,
    freeformWindowManager: FreeformWindowManager,
    density: Float,
    scope: CoroutineScope,
    onCloseAndKill: () -> Unit,
    onBack: () -> Unit,
    onBringToFront: () -> Unit,
    onMaximizeFullscreen: () -> Unit,
    onUpdateWindowLayout: (Float, Float, Int, Int) -> Unit,
    onSetupTextureView: (TextureView) -> Unit,
    textureViewListener: TextureView.SurfaceTextureListener,
    inputInjector: InputInjector
) {
    val state by stateFlow.collectAsState()
    
    if (state.isDestroyed) {
        return
    }
    
    val isBubbleMode = state.mode == WindowMode.BUBBLE

    fun onSetDragging(dragging: Boolean) {
    }

    // Keep the window mounted while it is minimized to a bubble. In
    // particular the TextureView that hosts the virtual display must NOT be
    // removed from composition: doing so destroys its surface, forces the
    // creation of a new freeform display on expand and moves the app task to
    // it, which recreates the Activity and loses in-memory state (playback).
    // Fade it out visually instead — the display and the task stay alive.
    val contentAlpha by animateFloatAsState(
        targetValue = if (isBubbleMode) 0f else 1f,
        animationSpec = tween(if (isBubbleMode) 220 else 300),
        label = "freeform-content-alpha"
    )
    Box(modifier = Modifier.fillMaxSize().alpha(contentAlpha)) {
        FullWindowView(
            state = state,
            stateManager = stateManager,
            scope = scope,
            onClose = onCloseAndKill,
            onBack = onBack,
            onMaximizeFullscreen = onMaximizeFullscreen,
            onBringToFront = onBringToFront,
            onSetDragging = ::onSetDragging,
            onSetupTextureView = onSetupTextureView,
            textureViewListener = textureViewListener,
            inputInjector = inputInjector
        )
    }
    
    BubbleModeView(
        state = state,
        stateManager = stateManager,
        freeformWindowManager = freeformWindowManager,
        density = density,
        visible = isBubbleMode
    )
    
    LaunchedEffect(state.x, state.y, state.width, state.height) {
        onUpdateWindowLayout(state.x, state.y, state.width, state.height)
    }
}

@Composable
private fun BubbleModeView(
    state: WindowState,
    stateManager: FreeformStateManager,
    freeformWindowManager: FreeformWindowManager,
    density: Float,
    visible: Boolean
) {
    var isBubbleDragging by remember { mutableStateOf(false) }
    var suppressBubbleTapUntil by remember { mutableLongStateOf(0L) }
    val bubbleSizePx = BUBBLE_SIZE_DP * density

    fun getBubbleCenterY() = state.y.toFloat() + bubbleSizePx / 2
    
    LaunchedEffect(state.y, isBubbleDragging) {
        if (isBubbleDragging) {
            val currentSlot = freeformWindowManager.getBubbleSlot(stateManager.packageName) ?: 0
            val targetSlot = freeformWindowManager.getBubbleSlotForY(getBubbleCenterY())

            if (targetSlot != currentSlot) {
                freeformWindowManager.reorderBubbleSlot(stateManager.packageName, targetSlot)
            }
        }
    }
    
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + composeScaleIn(),
        exit = fadeOut() + composeScaleOut()
    ) {
        BubbleView(
            appIcon = state.appIcon,
            onClick = {
                val now = SystemClock.uptimeMillis()
                if (!isBubbleDragging && now >= suppressBubbleTapUntil) {
                    stateManager.onBubbleTap()
                } else {
                    Log.d(
                        "BubbleModeView",
                        "Ignoring bubble tap after drag for ${stateManager.packageName}",
                    )
                }
            },
            onDragStart = {
                suppressBubbleTapUntil = Long.MAX_VALUE
                isBubbleDragging = true
            },
            onDrag = { deltaX, deltaY ->
                stateManager.onDrag(deltaX, deltaY)
            },
            onDragEnd = {
                isBubbleDragging = false
                suppressBubbleTapUntil = SystemClock.uptimeMillis() + 350L
                stateManager.onDragEnd()
            }
        )
    }
}

@Composable
private fun FullWindowView(
    state: WindowState,
    stateManager: FreeformStateManager,
    scope: CoroutineScope,
    onClose: () -> Unit,
    onBack: () -> Unit,
    onMaximizeFullscreen: () -> Unit,
    onBringToFront: () -> Unit,
    onSetDragging: (Boolean) -> Unit,
    onSetupTextureView: (TextureView) -> Unit,
    textureViewListener: TextureView.SurfaceTextureListener,
    inputInjector: InputInjector
) {
    val density = LocalDensity.current
    val isDesktopMode = state.mode == WindowMode.DESKTOP
    val decorCornerRadius = DESKTOP_CORNER_RADIUS_DP.dp
    val decorMetrics = FreeformDecorMetricsProvider.provide(state.width, density)

    var isResizing by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var hideHandlesJob by remember { mutableStateOf<Job?>(null) }
    
    val showResizeBorder = isResizing || isDragging
    
    fun onResizeStarted() {
        hideHandlesJob?.cancel()
        hideHandlesJob = null
        isResizing = true
    }
    
    fun onResizeEnded() {
        hideHandlesJob?.cancel()
        hideHandlesJob = scope.launch {
            delay(1500)
            isResizing = false
        }
    }
    
    val resizeBorderColor = MaterialTheme.colorScheme.primary

    var isWindowMenuExpanded by remember { mutableStateOf(false) }
    var dropdownOffsetX by remember { mutableFloatStateOf(0f) }
    val onMinimizeAction: (() -> Unit)? = if (isDesktopMode) null else ({ stateManager.onMinimize() })
    val dropdownStart = with(density) {
        val requestedStart = dropdownOffsetX.toDp()
        val maxStart = (state.width.toDp() - decorMetrics.menuWidth - decorMetrics.sidePadding)
            .coerceAtLeast(decorMetrics.sidePadding)
        requestedStart.coerceIn(decorMetrics.sidePadding, maxStart)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            DesktopTitleBar(
                onClose = onClose,
                onBack = null,
                onMinimize = onMinimizeAction,
                onMaximizeFullscreen = null,
                onDrag = { deltaX, deltaY ->
                    stateManager.onDrag(deltaX, deltaY)
                },
                onDragStart = {
                    onBringToFront()
                    onSetDragging(true)
                },
                onDragEnd = {
                    stateManager.onDragEnd()
                    onSetDragging(false)
                },
                isMenuExpanded = isWindowMenuExpanded,
                onMenuExpandedChange = { isWindowMenuExpanded = it },
                showWindowMenu = true,
                appIcon = state.appIcon,
                appName = state.appName,
                onDropdownOffsetChanged = { dropdownOffsetX = it },
                modifier = Modifier.padding(horizontal = decorMetrics.sidePadding)
            )

            Box(modifier = Modifier.weight(1f)) {
                WindowSurface(
                    state = state,
                    decorMetrics = decorMetrics,
                    onSetupTextureView = { view ->
                        onSetupTextureView(view)
                    },
                    textureViewListener = textureViewListener,
                    inputInjector = inputInjector
                )

                ResizeHandles(
                    stateManager = stateManager,
                    density = density,
                    decorMetrics = decorMetrics,
                    cornerRadius = decorCornerRadius,
                    onResizeStarted = { onResizeStarted() },
                    onResizeEnded = { onResizeEnded() },
                    onBack = onBack,
                    isDesktopMode = isDesktopMode
                )
            }
        }

        AnimatedVisibility(
            visible = showResizeBorder,
            enter = fadeIn(animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(300))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = decorMetrics.sidePadding,
                        end = decorMetrics.sidePadding
                    )
                    .border(
                        width = decorMetrics.resizeBorderWidth,
                        color = resizeBorderColor,
                        shape = RoundedCornerShape(decorCornerRadius)
                    )
            )
        }

        AnimatedVisibility(
            visible = isWindowMenuExpanded,
            enter = fadeIn(animationSpec = tween(150)) + expandVertically(
                animationSpec = tween(200),
                expandFrom = Alignment.Top
            ),
            exit = fadeOut(animationSpec = tween(150)) + shrinkVertically(
                animationSpec = tween(200),
                shrinkTowards = Alignment.Top
            ),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(
                    start = dropdownStart,
                    top = decorMetrics.titleBarHeight
                )
        ) {
            DesktopMenuDropdown(
                onResizeToFullscreen = {
                    stateManager.onResizeToFullscreen()
                    isWindowMenuExpanded = false
                },
                onResizeToHalfLeft = {
                    stateManager.onResizeToHalfLeft()
                    isWindowMenuExpanded = false
                },
                onResizeToHalfRight = {
                    stateManager.onResizeToHalfRight()
                    isWindowMenuExpanded = false
                },
                onMaximizeFullscreen = {
                    onMaximizeFullscreen()
                    isWindowMenuExpanded = false
                },
                showDesktopResizeOptions = isDesktopMode,
                menuWidth = decorMetrics.menuWidth
            )
        }
    }
}

@Composable
private fun BoxScope.WindowSurface(
    state: WindowState,
    decorMetrics: FreeformDecorMetrics,
    onSetupTextureView: (TextureView) -> Unit,
    textureViewListener: TextureView.SurfaceTextureListener,
    inputInjector: InputInjector
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = decorMetrics.sidePadding,
                end = decorMetrics.sidePadding,
                bottom = decorMetrics.bottomPadding
            ),
        color = MaterialTheme.colorScheme.surface
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Scroll) {
                                    event.changes.forEach { change ->
                                        val scrollDelta = change.scrollDelta
                                        if (scrollDelta.x != 0f || scrollDelta.y != 0f) {
                                            inputInjector.injectScrollEvent(
                                                change.position.x,
                                                change.position.y,
                                                scrollDelta.x,
                                                scrollDelta.y
                                            )
                                            change.consume()
                                        }
                                    }
                                }
                            }
                        }
                    }
            ) {
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).also {
                            it.isOpaque = false
                            it.surfaceTextureListener = textureViewListener
                            onSetupTextureView(it)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                val showVeil = state.isPaused || !state.isSurfaceReady || state.isAppLaunching
                AnimatedVisibility(
                    visible = showVeil,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            state.appIcon?.let { icon ->
                                Image(
                                    bitmap = icon.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.ResizeHandles(
    stateManager: FreeformStateManager,
    density: Density,
    decorMetrics: FreeformDecorMetrics,
    cornerRadius: Dp,
    onResizeStarted: () -> Unit,
    onResizeEnded: () -> Unit,
    onBack: () -> Unit,
    isDesktopMode: Boolean = false
) {
    val context = LocalContext.current
    val displayMetrics = context.resources.displayMetrics

    val s = stateManager.state.value

    val baseMinWidthPx = with(density) {
        if (isDesktopMode) DESKTOP_MIN_WIDTH_DP.dp.toPx().toInt()
        else MIN_WINDOW_WIDTH_DP.dp.toPx().toInt()
    }
    val baseMinHeightPx = with(density) {
        if (isDesktopMode) DESKTOP_MIN_HEIGHT_DP.dp.toPx().toInt()
        else MIN_WINDOW_HEIGHT_DP.dp.toPx().toInt()
    }

    val minWidthPx = if (s.isLandscape) baseMinHeightPx else baseMinWidthPx
    val minHeightPx = if (s.isLandscape) baseMinWidthPx else baseMinHeightPx

    val screenMarginPx = with(density) { WINDOW_SCREEN_MARGIN_DP.dp.toPx().roundToInt() }

    val maxWidthPx = if (isDesktopMode) {
        displayMetrics.widthPixels.coerceAtLeast(minWidthPx)
    } else {
        (displayMetrics.widthPixels - screenMarginPx * 2).coerceAtLeast(minWidthPx)
    }
    val maxHeightPx = if (isDesktopMode) {
        val taskbarPx = with(density) { DESKTOP_TASKBAR_HEIGHT_DP.dp.toPx().toInt() }
        (displayMetrics.heightPixels - taskbarPx).coerceAtLeast(minHeightPx)
    } else {
        (displayMetrics.heightPixels - screenMarginPx).coerceAtLeast(minHeightPx)
    }

    fun coerceResizeSize(width: Int, height: Int): Pair<Int, Int> {
        return if (isDesktopMode) {
            width.coerceIn(minWidthPx, maxWidthPx) to height.coerceIn(minHeightPx, maxHeightPx)
        } else {
            coerceFreeformWindowSize(width, height, minWidthPx, minHeightPx, maxWidthPx, maxHeightPx)
        }
    }

    var initialWidth by remember { mutableIntStateOf(0) }
    var initialHeight by remember { mutableIntStateOf(0) }
    var initialX by remember { mutableFloatStateOf(0f) }

    fun startResize() {
        val snap = stateManager.state.value
        initialWidth = snap.width
        initialHeight = snap.height
        initialX = snap.x
        onResizeStarted()
        stateManager.onResizeStart()
    }

    fun endResize() {
        onResizeEnded()
        stateManager.onResizeEnd()
    }

    BottomResizeBar(
        onRightResize = { totalDeltaX, totalDeltaY ->
            val (newWidth, newHeight) = coerceResizeSize(
                initialWidth + totalDeltaX.roundToInt(),
                initialHeight + totalDeltaY.roundToInt()
            )
            stateManager.onResize(newWidth, newHeight)
        },
        onLeftResize = { totalDeltaX, totalDeltaY ->
            val (newWidth, newHeight) = coerceResizeSize(
                initialWidth - totalDeltaX.roundToInt(),
                initialHeight + totalDeltaY.roundToInt()
            )
            val widthDelta = initialWidth - newWidth
            stateManager.onResizeWithPosition(newWidth, newHeight, initialX + widthDelta)
        },
        onBack = onBack,
        onResizeStart = ::startResize,
        onResizeEnd = ::endResize,
        cornerRadius = cornerRadius,
        barWidth = decorMetrics.bottomBarWidth,
        barHeight = decorMetrics.bottomBarHeight,
        modifier = Modifier.align(Alignment.BottomCenter)
    )
}
