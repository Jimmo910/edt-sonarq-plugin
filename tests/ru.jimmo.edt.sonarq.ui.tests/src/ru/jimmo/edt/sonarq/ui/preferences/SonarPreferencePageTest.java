/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.preferences;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the pure (SWT-free) parts of {@link SonarPreferencePage}.
 *
 * <p>Regression test for a review minor (issue #4/#5): the max-heap spinner must only be enabled while
 * local mode is selected AND the managed downloaded engine is in effect, since {@link
 * ru.jimmo.edt.sonarq.core.localanalysis.BslServerInstaller#configureHeap} rewrites only that engine's own
 * launcher configuration file and has no effect on a user-supplied executable.
 */
public class SonarPreferencePageTest
{
    @Test
    public void heapSpinnerEnabledOnlyInLocalModeWithManagedDownload()
    {
        assertTrue(SonarPreferencePage.heapSpinnerEnabled(true, false));
        assertFalse(SonarPreferencePage.heapSpinnerEnabled(true, true));
        assertFalse(SonarPreferencePage.heapSpinnerEnabled(false, false));
        assertFalse(SonarPreferencePage.heapSpinnerEnabled(false, true));
    }

    /**
     * Regression test for review minor M1: the URL field's focus-lost handler refilled the token field from
     * the secure store unconditionally. Typing a token and then clicking into the URL field before pressing
     * OK therefore replaced the typed token (usually with the empty string stored for that URL) and saved
     * that instead - a silent credential loss. A token the user typed must survive until the user changes it.
     */
    @Test
    public void typedSecretIsNeverOverwrittenByTheStoredOne()
    {
        assertFalse("a typed token must survive a URL edit",
            SonarPreferencePage.shouldReloadSecret("https://old", "https://new", true));
        assertFalse("a typed token must survive a focus-lost with the URL unchanged",
            SonarPreferencePage.shouldReloadSecret("https://old", "https://old", true));
    }

    /**
     * The refill itself must still happen when it is safe, otherwise a token belonging to the previous server
     * would be sent to - and saved for - a different one.
     */
    @Test
    public void untouchedSecretIsReloadedOnlyWhenTheUrlActuallyChanged()
    {
        assertTrue(SonarPreferencePage.shouldReloadSecret("https://old", "https://new", false));
        assertFalse(SonarPreferencePage.shouldReloadSecret("https://old", "https://old", false));
    }
}
