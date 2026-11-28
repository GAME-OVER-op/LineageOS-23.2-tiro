/*
 * Copyright (C) 2026 The LineageOS Project
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

package com.android.settings.display;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.provider.Settings.Secure;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/** Per-user Settings.Secure storage shared with the frame generation service. */
final class FrameGenerationStore {
    static final String ENABLED = "frame_generation_enabled";
    static final String DEFAULT_MODE = "frame_generation_default";
    static final String MAX_FPS = "frame_generation_max_fps";
    static final String PAUSE_IN_BATTERY_SAVER = "frame_generation_pause_in_battery_saver";
    static final String APPS = "frame_generation_apps";

    static final int APP_DEFAULT = 0; // In the JSON, represented by the absence of a key.
    static final int APP_OFF = -1;
    static final int APP_VIDEO_ONLY = 1;
    static final int APP_AUTO = 2;

    private static final String TAG = "FrameGenerationStore";

    private final ContentResolver mResolver;
    private final int mUserId;

    FrameGenerationStore(Context context) {
        mResolver = context.getContentResolver();
        mUserId = context.getUserId();
    }

    int getInt(String key, int fallback) {
        try {
            return Secure.getIntForUser(mResolver, key, fallback, mUserId);
        } catch (SecurityException e) {
            Log.w(TAG, "Unable to read frame generation setting", e);
            return fallback;
        }
    }

    boolean putInt(String key, int value) {
        try {
            return Secure.putIntForUser(mResolver, key, value, mUserId);
        } catch (SecurityException e) {
            Log.w(TAG, "Unable to write frame generation setting", e);
            return false;
        }
    }

    JSONObject getAppModes() {
        try {
            return readAppModes();
        } catch (SecurityException e) {
            Log.w(TAG, "Unable to read frame generation app modes", e);
            return new JSONObject();
        }
    }

    boolean putAppMode(String packageName, int mode) {
        if (packageName == null || packageName.isEmpty() || !isAppMode(mode)) {
            return false;
        }
        try {
            // Re-read to avoid writing a stale snapshot of the other packages' overrides.
            final JSONObject appModes = readAppModes();
            return Secure.putStringForUser(mResolver, APPS,
                    updateAppModes(appModes, packageName, mode), mUserId);
        } catch (JSONException | SecurityException e) {
            Log.w(TAG, "Unable to write frame generation app mode", e);
            return false;
        }
    }

    void registerObserver(ContentObserver observer, String... keys) {
        for (String key : keys) {
            mResolver.registerContentObserver(Secure.getUriFor(key), false, observer, mUserId);
        }
    }

    void unregisterObserver(ContentObserver observer) {
        mResolver.unregisterContentObserver(observer);
    }

    private JSONObject readAppModes() {
        return parseAppModes(Secure.getStringForUser(mResolver, APPS, mUserId));
    }

    static JSONObject parseAppModes(String value) {
        if (value != null) {
            try {
                return new JSONObject(value);
            } catch (JSONException e) {
                // A corrupt setting is treated as a configuration with no overrides.
            }
        }
        return new JSONObject();
    }

    static int getAppMode(JSONObject appModes, String packageName) {
        final Object value = appModes.opt(packageName);
        return value instanceof Integer mode && isAppMode(mode) ? mode : APP_DEFAULT;
    }

    static String updateAppModes(JSONObject appModes, String packageName, int mode)
            throws JSONException {
        if (mode == APP_DEFAULT) {
            appModes.remove(packageName);
        } else {
            appModes.put(packageName, mode);
        }
        return appModes.toString();
    }

    private static boolean isAppMode(int mode) {
        return mode == APP_DEFAULT || mode == APP_OFF || mode == APP_VIDEO_ONLY || mode == APP_AUTO;
    }
}
