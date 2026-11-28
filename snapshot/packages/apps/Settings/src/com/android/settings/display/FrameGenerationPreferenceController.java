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

import android.content.Context;

import com.android.settings.R;
import com.android.settings.core.BasePreferenceController;

/** Controls the visibility of the frame generation entry in Display settings. */
public class FrameGenerationPreferenceController extends BasePreferenceController {
    public FrameGenerationPreferenceController(Context context, String key) {
        super(context, key);
    }

    @Override
    public int getAvailabilityStatus() {
        return isSupported(mContext) ? AVAILABLE : UNSUPPORTED_ON_DEVICE;
    }

    static boolean isSupported(Context context) {
        return context.getResources().getBoolean(R.bool.config_frame_generation_available);
    }
}
