/*
 * SPDX-FileCopyrightText: 2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.lighting

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager

object LightingRuntimeController {
    @Volatile
    private var initialized = false

    private lateinit var appContext: Context

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> LightingRuntimeStore.setScreenState(true)
                Intent.ACTION_SCREEN_OFF -> LightingRuntimeStore.setScreenState(false)
            }
        }
    }

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) {
            LightingController.ensureInitialized()
            syncScreenState()
            return
        }
        appContext = context.applicationContext

        LightingController.ensureInitialized()
        syncScreenState()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        appContext.registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
        initialized = true
    }

    /**
     * Called from NubiaNotificationListener on every newly posted notification.
     *
     * Notification lighting is a short pulse that only makes sense with the screen off.
     * The native nubia_lighting_service owns the actual sysfs write and watches
     * runtime.conf, so there is no polling of the notification service here.
     */
    fun onNotificationPosted() {
        if (!initialized) return
        val powerManager = appContext.getSystemService(PowerManager::class.java)
        if (powerManager.isInteractive) return
        LightingRuntimeStore.setNotificationActive(true)
    }

    fun onListenerDisconnected() {
        if (initialized) LightingRuntimeStore.setNotificationActive(false)
    }

    private fun syncScreenState() {
        val interactive = appContext.getSystemService(PowerManager::class.java).isInteractive
        LightingRuntimeStore.setScreenState(interactive)
    }
}
