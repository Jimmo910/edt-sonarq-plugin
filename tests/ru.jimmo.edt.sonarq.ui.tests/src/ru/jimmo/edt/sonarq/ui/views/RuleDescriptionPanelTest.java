/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link RuleDescriptionPanel}'s platform-selection seam.
 *
 * <p>The panel itself embeds live SWT widgets ({@code Browser}, {@code StyledText}) and cannot be
 * instantiated headlessly, so these tests exercise only the pure, side-effect-free
 * {@link RuleDescriptionPanel#preferBrowser(String)} decision - the fix for issue #4 follow-up
 * (AndreiRch: the rule description pane was blank on Linux/GTK because a {@code Browser} there
 * constructs successfully but renders nothing when WebKit2GTK is missing or broken).
 */
public class RuleDescriptionPanelTest
{
    @Test
    public void gtkNeverPrefersTheBrowser()
    {
        assertFalse(RuleDescriptionPanel.preferBrowser("gtk"));
    }

    @Test
    public void win32PrefersTheBrowser()
    {
        assertTrue(RuleDescriptionPanel.preferBrowser("win32"));
    }

    @Test
    public void cocoaPrefersTheBrowser()
    {
        assertTrue(RuleDescriptionPanel.preferBrowser("cocoa"));
    }
}
