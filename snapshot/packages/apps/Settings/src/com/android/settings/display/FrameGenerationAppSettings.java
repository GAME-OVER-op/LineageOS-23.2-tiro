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

import static com.android.settings.display.FrameGenerationStore.APPS;
import static com.android.settings.display.FrameGenerationStore.APP_AUTO;
import static com.android.settings.display.FrameGenerationStore.APP_DEFAULT;
import static com.android.settings.display.FrameGenerationStore.APP_OFF;
import static com.android.settings.display.FrameGenerationStore.APP_VIDEO_ONLY;

import android.app.Dialog;
import android.app.settings.SettingsEnums;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.SearchView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.android.internal.content.PackageMonitor;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settingslib.utils.ThreadUtils;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A searchable, per-package list of apps that have a launcher activity for this user. */
public class FrameGenerationAppSettings extends SettingsPreferenceFragment {
    private static final String TAG = "FrameGenerationAppSettings";
    private static final String MODE_DIALOG = "app_mode";
    private static final String QUERY = "query";
    private static final int[] MODES = {APP_DEFAULT, APP_OFF, APP_VIDEO_ONLY, APP_AUTO};

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Preference> mAppRows = new LinkedHashMap<>();
    private final PackageMonitor mPackageMonitor = new PackageMonitor(false) {
        @Override
        public void onSomePackagesChanged() {
            reloadApps();
        }
    };
    private final ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            if (mStarted) {
                refreshAppModes();
            }
        }
    };

    private FrameGenerationStore mStore;
    private Preference mEmptyRow;
    private String mQuery = "";
    private boolean mStarted;
    private int mLoadGeneration;
    private int mEmptyMessage = R.string.frame_generation_loading_apps;

    @Override
    public int getMetricsCategory() {
        return SettingsEnums.DISPLAY;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        mStore = new FrameGenerationStore(requireContext());
        final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(
                getPrefContext());
        screen.setTitle(R.string.frame_generation_apps_title);
        setPreferenceScreen(screen);
        mEmptyRow = new Preference(getPrefContext());
        mEmptyRow.setSelectable(false);
        mEmptyRow.setTitle(mEmptyMessage);
        screen.addPreference(mEmptyRow);
        if (savedInstanceState != null) {
            mQuery = savedInstanceState.getString(QUERY, "");
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        final SearchView search = (SearchView) setPinnedHeaderView(
                R.layout.frame_generation_app_search);
        search.setQuery(mQuery, false);
        search.clearFocus();
        search.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextChange(String query) {
                mQuery = query;
                applyFilter();
                return true;
            }

            @Override
            public boolean onQueryTextSubmit(String query) {
                search.clearFocus();
                return true;
            }
        });
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(QUERY, mQuery);
    }

    @Override
    public void onStart() {
        super.onStart();
        final Context context = requireContext();
        if (!FrameGenerationPreferenceController.isSupported(context)) {
            finish();
            return;
        }
        requireActivity().setTitle(R.string.frame_generation_apps_title);
        mStarted = true;
        mStore.registerObserver(mObserver, APPS);
        mPackageMonitor.register(context, context.getUser(), mHandler);
        reloadApps();
    }

    @Override
    public void onStop() {
        if (mStarted) {
            mStarted = false;
            mLoadGeneration++;
            mPackageMonitor.unregister();
            mStore.unregisterObserver(mObserver);
        }
        super.onStop();
    }

    private void reloadApps() {
        final Context context = getContext();
        if (!mStarted || context == null) {
            return;
        }
        final int generation = ++mLoadGeneration;
        final PackageManager pm = context.getPackageManager();
        final int userId = context.getUserId();
        final Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        ThreadUtils.postOnBackgroundThread(() -> {
            try {
                final Map<String, String> labels = new LinkedHashMap<>();
                for (ResolveInfo resolve : queryLauncherActivities(pm, userId, null)) {
                    if (isLaunchable(resolve)
                            && !labels.containsKey(resolve.activityInfo.packageName)) {
                        final ApplicationInfo app = resolve.activityInfo.applicationInfo;
                        final String label = app.loadSafeLabel(pm, 0,
                                TextUtils.SAFE_STRING_FLAG_TRIM
                                        | TextUtils.SAFE_STRING_FLAG_FIRST_LINE).toString();
                        labels.put(app.packageName, label.isEmpty() ? app.packageName : label);
                    }
                }
                final List<Map.Entry<String, String>> apps = new ArrayList<>(labels.entrySet());
                apps.sort(Map.Entry.<String, String>comparingByValue(Collator.getInstance(locale))
                        .thenComparing(Comparator.comparing(Map.Entry::getKey)));
                mHandler.post(() -> showAppList(generation, apps,
                        R.string.frame_generation_no_apps));
            } catch (SecurityException e) {
                Log.w(TAG, "Unable to query launcher activities", e);
                mHandler.post(() -> showAppList(generation, List.of(),
                        R.string.frame_generation_load_failed));
            }
        });
    }

    private static List<ResolveInfo> queryLauncherActivities(PackageManager pm, int userId,
            String packageName) {
        final Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        return pm.queryIntentActivitiesAsUser(intent.setPackage(packageName), 0, userId);
    }

    private static boolean isLaunchable(ResolveInfo resolve) {
        return resolve.activityInfo != null && resolve.activityInfo.exported
                && resolve.activityInfo.applicationInfo != null
                && !resolve.isCrossProfileIntentForwarderActivity();
    }

    private void showAppList(int generation, List<Map.Entry<String, String>> apps,
            int emptyMessage) {
        if (!mStarted || generation != mLoadGeneration) {
            return;
        }
        final PreferenceScreen screen = getPreferenceScreen();
        for (Preference row : mAppRows.values()) {
            screen.removePreference(row);
        }
        mAppRows.clear();
        mEmptyMessage = emptyMessage;
        for (Map.Entry<String, String> app : apps) {
            final String packageName = app.getKey();
            final String label = app.getValue();
            final Preference row = new Preference(getPrefContext());
            row.setKey(packageName);
            row.setTitle(label);
            row.setPersistent(false);
            row.setOnPreferenceClickListener(preference -> showModeDialog(packageName, label));
            mAppRows.put(packageName, row);
            screen.addPreference(row);
        }
        refreshAppModes();
        applyFilter();
    }

    private void applyFilter() {
        final String query = mQuery.trim().toLowerCase(Locale.ROOT);
        boolean hasMatches = false;
        for (Map.Entry<String, Preference> app : mAppRows.entrySet()) {
            final Preference row = app.getValue();
            final boolean matches = app.getKey().toLowerCase(Locale.ROOT).contains(query)
                    || row.getTitle().toString().toLowerCase(Locale.ROOT).contains(query);
            row.setVisible(matches);
            hasMatches |= matches;
        }
        mEmptyRow.setTitle(mAppRows.isEmpty() ? mEmptyMessage
                : R.string.frame_generation_no_matching_apps);
        mEmptyRow.setVisible(!hasMatches);
    }

    private void refreshAppModes() {
        final JSONObject appModes = mStore.getAppModes();
        for (Map.Entry<String, Preference> app : mAppRows.entrySet()) {
            final String packageName = app.getKey();
            final int mode = FrameGenerationStore.getAppMode(appModes, packageName);
            app.getValue().setSummary(getString(R.string.frame_generation_app_summary,
                    packageName, getString(modeLabel(mode))));
        }
    }

    private boolean showModeDialog(String packageName, String label) {
        if (!mStarted || getChildFragmentManager().isStateSaved()
                || getChildFragmentManager().findFragmentByTag(MODE_DIALOG) != null) {
            return false;
        }
        // This dialog can be restored before the asynchronous list of preferences loads.
        final AppModeDialog dialog = new AppModeDialog();
        final Bundle args = new Bundle();
        args.putString(AppModeDialog.PACKAGE, packageName);
        args.putString(AppModeDialog.LABEL, label);
        dialog.setArguments(args);
        dialog.showNow(getChildFragmentManager(), MODE_DIALOG);
        return true;
    }

    private void onAppModeSelected(String packageName, int mode) {
        final Context context = getContext();
        if (!mStarted || context == null || packageName == null || packageName.isEmpty()) {
            return;
        }
        try {
            // An app might have been removed or disabled while its dialog was open.
            for (ResolveInfo resolve : queryLauncherActivities(context.getPackageManager(),
                    context.getUserId(), packageName)) {
                if (isLaunchable(resolve) && packageName.equals(resolve.activityInfo.packageName)) {
                    if (mStore.putAppMode(packageName, mode)) {
                        refreshAppModes();
                        return;
                    }
                    break;
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Unable to verify launcher activity", e);
        }
        Toast.makeText(context, R.string.frame_generation_update_failed, Toast.LENGTH_SHORT).show();
    }

    private static int modeLabel(int mode) {
        return switch (mode) {
            case APP_OFF -> R.string.frame_generation_app_off;
            case APP_VIDEO_ONLY -> R.string.frame_generation_app_video_only;
            case APP_AUTO -> R.string.frame_generation_auto;
            default -> R.string.frame_generation_app_default;
        };
    }

    /** Mode selection dialog with stable arguments independent of the asynchronous list. */
    public static class AppModeDialog extends DialogFragment {
        private static final String PACKAGE = "package";
        private static final String LABEL = "label";

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Bundle args = requireArguments();
            final String packageName = args.getString(PACKAGE);
            final CharSequence[] labels = new CharSequence[MODES.length];
            int selection = 0;
            final int mode = getParentFragment() instanceof FrameGenerationAppSettings parent
                    ? FrameGenerationStore.getAppMode(parent.mStore.getAppModes(), packageName)
                    : APP_DEFAULT;
            for (int index = 0; index < MODES.length; index++) {
                labels[index] = getText(modeLabel(MODES[index]));
                if (MODES[index] == mode) {
                    selection = index;
                }
            }
            return new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(args.getString(LABEL))
                    .setSingleChoiceItems(labels, selection, (dialog, which) -> {
                        if (getParentFragment() instanceof FrameGenerationAppSettings parent
                                && which >= 0 && which < MODES.length) {
                            parent.onAppModeSelected(packageName, MODES[which]);
                        }
                        dismiss();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
        }
    }
}
