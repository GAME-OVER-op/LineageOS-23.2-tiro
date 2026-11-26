/*
 * SPDX-FileCopyrightText: 2025-2026 GAME-OVER-op
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Collections
import org.lineageos.settings.lighting.LightingRuntimeController

class NubiaNotificationListener : NotificationListenerService() {

    // Only a notification key we have not seen before counts as a NEW notification.
    // An update of an existing one (music metadata, progress, etc.) keeps the same
    // key and must not restart the lighting — same semantics as the tested mora
    // logic, which reacts only to a new line in `cmd notification list`.
    private val activeKeys = Collections.synchronizedSet(LinkedHashSet<String>())

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Seed from what is already on screen so notifications that existed before
        // the listener was bound are not treated as new.
        activeKeys.clear()
        activeNotifications.orEmpty()
            .filter(::isEligible)
            .forEach { activeKeys += it.key }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isEligible(sbn)) return
        if (activeKeys.add(sbn.key)) LightingRuntimeController.onNotificationPosted()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Once dismissed, a later post with the same id/tag is a new notification.
        activeKeys.remove(sbn.key)
    }

    override fun onListenerDisconnected() {
        activeKeys.clear()
        LightingRuntimeController.onListenerDisconnected()
        super.onListenerDisconnected()
    }

    private fun isEligible(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        return sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
    }
}
