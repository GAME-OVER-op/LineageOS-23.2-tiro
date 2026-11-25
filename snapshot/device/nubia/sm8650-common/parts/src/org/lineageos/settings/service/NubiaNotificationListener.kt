/*
 * SPDX-FileCopyrightText: 2025-2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.lineageos.settings.lighting.LightingRuntimeController

class NubiaNotificationListener : NotificationListenerService() {

    // Event-driven: a newly posted notification starts the lighting pulse. We do not
    // keep a set of "currently active" notifications, because a media notification
    // that stays visible would otherwise hold the external LEDs on forever.
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (isEligible(sbn)) LightingRuntimeController.onNotificationPosted()
    }

    override fun onListenerDisconnected() {
        LightingRuntimeController.onListenerDisconnected()
        super.onListenerDisconnected()
    }

    private fun isEligible(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        return sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
    }
}
