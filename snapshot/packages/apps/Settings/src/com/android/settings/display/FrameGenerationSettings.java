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

import static com.android.settings.display.FrameGenerationStore.DEFAULT_MODE;
import static com.android.settings.display.FrameGenerationStore.ENABLED;
import static com.android.settings.display.FrameGenerationStore.MAX_FPS;
import static com.android.settings.display.FrameGenerationStore.PAUSE_IN_BATTERY_SAVER;

import android.app.settings.SettingsEnums;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;

/** User-facing controls for frame generation. */
public class FrameGenerationSettings extends SettingsPreferenceFragment {
    private static final int DEFAULT_DISABLED = 0;
    private static final int DEFAULT_SELECTED_APPS = 0;
    private static final int DEFAULT_AUTO_FPS = 0;
    private static final int DEFAULT_PAUSE = 1;

    private final ContentObserver mObserver = new ContentObserver(
            new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            if (mStarted) {
                refreshPreferences();
            }
        }
    };

    private FrameGenerationStore mStore;
    private boolean mStarted;

    @Override
    public int getMetricsCategory() {
        return SettingsEnums.DISPLAY;
    }

    @Override
    protected int getPreferenceScreenResId() {
        return R.xml.frame_generation_settings;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        mStore = new FrameGenerationStore(requireContext());
        for (String key : new String[]{ENABLED, DEFAULT_MODE, MAX_FPS, PAUSE_IN_BATTERY_SAVER}) {
            findPreference(key).setOnPreferenceChangeListener(this::onSettingChanged);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if (!FrameGenerationPreferenceController.isSupported(requireContext())) {
            finish();
            return;
        }
        requireActivity().setTitle(R.string.frame_generation_title);
        mStarted = true;
        mStore.registerObserver(mObserver, ENABLED, DEFAULT_MODE, MAX_FPS, PAUSE_IN_BATTERY_SAVER);
        refreshPreferences();
    }

    @Override
    public void onStop() {
        if (mStarted) {
            mStarted = false;
            mStore.unregisterObserver(mObserver);
        }
        super.onStop();
    }

    private boolean onSettingChanged(Preference preference, Object newValue) {
        final Context context = getContext();
        if (!mStarted || context == null) {
            return false;
        }
        final int value;
        if (preference instanceof SwitchPreferenceCompat && newValue instanceof Boolean checked) {
            value = checked ? 1 : 0;
        } else if (preference instanceof ListPreference list && newValue instanceof String entry
                && list.findIndexOfValue(entry) >= 0) {
            value = Integer.parseInt(entry);
        } else {
            return false;
        }
        if (mStore.putInt(preference.getKey(), value)) {
            return true;
        }
        Toast.makeText(context, R.string.frame_generation_update_failed, Toast.LENGTH_SHORT).show();
        return false;
    }

    private void refreshPreferences() {
        ((SwitchPreferenceCompat) findPreference(ENABLED))
                .setChecked(mStore.getInt(ENABLED, DEFAULT_DISABLED) == 1);
        ((SwitchPreferenceCompat) findPreference(PAUSE_IN_BATTERY_SAVER))
                .setChecked(mStore.getInt(PAUSE_IN_BATTERY_SAVER, DEFAULT_PAUSE) != 0);
        refreshList(DEFAULT_MODE, DEFAULT_SELECTED_APPS);
        refreshList(MAX_FPS, DEFAULT_AUTO_FPS);
    }

    private void refreshList(String key, int fallback) {
        final ListPreference preference = findPreference(key);
        final String value = Integer.toString(mStore.getInt(key, fallback));
        preference.setValue(preference.findIndexOfValue(value) >= 0
                ? value : Integer.toString(fallback));
    }
}
