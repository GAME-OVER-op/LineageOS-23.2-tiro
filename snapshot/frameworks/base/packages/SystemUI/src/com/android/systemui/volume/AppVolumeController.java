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
 * Tracks application playback sessions and applies a persistent per-application volume multiplier.
 *
 * <p>The multiplier is applied through {@link PlayerProxy#setVolume(float)}. PlayerBase treats that
 * value as an independent multiplier, so applications can continue changing their own player
 * volume without overwriting the SystemUI app-volume value.</p>
 */
public final class AppVolumeController {
    private static final String TAG = "AppVolumeController";
    private static final String PREFS_NAME = "app_volume_levels";
    private static final String PREF_PREFIX = "volume:";
    private static final int DEFAULT_VOLUME = 100;
    // Perceptual gain curve. A linear amplitude mapping makes the upper half of the slider
    // sound almost flat, especially with dynamics-heavy effects such as DTS. 2.2 keeps 100%
    // bit-for-bit at unity while giving the middle/lower range useful audible separation.
    private static final double GAIN_CURVE_EXPONENT = 2.2d;

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
    private final SharedPreferences mPreferences;
    private final Listener mListener;

    private final Map<String, List<AudioPlaybackConfiguration>> mActivePlayers =
            new LinkedHashMap<>();
    private final Map<String, AppState> mActiveStates = new LinkedHashMap<>();
    private final Map<Integer, Integer> mAppliedVolumeByPlayerId = new HashMap<>();
    private final Map<Integer, String> mPackageByPid = new HashMap<>();

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
        mPreferences = storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void start() {
        if (mStarted || mAudioManager == null) {
            return;
        }
        mStarted = true;

        // Use the platform API instead of shell/settings commands. AudioService persists this in
        // Settings.System and ignores the request when multi-audio focus is already enabled.
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
        mAppliedVolumeByPlayerId.clear();
        mPackageByPid.clear();
    }

    public void setAppVolume(@NonNull String key, int volume) {
        final int clampedVolume = Math.max(0, Math.min(DEFAULT_VOLUME, volume));
        if (clampedVolume == DEFAULT_VOLUME) {
            mPreferences.edit().remove(PREF_PREFIX + key).apply();
        } else {
            mPreferences.edit().putInt(PREF_PREFIX + key, clampedVolume).apply();
        }

        final AppState oldState = mActiveStates.get(key);
        if (oldState == null) {
            return;
        }

        final AppState newState = oldState.withVolume(clampedVolume);
        mActiveStates.put(key, newState);

        // Re-apply to every tracked player, including ones that are not in
        // STARTED state right now (e.g. between tracks). A new player created
        // for the next track needs the saved multiplier before it starts.
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
        final Set<Integer> activePlayerIds = new HashSet<>();
        final Set<Integer> activePids = new HashSet<>();

        if (configs != null) {
            for (AudioPlaybackConfiguration config : configs) {
                // Track *all* app players, not only ones currently in
                // PLAYER_STATE_STARTED with a media usage. When a music app
                // switches tracks, the replacement player first shows up as
                // IDLE with USAGE_UNKNOWN; skipping it here meant the saved
                // per-app multiplier was never applied and the next track
                // played at full volume. The master volume key happened to
                // trigger another callback when the player was STARTED, which
                // is why pressing it "restored" the volume.
                final int uid = config.getClientUid();
                if (UserHandle.getAppId(uid) < Process.FIRST_APPLICATION_UID) {
                    continue;
                }

                final String packageName = resolvePackageName(config);
                if (packageName == null || packageName.equals(mContext.getPackageName())) {
                    continue;
                }

                final int userId = UserHandle.getUserId(uid);
                final String key = makeKey(userId, packageName);
                final int volume = getSavedVolume(key);

                activePids.add(config.getClientPid());
                activePlayers.computeIfAbsent(key, unused -> new ArrayList<>()).add(config);
                activePlayerIds.add(config.getPlayerInterfaceId());

                // Apply to every player, including IDLE/USAGE_UNKNOWN ones.
                applyVolume(config, volume);

                // UI visibility stays restricted to genuinely playing media
                // apps; isEligible() is only used for the slider list.
                if (isEligible(config)) {
                    activeStates.putIfAbsent(
                            key, new AppState(key, packageName, uid, userId, volume));
                }
            }
        }

        mAppliedVolumeByPlayerId.keySet().retainAll(activePlayerIds);
        mPackageByPid.keySet().retainAll(activePids);
        mActivePlayers.clear();
        mActivePlayers.putAll(activePlayers);
        mActiveStates.clear();
        mActiveStates.putAll(activeStates);
        dispatchSnapshot();
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

    private int getSavedVolume(String key) {
        return Math.max(
                0,
                Math.min(
                        DEFAULT_VOLUME,
                        mPreferences.getInt(PREF_PREFIX + key, DEFAULT_VOLUME)));
    }

    private void applyVolume(AudioPlaybackConfiguration config, int volume) {
        final int playerId = config.getPlayerInterfaceId();
        final PlayerProxy proxy = config.getPlayerProxy();
        if (proxy == null) {
            return;
        }

        // Always (re-)apply the saved multiplier. The old "skip if already
        // applied to this player id" check broke track switches: a reused
        // player id kept its stale cached value and a replacement player was
        // left at full volume. setVolume() is cheap and idempotent.
        try {
            proxy.setVolume(volumeToGain(volume));
            mAppliedVolumeByPlayerId.put(playerId, volume);
        } catch (RuntimeException e) {
            // The player may be released between the playback callback and this operation.
            Log.d(TAG, "Unable to apply app volume to player: " + playerId, e);
        }
    }

    private static float volumeToGain(int volume) {
        if (volume <= 0) {
            return 0f;
        }
        if (volume >= DEFAULT_VOLUME) {
            return 1f;
        }
        final double normalized = volume / (double) DEFAULT_VOLUME;
        return (float) Math.pow(normalized, GAIN_CURVE_EXPONENT);
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
