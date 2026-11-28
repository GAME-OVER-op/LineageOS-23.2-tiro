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

package com.android.server.display;

import android.os.UserHandle;
import android.util.JsonReader;
import android.util.JsonToken;

import com.android.server.pm.pkg.PackageState;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** Translates per-package frame generation settings to the modes understood by SurfaceFlinger. */
final class FrameGenerationPolicy {
    static final int MODE_OFF = -1;
    private static final int MODE_NOT_SELECTED = 0;
    static final int MODE_VIDEO = 1;
    static final int MODE_AUTO = 2;

    private FrameGenerationPolicy() {}

    static boolean isEnabled(int enabled, int pauseInBatterySaver, boolean batterySaver) {
        return enabled == 1 && (pauseInBatterySaver == 0 || !batterySaver);
    }

    static int sanitizeMaxFps(int maxFps) {
        return maxFps == 60 || maxFps == 120 ? maxFps : 0;
    }

    static Map<String, Integer> parseAppModes(String json) throws IOException {
        if (json == null || json.isEmpty()) {
            return Map.of();
        }
        final Map<String, Integer> modes = new HashMap<>();
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw new IOException("Expected object");
            }
            reader.beginObject();
            while (reader.hasNext()) {
                final String name = reader.nextName();
                if (reader.peek() != JsonToken.NUMBER) {
                    reader.skipValue();
                    continue;
                }
                switch (reader.nextString()) {
                    case "-1" -> modes.put(name, MODE_OFF);
                    case "1" -> modes.put(name, MODE_VIDEO);
                    case "2" -> modes.put(name, MODE_AUTO);
                    default -> { }
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("Expected end of document");
            }
        }
        return modes;
    }

    static Map<Integer, Integer> resolveUidOverrides(int userId, boolean autoAll,
            Map<String, Integer> appModes, Iterable<PackageState> packageStates) {
        final int defaultMode = autoAll ? MODE_AUTO : MODE_NOT_SELECTED;
        final Map<Integer, Integer> uidModes = new TreeMap<>();
        for (PackageState pkg : packageStates) {
            final int appId = pkg.getAppId();
            if (!UserHandle.isApp(appId) || !pkg.getUserStateOrDefault(userId).isInstalled()) {
                continue;
            }

            // Privileged components such as SystemUI can have an application-range app ID.
            // Other preinstalled apps require an explicit opt-in rather than inheriting auto.
            final int packageDefault = autoAll && (pkg.isSystem() || pkg.isUpdatedSystemApp())
                    ? MODE_OFF : defaultMode;
            final int mode = pkg.isPrivileged() ? (autoAll ? MODE_OFF : MODE_NOT_SELECTED)
                    : appModes.getOrDefault(pkg.getPackageName(), packageDefault);
            final int uid = UserHandle.getUid(userId, appId);
            // SurfaceFlinger cannot tell packages sharing the same UID apart. Never give
            // one a mode that another could not get on its own.
            uidModes.merge(uid, mode, (previous, next) -> previous.equals(next)
                    ? previous : MODE_OFF);
        }

        uidModes.values().removeIf(mode -> mode == defaultMode);
        return uidModes;
    }
}
