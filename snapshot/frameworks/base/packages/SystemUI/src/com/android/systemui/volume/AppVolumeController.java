/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.volume;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.PlayerProxy;
import android.os.Handler;
import android.os.Process;
import android.provider.Settings;
import android.os.UserHandle;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tracks application playback sessions and provides a persistent per-application volume level.
 *
 * <p>The multiplier itself is applied by AudioService when a player is registered
 * ({@code PlaybackActivityMonitor.trackPlayer}). Applying it at registration means a replacement
 * player created for the next track gets the saved gain before it can play a single frame, so
 * there is no loud->quiet transition and no need for delayed re-apply hacks. The level is stored
 * in {@link Settings.Secure} keyed by uid so system_server can read it synchronously. PlayerProxy
 * is used here only to update players that are already active when the user moves the slider.</p>
 */
public final class AppVolumeController {
    private static final String TAG = "AppVolumeController";
    private static final String PREFS_NAME = "app_volume_levels";
    private static final String PREF_PREFIX = "volume:";
    static final String SETTING_PREFIX = "app_volume_";
    private static final int DEFAULT_VOLUME = 100;

    public interface Listener {
        void onAppVolumesChanged(@NonNull List<AppState> apps);
    }

    public static final class AppState {
        public final String key;
        public final String packageName;
        public final int uid;
        public final int userId;
        public final int volume;

        AppState(String key, String packageName, int uid, int userId, int volume) {
            this.key = key;
            this.packageName = packageName;
            this.uid = uid;
            this.userId = userId;
            this.volume = volume;
        }

        AppState withVolume(int newVolume) {
            return new AppState(key, packageName, uid, userId, newVolume);
        }
    }

    private final Context mContext;
    private final Handler mHandler;
    private final AudioManager mAudioManager;
    private final PackageManager mPackageManager;
    private final ActivityManager mActivityManager;
    private final SharedPreferences mLegacyPrefs;
    private final Listener mListener;

    private final Map<String, List<AudioPlaybackConfiguration>> mActivePlayers =
            new LinkedHashMap<>();
    private final Map<String, AppState> mActiveStates = new LinkedHashMap<>();
    private final Map<Integer, String> mPackageByPid = new HashMap<>();
    private final Map<Integer, String> mKeyByUid = new HashMap<>();

    private boolean mStarted;

