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

import static com.android.server.display.FrameGenerationPolicy.MODE_AUTO;
import static com.android.server.display.FrameGenerationPolicy.MODE_OFF;
import static com.android.server.display.FrameGenerationPolicy.MODE_VIDEO;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.Process;
import android.os.UserHandle;

import com.android.server.pm.pkg.PackageState;
import com.android.server.pm.pkg.PackageUserState;

import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class FrameGenerationPolicyTest {
    private static final int USER_ID = 10;
    private static final int OTHER_USER_ID = 11;
    private static final int APP_ID = Process.FIRST_APPLICATION_UID;

    @Test
    public void enabledAndBatterySaver() {
        assertFalse(FrameGenerationPolicy.isEnabled(0, 1, false));
        assertFalse(FrameGenerationPolicy.isEnabled(2, 0, false));
        assertFalse(FrameGenerationPolicy.isEnabled(1, 1, true));
        assertTrue(FrameGenerationPolicy.isEnabled(1, 1, false));
        assertTrue(FrameGenerationPolicy.isEnabled(1, 0, true));
    }

    @Test
    public void maxFpsAcceptsOnlySupportedValues() {
        assertEquals(0, FrameGenerationPolicy.sanitizeMaxFps(0));
        assertEquals(60, FrameGenerationPolicy.sanitizeMaxFps(60));
        assertEquals(120, FrameGenerationPolicy.sanitizeMaxFps(120));
        assertEquals(0, FrameGenerationPolicy.sanitizeMaxFps(90));
        assertEquals(0, FrameGenerationPolicy.sanitizeMaxFps(-1));
    }

    @Test
    public void parsesOnlySupportedIntegerModes() throws IOException {
        assertEquals(Map.of(), FrameGenerationPolicy.parseAppModes(null));
        assertEquals(Map.of(), FrameGenerationPolicy.parseAppModes("{}"));
        assertEquals(Map.of("pkg.off", MODE_OFF, "pkg.video", MODE_VIDEO, "pkg.auto", MODE_AUTO),
                FrameGenerationPolicy.parseAppModes("""
                        {"pkg.off":-1,"pkg.string":"1","pkg.null":null,"pkg.video":1,
                         "pkg.float":2.0,"pkg.default":0,"pkg.list":[2],"pkg.auto":2}
                        """));
    }

    @Test
    public void rejectsMalformedOrNonObjectJson() {
        assertThrows(IOException.class, () -> FrameGenerationPolicy.parseAppModes("[]"));
        assertThrows(IOException.class, () -> FrameGenerationPolicy.parseAppModes("{\"pkg\":-1"));
        assertThrows(IOException.class,
                () -> FrameGenerationPolicy.parseAppModes("{\"pkg\":-1}{}"));
    }

    @Test
    public void selectedAppsResolvesOnlyInstalledNormalAppUidsForTheRequestedUser() {
        final PackageState video = pkg("pkg.video", APP_ID, true);
        final PackageState off = pkg("pkg.off", APP_ID + 1, true);
        final PackageState differentUser = pkg("pkg.other", APP_ID + 2, false);
        setInstalled(differentUser, OTHER_USER_ID, true);
        setInstalled(video, OTHER_USER_ID, false);
        setInstalled(off, OTHER_USER_ID, false);
        final PackageState core = pkg("android", Process.SYSTEM_UID, true);
        final PackageState isolated = pkg("pkg.isolated", Process.FIRST_ISOLATED_UID, true);
        final List<PackageState> packages = List.of(video, off, differentUser, core, isolated);
        final Map<String, Integer> modes = Map.of("pkg.video", MODE_VIDEO, "pkg.off", MODE_OFF,
                "pkg.other", MODE_AUTO, "android", MODE_AUTO, "pkg.isolated", MODE_AUTO);

        assertEquals(Map.of(uid(APP_ID), MODE_VIDEO, uid(APP_ID + 1), MODE_OFF),
                FrameGenerationPolicy.resolveUidOverrides(USER_ID, false, modes, packages));
        assertEquals(Map.of(UserHandle.getUid(OTHER_USER_ID, APP_ID + 2), MODE_AUTO),
                FrameGenerationPolicy.resolveUidOverrides(OTHER_USER_ID, false, modes, packages));
    }

    @Test
    public void autoDefaultExcludesPrivilegedAndSystemAppUids() {
        final PackageState systemUi = pkg("pkg.systemui", APP_ID, true);
        when(systemUi.isPrivileged()).thenReturn(true);
        final PackageState systemApp = pkg("pkg.preinstalled", APP_ID + 1, true);
        when(systemApp.isSystem()).thenReturn(true);
        final PackageState updatedSystemApp = pkg("pkg.updated", APP_ID + 2, true);
        when(updatedSystemApp.isUpdatedSystemApp()).thenReturn(true);
        final PackageState ordinaryApp = pkg("pkg.ordinary", APP_ID + 3, true);
        final PackageState off = pkg("pkg.off", APP_ID + 4, true);

        final Map<String, Integer> modes = Map.of("pkg.systemui", MODE_AUTO,
                "pkg.updated", MODE_VIDEO, "pkg.off", MODE_OFF);
        assertEquals(Map.of(uid(APP_ID), MODE_OFF, uid(APP_ID + 1), MODE_OFF,
                        uid(APP_ID + 2), MODE_VIDEO, uid(APP_ID + 4), MODE_OFF),
                FrameGenerationPolicy.resolveUidOverrides(USER_ID, true, modes,
                        List.of(systemUi, systemApp, updatedSystemApp, ordinaryApp, off)));
    }

    @Test
    public void sharedUidRequiresAgreementForTheEffectiveMode() {
        final PackageState first = pkg("pkg.first", APP_ID, true);
        final PackageState second = pkg("pkg.second", APP_ID, true);
        final List<PackageState> packages = List.of(first, second);

        assertEquals(Map.of(uid(APP_ID), MODE_OFF), FrameGenerationPolicy.resolveUidOverrides(
                USER_ID, false, Map.of("pkg.first", MODE_AUTO), packages));
        assertEquals(Map.of(uid(APP_ID), MODE_OFF), FrameGenerationPolicy.resolveUidOverrides(
                USER_ID, true, Map.of("pkg.second", MODE_VIDEO), packages));
        assertEquals(Map.of(uid(APP_ID), MODE_VIDEO), FrameGenerationPolicy.resolveUidOverrides(
                USER_ID, true, Map.of("pkg.first", MODE_VIDEO, "pkg.second", MODE_VIDEO),
                packages));
        assertEquals(Map.of(uid(APP_ID), MODE_OFF), FrameGenerationPolicy.resolveUidOverrides(
                USER_ID, true, Map.of("pkg.first", MODE_OFF), packages));
    }

    private static int uid(int appId) {
        return UserHandle.getUid(USER_ID, appId);
    }

    private static PackageState pkg(String name, int appId, boolean installed) {
        final PackageState pkg = mock(PackageState.class);
        when(pkg.getPackageName()).thenReturn(name);
        when(pkg.getAppId()).thenReturn(appId);
        setInstalled(pkg, USER_ID, installed);
        return pkg;
    }

    private static void setInstalled(PackageState pkg, int userId, boolean installed) {
        final PackageUserState state = mock(PackageUserState.class);
        when(state.isInstalled()).thenReturn(installed);
        when(pkg.getUserStateOrDefault(userId)).thenReturn(state);
    }
}
