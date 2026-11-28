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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.view.SurfaceControl;

import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.server.LocalManagerRegistry;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.PackageManagerLocal;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

/** Keeps SurfaceFlinger's per-layer frame generation policy in sync with Secure settings. */
public final class FrameGenerationService extends SystemService {
    private static final String TAG = "FrameGenerationService";
    private static final long IPC_RETRY_DELAY_MS = 5_000;
    private static final int[] NO_OVERRIDES = new int[0];
    private static final String[] POLICY_KEYS = {
            Settings.Secure.FRAME_GENERATION_ENABLED,
            Settings.Secure.FRAME_GENERATION_DEFAULT,
            Settings.Secure.FRAME_GENERATION_MAX_FPS,
            Settings.Secure.FRAME_GENERATION_PAUSE_IN_BATTERY_SAVER,
            Settings.Secure.FRAME_GENERATION_APPS,
    };

    private final Handler mHandler = BackgroundThread.getHandler();
    private final Runnable mUpdatePolicy = this::updatePolicy;
    private final Runnable mRetryPolicy = this::scheduleUpdate;

    // Accessed only on mHandler.
    private ActivityManagerInternal mActivityManager;
    private PackageManagerLocal mPackageManager;
    private PowerManager mPowerManager;
    private boolean mWatching;

    public FrameGenerationService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {}

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_ACTIVITY_MANAGER_READY) {
            mHandler.post(this::startWatching);
        }
    }

    @Override
    public void onUserSwitching(@Nullable TargetUser from, @NonNull TargetUser to) {
        mHandler.post(this::scheduleUpdate);
    }

    @Override
    public void onUserUnlocked(@NonNull TargetUser user) {
        mHandler.post(() -> scheduleUpdateForUser(user.getUserIdentifier()));
    }

    private void startWatching() {
        mActivityManager = LocalServices.getService(ActivityManagerInternal.class);
        mPackageManager = LocalManagerRegistry.getManager(PackageManagerLocal.class);
        mPowerManager = getContext().getSystemService(PowerManager.class);
        if (mActivityManager == null || mPackageManager == null || mPowerManager == null) {
            Slog.e(TAG, "Cannot start frame generation policy observers: missing system service");
            return;
        }

        final ContentObserver observer = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, @NonNull Collection<Uri> uris, int flags,
                    int userId) {
                scheduleUpdateForUser(userId);
            }
        };
        final ContentResolver resolver = getContext().getContentResolver();
        for (String key : POLICY_KEYS) {
            resolver.registerContentObserver(Settings.Secure.getUriFor(key), false, observer,
                    UserHandle.USER_ALL);
        }

        new PackageMonitor(false /* supportsPackageRestartQuery */) {
            @Override
            public void onSomePackagesChanged() {
                scheduleUpdateForUser(getChangingUserId());
            }

            @Override
            public void onPackageModified(@NonNull String packageName) {
                scheduleUpdateForUser(getChangingUserId());
            }

            @Override
            public void onUidRemoved(int uid) {
                scheduleUpdateForUser(getChangingUserId());
            }
        }.register(getContext(), UserHandle.ALL, mHandler);

        getContext().registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                scheduleUpdate();
            }
        }, new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), null, mHandler,
                Context.RECEIVER_NOT_EXPORTED);

        mWatching = true;
        scheduleUpdate();
    }

    private void scheduleUpdateForUser(int userId) {
        if (mWatching && (userId == UserHandle.USER_ALL
                || userId == mActivityManager.getCurrentUserId())) {
            scheduleUpdate();
        }
    }

    private void scheduleUpdate() {
        if (mWatching && !mHandler.hasCallbacks(mUpdatePolicy)) {
            mHandler.post(mUpdatePolicy);
        }
    }

    private void updatePolicy() {
        final int userId = mActivityManager.getCurrentUserId();
        final ContentResolver resolver = getContext().getContentResolver();
        try {
            final int enabled = Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.FRAME_GENERATION_ENABLED, 0, userId);
            final int pause = Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.FRAME_GENERATION_PAUSE_IN_BATTERY_SAVER, 1, userId);
            final boolean batterySaver = enabled == 1 && mPowerManager.isPowerSaveMode();
            if (!FrameGenerationPolicy.isEnabled(enabled, pause, batterySaver)) {
                publishPolicy(false, userId, false, 0, NO_OVERRIDES, NO_OVERRIDES);
                return;
            }

            final boolean autoAll = Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.FRAME_GENERATION_DEFAULT, 0, userId) == 1;
            final int maxFps = FrameGenerationPolicy.sanitizeMaxFps(Settings.Secure.getIntForUser(
                    resolver, Settings.Secure.FRAME_GENERATION_MAX_FPS, 0, userId));
            final Map<String, Integer> appModes = FrameGenerationPolicy.parseAppModes(
                    Settings.Secure.getStringForUser(resolver,
                            Settings.Secure.FRAME_GENERATION_APPS, userId));

            final Map<Integer, Integer> uidModes;
            try (PackageManagerLocal.UnfilteredSnapshot snapshot =
                    mPackageManager.withUnfilteredSnapshot()) {
                uidModes = FrameGenerationPolicy.resolveUidOverrides(userId, autoAll, appModes,
                        snapshot.getPackageStates().values());
            }
            final int[] uids = new int[uidModes.size()];
            final int[] modes = new int[uidModes.size()];
            int i = 0;
            for (Map.Entry<Integer, Integer> entry : uidModes.entrySet()) {
                uids[i] = entry.getKey();
                modes[i] = entry.getValue();
                i++;
            }
            publishPolicy(true, userId, autoAll, maxFps, uids, modes);
        } catch (IOException e) {
            // A malformed settings value could have contained opt-outs. Don't fall back to auto.
            Slog.w(TAG, "Ignoring malformed frame generation app settings");
            publishPolicy(false, userId, false, 0, NO_OVERRIDES, NO_OVERRIDES);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Could not read frame generation policy", e);
            publishPolicy(false, userId, false, 0, NO_OVERRIDES, NO_OVERRIDES);
        }
    }

    private void publishPolicy(boolean enabled, int userId, boolean autoAll, int maxFps,
            int[] uids, int[] modes) {
        // A switch may have happened while reading settings or resolving packages.
        if (userId != mActivityManager.getCurrentUserId()) {
            scheduleUpdate();
            return;
        }
        try {
            SurfaceControl.setFrameGenerationPolicy(enabled, userId, autoAll, maxFps, uids, modes);
            mHandler.removeCallbacks(mRetryPolicy);
        } catch (IllegalStateException e) {
            if (!mHandler.hasCallbacks(mRetryPolicy)) {
                mHandler.postDelayed(mRetryPolicy, IPC_RETRY_DELAY_MS);
            }
            Slog.w(TAG, "Could not reach SurfaceFlinger to apply frame generation policy", e);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Could not apply frame generation policy to SurfaceFlinger", e);
        }
    }
}
