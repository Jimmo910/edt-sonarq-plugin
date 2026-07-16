/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IPreferencesService;
import org.junit.Test;

import ru.jimmo.edt.sonarq.ui.SonarqPlugin;

/** Tests for {@link PreferenceInitializer}. */
public class PreferenceDefaultsTest
{
    @Test
    public void defaultsAreInitialized()
    {
        IPreferencesService service = Platform.getPreferencesService();
        assertEquals("server",
            service.getString(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_MODE, null, null));
        assertEquals(30,
            service.getInt(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_TIMEOUT_SECONDS, -1, null));
        assertEquals("LOCAL_AUTO",
            service.getString(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_LAUNCH_MODE, null, null));
        assertTrue(
            service.getBoolean(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_SHOW_MARKERS, false, null));
        assertFalse(
            service.getBoolean(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_AUTO_SYNC, true, null));
        assertEquals(15,
            service.getInt(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_AUTO_SYNC_MINUTES, -1, null));
    }
}
