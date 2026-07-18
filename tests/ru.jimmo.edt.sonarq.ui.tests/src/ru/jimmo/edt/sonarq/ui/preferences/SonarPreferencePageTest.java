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
}
