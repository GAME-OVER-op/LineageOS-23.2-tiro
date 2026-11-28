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
import android.util.Log
import com.android.internal.R as InternalR
import com.android.edge.bar.freeform.presentation.WindowMode
import com.android.edge.bar.freeform.FreeformWindowCompose
import com.android.edge.bar.freeform.presentation.FreeformStateManager
import com.android.edge.bar.freeform.domain.FreeformConstants
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

class FreeformWindowManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "FreeformWindowManager"
        
        @Volatile
        private var instance: FreeformWindowManager? = null
        
        fun getInstance(context: Context): FreeformWindowManager {
            return instance ?: synchronized(this) {
                instance ?: FreeformWindowManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val windows = ConcurrentHashMap<String, FreeformWindowCompose>()
    private val displayIdToPackage = ConcurrentHashMap<Int, String>()
    private var focusedWindow: FreeformWindowCompose? = null
    private val listeners = mutableListOf<WindowEventListener>()
    private val bubbleSlots = ConcurrentHashMap<String, Int>()
    
    private var screenWidth: Int = context.resources.displayMetrics.widthPixels
    private var screenHeight: Int = context.resources.displayMetrics.heightPixels
    
    interface WindowEventListener {
        fun onWindowAdded(packageName: String, window: FreeformWindowCompose) {}
        fun onWindowRemoved(packageName: String) {}
        fun onWindowFocusChanged(packageName: String?, hasFocus: Boolean) {}
        fun onWindowOrientationChanged(packageName: String, isLandscape: Boolean) {}
    }

    fun registerWindow(packageName: String, window: FreeformWindowCompose) {
        windows[packageName] = window
        Log.d(TAG, "Registered window for $packageName. Total windows: ${windows.size}")
        
        listeners.forEach { it.onWindowAdded(packageName, window) }
    }

    fun unregisterWindow(packageName: String, window: FreeformWindowCompose) {
        // An old closing animation may finish after a replacement was registered.
        if (!windows.remove(packageName, window)) return
        if (focusedWindow == window) {
            focusedWindow = null
        }
        releaseBubbleSlot(packageName)
        Log.d(TAG, "Unregistered window for $packageName. Remaining windows: ${windows.size}")
        listeners.forEach { listener -> listener.onWindowRemoved(packageName) }
    }

    fun getWindow(packageName: String): FreeformWindowCompose? {
        return windows[packageName]
    }

    fun getAllWindows(): Map<String, FreeformWindowCompose> {
        return windows.toMap()
    }

    fun getWindowCount(): Int {
        return windows.size
    }

    fun hasWindow(packageName: String): Boolean {
        return windows.containsKey(packageName)
    }

    fun setFocusedWindow(packageName: String?) {
        val newFocusedWindow = packageName?.let { windows[it] }
        
        if (focusedWindow != newFocusedWindow) {
            focusedWindow = newFocusedWindow
            
            listeners.forEach { 
                it.onWindowFocusChanged(packageName, newFocusedWindow != null) 
            }
            
            Log.d(TAG, "Focus changed to: $packageName")
        }
    }

    fun getFocusedWindow(): FreeformWindowCompose? {
        return focusedWindow
    }

    fun bringToFront(packageName: String) {
        windows[packageName]?.let { window ->
            window.bringToFront()
            setFocusedWindow(packageName)
        }
    }

    fun bringAllWindowsToBack() {
        windows.values.forEach { window ->
            window.bringToBack()
        }
    }
    
    fun onWindowLostFocus(packageName: String) {
        if (focusedWindow == windows[packageName]) {
            focusedWindow = null
            listeners.forEach { it.onWindowFocusChanged(null, false) }
            Log.d(TAG, "Window lost focus: $packageName")
        }
    }

    fun closeWindow(packageName: String) {
        windows[packageName]?.let { window ->
            window.destroy("Closed by window manager")
            unregisterWindow(packageName, window)
        }
    }

    fun closeAllWindows() {
        val packagesToClose = windows.keys.toList()
        packagesToClose.forEach { closeWindow(it) }
    }

    fun addListener(listener: WindowEventListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: WindowEventListener) {
        listeners.remove(listener)
    }

    fun getWindowPackages(): List<String> {
        return windows.keys.toList()
    }
    
    fun getNextBubbleSlot(packageName: String): Int {
        bubbleSlots[packageName]?.let { return it }

        normalizeBubbleSlots()
        val nextSlot = bubbleSlots.size
        bubbleSlots[packageName] = nextSlot
        Log.d(TAG, "Assigned bubble slot $nextSlot to $packageName")
        return nextSlot
    }

    fun releaseBubbleSlot(packageName: String) {
        val released = bubbleSlots.remove(packageName) ?: return
        Log.d(TAG, "Released bubble slot $released from $packageName")
        normalizeBubbleSlots()
        updateBubblePositionsAfterReorder()
    }

    fun getBubbleSlot(packageName: String): Int? {
        return bubbleSlots[packageName]
    }

    fun getBubbleSlotForY(centerY: Float): Int {
        if (bubbleSlots.isEmpty()) return 0

        val bubbleSizePx = context.dpToPx(FreeformConstants.BUBBLE_SIZE_DP.toFloat())
        val step = bubbleSizePx + context.dpToPx(8f)
        val top = context.resources.getDimensionPixelSize(InternalR.dimen.status_bar_height).toFloat()
        val firstCenter = top + bubbleSizePx / 2f
        return ((centerY - firstCenter) / step)
            .roundToInt()
            .coerceIn(0, bubbleSlots.size - 1)
    }

    fun getBubbleSlotY(slot: Int): Float {
        val bubbleSizePx = context.dpToPx(FreeformConstants.BUBBLE_SIZE_DP.toFloat())
        val top = context.resources.getDimensionPixelSize(InternalR.dimen.status_bar_height).toFloat()
        val spacing = context.dpToPx(8f)
        return top + slot.coerceAtLeast(0) * (bubbleSizePx + spacing)
    }

    /**
     * Inserts [packageName] at [newSlot] and shifts the intervening bubbles as one ordered stack.
     * This is intentionally not a swap: while dragging through a column every neighbour moves out
     * of the way, which gives the same stable insertion behaviour as a reorderable list.
     */
    fun reorderBubbleSlot(packageName: String, newSlot: Int): Boolean {
        val currentSlot = bubbleSlots[packageName] ?: return false
        val targetSlot = newSlot.coerceIn(0, (bubbleSlots.size - 1).coerceAtLeast(0))
        if (targetSlot == currentSlot) return false

        if (targetSlot < currentSlot) {
            bubbleSlots.entries
                .filter { it.key != packageName && it.value in targetSlot until currentSlot }
                .forEach { bubbleSlots[it.key] = it.value + 1 }
        } else {
            bubbleSlots.entries
                .filter { it.key != packageName && it.value in (currentSlot + 1)..targetSlot }
                .forEach { bubbleSlots[it.key] = it.value - 1 }
        }
        bubbleSlots[packageName] = targetSlot

        Log.d(TAG, "Inserted $packageName from slot $currentSlot at $targetSlot")
        updateBubblePositionsAfterReorder(packageName)
        return true
    }

    fun getBubblePackagesSortedBySlot(): List<String> {
        return bubbleSlots.entries
            .sortedBy { it.value }
            .map { it.key }
    }

    fun finishBubbleDrag(packageName: String) {
        val slot = bubbleSlots[packageName] ?: getNextBubbleSlot(packageName)
        windows[packageName]
            ?.stateManager
            ?.takeIf { it.state.value.mode == WindowMode.BUBBLE }
            ?.onBubblePositionUpdate(getBubbleSlotY(slot))
    }

    fun updateBubblePositionsAfterReorder(draggingPackageName: String? = null) {
        bubbleSlots.entries
            .sortedBy { it.value }
            .forEach { (packageName, slot) ->
                if (packageName == draggingPackageName) return@forEach

                val window = windows[packageName]
                if (window?.stateManager?.state?.value?.mode == WindowMode.BUBBLE) {
                    window.stateManager.onBubblePositionUpdate(getBubbleSlotY(slot))
                }
            }
    }

    private fun normalizeBubbleSlots() {
        bubbleSlots.entries
            .sortedWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .forEachIndexed { index, entry ->
                if (entry.value != index) {
                    bubbleSlots[entry.key] = index
                }
            }
    }

    fun registerDisplayId(displayId: Int, packageName: String) {
        displayIdToPackage[displayId] = packageName
        Log.d(TAG, "Registered displayId $displayId for $packageName")
    }

    fun unregisterDisplayId(displayId: Int) {
        displayIdToPackage.remove(displayId)?.let {
            Log.d(TAG, "Unregistered displayId $displayId (was $it)")
        }
    }

    fun getPackageForDisplayId(displayId: Int): String? {
        return displayIdToPackage[displayId]
    }

    fun notifyOrientationChanged(displayId: Int, isLandscape: Boolean) {
        val packageName = displayIdToPackage[displayId] ?: return
        Log.d(TAG, "Orientation changed for $packageName: isLandscape=$isLandscape")
        listeners.forEach { it.onWindowOrientationChanged(packageName, isLandscape) }
    }

    fun notifyOrientationChangedConfig(isLandscape: Boolean) {
        Log.d(TAG, "Global orientation changed: isLandscape=$isLandscape. Notifying ${windows.size} windows.")
        windows.keys.forEach { packageName ->
            listeners.forEach { it.onWindowOrientationChanged(packageName, isLandscape) }
        }
    }

    fun updateScreenDimensions(width: Int, height: Int) {
        if (screenWidth == width && screenHeight == height) return
        
        Log.d(TAG, "Updating screen dimensions: ${width}x${height}")
        screenWidth = width
        screenHeight = height

        windows.values.forEach { window ->
            window.stateManager.onScreenDimensionsChanged(width, height)
        }
    }

    fun getScreenWidth() = screenWidth
    fun getScreenHeight() = screenHeight
}