    private final AudioManager.AudioPlaybackCallback mPlaybackCallback =
            new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    handlePlaybackConfigurations(configs);
                }
            };

    public AppVolumeController(Context context, Handler handler, Listener listener) {
        mContext = context;
        mHandler = handler;
        mListener = listener;
        mAudioManager = context.getSystemService(AudioManager.class);
        mPackageManager = context.getPackageManager();
        mActivityManager = context.getSystemService(ActivityManager.class);
        final Context storageContext = context.createDeviceProtectedStorageContext();
        mLegacyPrefs = storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void start() {
        if (mStarted || mAudioManager == null) {
            return;
        }
        mStarted = true;
        migrateLegacyLevels();

        try {
            mAudioManager.setMultiAudioFocusEnabled(true);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to enable multi-audio focus", e);
        }

        mAudioManager.registerAudioPlaybackCallback(mPlaybackCallback, mHandler);
        handlePlaybackConfigurations(mAudioManager.getActivePlaybackConfigurations());
    }

    public void stop() {
        if (!mStarted || mAudioManager == null) {
            return;
        }
        mStarted = false;
        mAudioManager.unregisterAudioPlaybackCallback(mPlaybackCallback);
        mActivePlayers.clear();
        mActiveStates.clear();
        mPackageByPid.clear();
        mKeyByUid.clear();
    }

    /** One-time migration from the old device-protected SharedPreferences to Settings.Secure. */
    private void migrateLegacyLevels() {
        final Map<String, ?> all = mLegacyPrefs.getAll();
        if (all.isEmpty()) {
            return;
        }
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            if (!entry.getKey().startsWith(PREF_PREFIX) || !(entry.getValue() instanceof Integer)) {
                continue;
            }
            // Old key format: "volume:<userId>:<packageName>".
            final String rest = entry.getKey().substring(PREF_PREFIX.length());
            final int sep = rest.indexOf(':');
            if (sep <= 0) {
                continue;
            }
            final String packageName = rest.substring(sep + 1);
            try {
                final android.content.pm.ApplicationInfo info =
                        mPackageManager.getApplicationInfoAsUser(
                                packageName, 0, UserHandle.USER_CURRENT);
                Settings.Secure.putIntForUser(mContext.getContentResolver(),
                        SETTING_PREFIX + info.uid, (Integer) entry.getValue(),
                        UserHandle.USER_CURRENT);
            } catch (PackageManager.NameNotFoundException ignored) {
                // App no longer installed; nothing to migrate.
            }
        }
        mLegacyPrefs.edit().clear().apply();
    }

    public void setAppVolume(@NonNull String key, int volume) {
        final int clampedVolume = Math.max(0, Math.min(DEFAULT_VOLUME, volume));
        final AppState oldState = mActiveStates.get(key);
        if (oldState == null) {
            return;
        }
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                SETTING_PREFIX + oldState.uid, clampedVolume, UserHandle.USER_CURRENT);

        final AppState newState = oldState.withVolume(clampedVolume);
        mActiveStates.put(key, newState);
        mKeyByUid.put(newState.uid, key);

        // Players that are already active need the new value now. Future players
        // get it from AudioService at registration time, without any race.
        final List<AudioPlaybackConfiguration> players = mActivePlayers.get(key);
        if (players != null) {
            for (AudioPlaybackConfiguration config : players) {
                applyVolume(config, clampedVolume);
            }
        }
        dispatchSnapshot();
    }

    private void handlePlaybackConfigurations(List<AudioPlaybackConfiguration> configs) {
        if (!mStarted) {
            return;
        }

        final LinkedHashMap<String, List<AudioPlaybackConfiguration>> activePlayers =
                new LinkedHashMap<>();
        final LinkedHashMap<String, AppState> activeStates = new LinkedHashMap<>();
        final Set<Integer> activePids = new HashSet<>();

        if (configs != null) {
            for (AudioPlaybackConfiguration config : configs) {
                final int uid = config.getClientUid();
                if (UserHandle.getAppId(uid) < Process.FIRST_APPLICATION_UID) {
                    continue;
                }

                final String key = keyForConfig(uid, config);
                if (key == null || key.equals(makeKey(UserHandle.getUserId(uid),
                        mContext.getPackageName()))) {
                    continue;
                }

                final int volume = getSavedVolume(uid);
                activePids.add(config.getClientPid());
                activePlayers.computeIfAbsent(key, unused -> new ArrayList<>()).add(config);
                mKeyByUid.put(uid, key);

                if (isEligible(config)) {
                    final String packageName = resolvePackageName(config);
                    activeStates.putIfAbsent(
                            key, new AppState(key, packageName, uid,
                                    UserHandle.getUserId(uid), volume));
                }
            }
        }

        mPackageByPid.keySet().retainAll(activePids);
        mActivePlayers.clear();
        mActivePlayers.putAll(activePlayers);
        mActiveStates.clear();
        mActiveStates.putAll(activeStates);
        dispatchSnapshot();
    }

    private String keyForConfig(int uid, AudioPlaybackConfiguration config) {
        final String known = mKeyByUid.get(uid);
        if (known != null) {
            return known;
        }
        final String packageName = resolvePackageName(config);
        if (packageName == null) {
            return null;
        }
        return makeKey(UserHandle.getUserId(uid), packageName);
    }

    private boolean isEligible(AudioPlaybackConfiguration config) {
        if (config == null
                || config.getPlayerState() != AudioPlaybackConfiguration.PLAYER_STATE_STARTED) {
            return false;
        }
        final int uid = config.getClientUid();
        if (UserHandle.getAppId(uid) < Process.FIRST_APPLICATION_UID) {
            return false;
        }
        final int usage = config.getAudioAttributes().getUsage();
        return usage == AudioAttributes.USAGE_UNKNOWN
                || usage == AudioAttributes.USAGE_MEDIA
                || usage == AudioAttributes.USAGE_GAME
                || usage == AudioAttributes.USAGE_ASSISTANT
                || usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
    }

    private int getSavedVolume(int uid) {
        return Math.max(0, Math.min(DEFAULT_VOLUME,
                Settings.Secure.getIntForUser(mContext.getContentResolver(),
                        SETTING_PREFIX + uid, DEFAULT_VOLUME, UserHandle.USER_CURRENT)));
    }

    private void applyVolume(AudioPlaybackConfiguration config, int volume) {
        final PlayerProxy proxy = config.getPlayerProxy();
        if (proxy == null) {
            return;
        }
        try {
            proxy.setVolume(volumeToGain(volume));
        } catch (RuntimeException e) {
            Log.d(TAG, "Unable to apply app volume to player: "
                    + config.getPlayerInterfaceId(), e);
        }
    }

    private static float volumeToGain(int volume) {
        if (volume <= 0) {
            return 0f;
        }
        if (volume >= DEFAULT_VOLUME) {
            return 1f;
        }
        return (float) Math.pow(volume / (double) DEFAULT_VOLUME, 2.2d);
    }

    private String resolvePackageName(AudioPlaybackConfiguration config) {
        final int uid = config.getClientUid();
        final int pid = config.getClientPid();
        final String cached = mPackageByPid.get(pid);
        if (cached != null) {
            return cached;
        }

        final String[] packages = mPackageManager.getPackagesForUid(uid);
        final String resolved;
        if (packages != null && packages.length == 1) {
            resolved = packages[0];
        } else {
            final String processPackage = resolvePackageFromProcess(pid, packages);
            if (processPackage != null) {
                resolved = processPackage;
            } else if (packages != null && packages.length > 0) {
                resolved = chooseBestPackage(packages);
            } else {
                resolved = resolvePackageFromProcess(pid, null);
            }
        }

        if (resolved != null) {
            mPackageByPid.put(pid, resolved);
        }
        return resolved;
    }

    private String resolvePackageFromProcess(int pid, String[] allowedPackages) {
        if (mActivityManager == null) {
            return null;
        }
        final List<ActivityManager.RunningAppProcessInfo> processes =
                mActivityManager.getRunningAppProcesses();
        if (processes == null) {
            return null;
        }

        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process.pid != pid || process.processName == null) {
                continue;
            }
            final String candidate = process.processName.split(":", 2)[0];
            if (allowedPackages != null) {
                for (String packageName : allowedPackages) {
                    if (candidate.equals(packageName)) {
                        return candidate;
                    }
                }
                return null;
            }
            try {
                mPackageManager.getApplicationInfo(candidate, 0);
                return candidate;
            } catch (PackageManager.NameNotFoundException ignored) {
                return null;
            }
        }
        return null;
    }

    private String chooseBestPackage(String[] packages) {
        String first = null;
        for (String packageName : packages) {
            if (packageName == null) {
                continue;
            }
            if (first == null) {
                first = packageName;
            }
            try {
                final ApplicationInfo info = mPackageManager.getApplicationInfo(packageName, 0);
                if ((info.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    return packageName;
                }
            } catch (PackageManager.NameNotFoundException ignored) {
                // Keep looking for another package attached to this uid.
            }
        }
        return first;
    }

    private void dispatchSnapshot() {
        if (mListener == null) {
            return;
        }
        mListener.onAppVolumesChanged(
                Collections.unmodifiableList(new ArrayList<>(mActiveStates.values())));
    }

    private static String makeKey(int userId, String packageName) {
        return userId + ":" + packageName;
    }
}
