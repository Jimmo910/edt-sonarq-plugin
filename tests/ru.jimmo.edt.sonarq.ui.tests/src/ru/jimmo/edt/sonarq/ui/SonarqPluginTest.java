/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Skeleton smoke test: the test fragment compiles, sees the host bundle and runs.
 */
public class SonarqPluginTest
{
    @Test
    public void pluginIdMatchesBundleSymbolicName()
    {
        assertEquals("ru.jimmo.edt.sonarq.ui", SonarqPlugin.PLUGIN_ID);
    }
}
