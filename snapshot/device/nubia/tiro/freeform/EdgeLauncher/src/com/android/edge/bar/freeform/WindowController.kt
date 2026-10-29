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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import androidx.compose.ui.platform.ComposeView

class WindowController(
    private val context: Context,
    private val packageName: String,
    private val targetDisplayId: Int = Display.DEFAULT_DISPLAY,
) {
    companion object {
        private const val TAG = "WindowController"
        private const val SHOW_DURATION_MS = 420L
        private const val HIDE_DURATION_MS = 320L
        private const val WINDOW_SCALE_FROM = 0.92f
        private const val WINDOW_SCALE_TO = 0.92f

        private val SHOW_INTERPOLATOR = PathInterpolator(0.20f, 0.94f, 0.05f, 1f)
        private val HIDE_INTERPOLATOR = PathInterpolator(0.40f, 0f, 0f, 1f)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager = resolveWindowManager()
    
    val windowParams = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        format = PixelFormat.TRANSLUCENT
        gravity = Gravity.TOP or Gravity.START
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        privateFlags = WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS or
                WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        title = "FreeformWindow_$packageName"
        preferredRefreshRate = resolvePreferredRefreshRate()
    }
    var composeView: ComposeView? = null
        private set
    private var pendingX = 0
    private var pendingY = 0
    private var pendingWidth = 0
    private var pendingHeight = 0
    private var hasPendingLayout = false
    private var layoutUpdateScheduled = false

    private fun resolvePreferredRefreshRate(): Float {
        return try {
            val dm = context.getSystemService(DisplayManager::class.java)
            val displayId = if (
                targetDisplayId != Display.INVALID_DISPLAY
            ) {
                targetDisplayId
            } else {
                Display.DEFAULT_DISPLAY
            }

            val display = dm?.getDisplay(displayId)
                ?: dm?.getDisplay(Display.DEFAULT_DISPLAY)

            display?.supportedModes
                ?.maxByOrNull { it.refreshRate }
                ?.refreshRate
                ?: display?.refreshRate
                ?: 60f
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve preferred refresh rate", e)
            60f
        }
    }

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            handler.post { block() }
        }
    }

    fun initWindowParams(width: Int, height: Int, x: Int, y: Int) = runOnMain {
        windowParams.apply {
            this.width = width
            this.height = height
            this.x = x
            this.y = y
        }
    }

    fun createWindow(view: ComposeView, onResult: ((Boolean) -> Unit)? = null) = runOnMain {
        composeView = view
        try {
            windowManager.addView(view, windowParams)
            scaleInInternal()
            onResult?.invoke(true)
        } catch (e: Exception) {
            Log.e(TAG, "addView failed", e)
            onResult?.invoke(false)
        }
    }

    fun bringToFront() = runOnMain {
        // Stage 1 intentionally uses only the stock WindowManager API. Re-applying the same
        // layout is safe and keeps this method callable without the old framework z-order
        // extension. Explicit cross-overlay z-order will be revisited after landscape/Recents
        // behavior is proven stable.
        try {
            composeView?.let { view ->
                if (view.isAttachedToWindow) {
                    windowManager.updateViewLayout(view, windowParams)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "bringToFront refresh failed", e)
        }
    }

    fun bringToBack() = runOnMain {
        // No stock WindowManager API can safely push one TYPE_APPLICATION_OVERLAY behind its
        // siblings. Do not patch IWindowSession/Session for Stage 1; Overview must remain fully
        // stock.
        Log.d(TAG, "bringToBack ignored in clean Stage 1")
    }

    fun updateLayout(x: Int, y: Int, width: Int, height: Int) = runOnMain {
        pendingX = x
        pendingY = y
        pendingWidth = width
        pendingHeight = height
        hasPendingLayout = true

        if (!layoutUpdateScheduled) {
            layoutUpdateScheduled = true
            val view = composeView
            if (view?.isAttachedToWindow == true) {
                view.postOnAnimation { applyPendingLayout() }
            } else {
                applyPendingLayout()
            }
        }
    }

    private fun applyPendingLayout() {
        layoutUpdateScheduled = false
        if (!hasPendingLayout) return
        hasPendingLayout = false
        windowParams.x = pendingX
        windowParams.y = pendingY
        windowParams.width = pendingWidth
        windowParams.height = pendingHeight
        try {
            composeView?.let { view ->
                if (view.isAttachedToWindow) {
                    windowManager.updateViewLayout(view, windowParams)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateViewLayout failed", e)
        }
    }

    private fun scaleInInternal() {
        composeView?.let { view ->
            view.animate().cancel()
            view.scaleX = WINDOW_SCALE_FROM
            view.scaleY = WINDOW_SCALE_FROM
            view.alpha = 0f

            val scaleX = ObjectAnimator.ofFloat(view, View.SCALE_X, WINDOW_SCALE_FROM, 1f)
            val scaleY = ObjectAnimator.ofFloat(view, View.SCALE_Y, WINDOW_SCALE_FROM, 1f)
            val alpha = ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f)

            AnimatorSet().apply {
                playTogether(scaleX, scaleY, alpha)
                duration = SHOW_DURATION_MS
                interpolator = SHOW_INTERPOLATOR
                start()
            }
        }
    }

    fun scaleOut(onComplete: () -> Unit) = runOnMain {
        composeView?.let { view ->
            view.animate().cancel()
            val scaleX = ObjectAnimator.ofFloat(view, View.SCALE_X, view.scaleX, WINDOW_SCALE_TO)
            val scaleY = ObjectAnimator.ofFloat(view, View.SCALE_Y, view.scaleY, WINDOW_SCALE_TO)
            val alpha = ObjectAnimator.ofFloat(view, View.ALPHA, view.alpha, 0f)

            AnimatorSet().apply {
                playTogether(scaleX, scaleY, alpha)
                duration = HIDE_DURATION_MS
                interpolator = HIDE_INTERPOLATOR
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        onComplete()
                    }
                })
                start()
            }
        } ?: onComplete()
    }

    fun requestFocus() = runOnMain {
        composeView?.requestFocus()
    }

    fun onOutsideTouch() = runOnMain {
    }

    fun destroy(reason: String, onDestroyed: () -> Unit) = runOnMain {
        Log.i(TAG, "destroy: $reason")
        composeView?.let { view ->
            if (view.isAttachedToWindow) {
                scaleOut {
                    removeWindowInternal()
                    onDestroyed()
                }
            } else {
                onDestroyed()
            }
        } ?: onDestroyed()
    }

    private fun removeWindowInternal() {
        try {
            composeView?.let { view ->
                if (view.isAttachedToWindow) {
                    windowManager.removeViewImmediate(view)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "removeView failed", e)
        }
        composeView = null
    }

    fun removeWindow() = runOnMain {
        removeWindowInternal()
    }

    private fun resolveWindowManager(): WindowManager {
        if (targetDisplayId != Display.DEFAULT_DISPLAY
            && targetDisplayId != Display.INVALID_DISPLAY) {
            val dm = context.getSystemService(DisplayManager::class.java)
            val display = dm?.getDisplay(targetDisplayId)
            if (display != null) {
                val displayContext = context.createWindowContext(
                    display,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    null)
                return displayContext.getSystemService(WindowManager::class.java)
            }
        }
        return context.getSystemService(WindowManager::class.java)
    }
}
