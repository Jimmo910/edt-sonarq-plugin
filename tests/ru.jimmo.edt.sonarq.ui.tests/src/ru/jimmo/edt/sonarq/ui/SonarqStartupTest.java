/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/**
 * Tests the preference-watch state {@link SonarqStartup} exposes.
 *
 * <p>That state is what tells the preference page whether it has to arm the auto-sync scheduler itself: with
 * this plug-in's early start-up switched off in the workbench preferences, neither {@code earlyStartup()} nor
 * the listener it registers ever runs, and toggling auto-sync would otherwise do nothing until the next
 * restart. The headless test fragment runs with no workbench, so early start-up has not run here and the
 * state starts out false.
 */
public class SonarqStartupTest
{
    @After
    public void tearDown()
    {
        SonarqStartup.shutdown();
    }

    @Test
    public void preferenceWatchIsOffUntilEarlyStartupRuns()
    {
        assertFalse(SonarqStartup.isWatchingPreferences());
    }

    @Test
    public void earlyStartupStartsWatchingAndShutdownStops()
    {
        new SonarqStartup().earlyStartup();
        assertTrue(SonarqStartup.isWatchingPreferences());
        SonarqStartup.shutdown();
        assertFalse(SonarqStartup.isWatchingPreferences());
    }

    /**
     * Review minor M4 left the watch state observable exactly as before - it is a locking-consistency
     * refactor - so this only pins down that a second early start-up (the test suite itself does that, and
     * so does a plug-in restarted in a running workbench) is a no-op rather than something that breaks the
     * watch state or leaves teardown with nothing to remove.
     */
    @Test
    public void repeatedEarlyStartupKeepsWatchingAndStaysRemovable()
    {
        new SonarqStartup().earlyStartup();
        new SonarqStartup().earlyStartup();
        assertTrue(SonarqStartup.isWatchingPreferences());

        SonarqStartup.shutdown();

        assertFalse(SonarqStartup.isWatchingPreferences());
    }
}
