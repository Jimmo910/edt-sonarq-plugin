/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.settings;

import static org.junit.Assert.assertEquals;

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
    }
}
