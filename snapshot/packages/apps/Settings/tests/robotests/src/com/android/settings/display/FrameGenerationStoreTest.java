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

import static com.android.settings.display.FrameGenerationStore.APP_AUTO;
import static com.android.settings.display.FrameGenerationStore.APP_DEFAULT;
import static com.android.settings.display.FrameGenerationStore.APP_OFF;
import static com.android.settings.display.FrameGenerationStore.APP_VIDEO_ONLY;
import static com.android.settings.display.FrameGenerationStore.getAppMode;
import static com.android.settings.display.FrameGenerationStore.parseAppModes;
import static com.android.settings.display.FrameGenerationStore.updateAppModes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.json.JSONObject;
import org.junit.Test;

public class FrameGenerationStoreTest {
    private static final String FIRST_APP = "com.example.first";
    private static final String SECOND_APP = "com.example.second";

    @Test
    public void unconfiguredOrMalformedModes_returnDefault() {
        assertEquals(APP_DEFAULT, getAppMode(parseAppModes(null), FIRST_APP));
        assertEquals(APP_DEFAULT, getAppMode(parseAppModes("{"), FIRST_APP));
        assertEquals(APP_DEFAULT, getAppMode(parseAppModes("[-1]"), FIRST_APP));
    }

    @Test
    public void unknownModeOrNonInteger_returnDefault() {
        assertEquals(APP_DEFAULT, getAppMode(parseAppModes("{\"com.example.first\":3}"),
                FIRST_APP));
        assertEquals(APP_DEFAULT, getAppMode(parseAppModes("{\"com.example.first\":\"1\"}"),
                FIRST_APP));
        assertEquals(APP_DEFAULT, getAppMode(parseAppModes("{\"com.example.first\":1.5}"),
                FIRST_APP));
    }

    @Test
    public void writingOverrides_keepsIntegerModesAndUnrelatedPackages() throws Exception {
        final JSONObject modes = parseAppModes("{\"com.example.first\":-1}");
        final String serialized = updateAppModes(modes, SECOND_APP, APP_VIDEO_ONLY);
        assertEquals("{\"com.example.first\":-1,\"com.example.second\":1}", serialized);
        assertEquals(APP_OFF, getAppMode(parseAppModes(serialized), FIRST_APP));
        assertEquals(APP_VIDEO_ONLY, getAppMode(parseAppModes(serialized), SECOND_APP));

        final String updated = updateAppModes(modes, FIRST_APP, APP_AUTO);
        assertEquals(APP_AUTO, getAppMode(parseAppModes(updated), FIRST_APP));
        assertEquals(APP_VIDEO_ONLY, getAppMode(parseAppModes(updated), SECOND_APP));
    }

    @Test
    public void selectingDefault_removesOnlyTheSelectedPackage() throws Exception {
        final JSONObject modes = parseAppModes(
                "{\"com.example.first\":-1,\"com.example.second\":2}");
        final JSONObject updated = parseAppModes(updateAppModes(modes, FIRST_APP, APP_DEFAULT));
        assertFalse(updated.has(FIRST_APP));
        assertEquals(APP_AUTO, getAppMode(updated, SECOND_APP));
        assertEquals("{}", updateAppModes(updated, SECOND_APP, APP_DEFAULT));
    }
}
