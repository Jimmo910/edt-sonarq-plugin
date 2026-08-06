/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.OptionalInt;

import org.junit.Test;

import ru.jimmo.edt.sonarq.ui.views.SonarIssuesView.IssueColumn;

/** Tests for {@link IssueColumnWidths}. */
public class IssueColumnWidthsTest
{
    private static final int DEFAULT_WIDTH = 140;

    private final IssueColumnWidths widths = new IssueColumnWidths();

    @Test
    public void stayingVisibleNeverTouchesTheWidth()
    {
        // The regression this class exists for: every refresh re-applies the visibility pass, and a column
        // that is not being hidden must keep the width the user dragged it to (200 here, not the default).
        assertFalse(widths.widthFor(IssueColumn.RULE, false, DEFAULT_WIDTH, DEFAULT_WIDTH).isPresent());
        assertFalse(widths.widthFor(IssueColumn.RULE, false, 200, DEFAULT_WIDTH).isPresent());
        assertFalse(widths.widthFor(IssueColumn.RULE, false, 200, DEFAULT_WIDTH).isPresent());
    }

    @Test
    public void stayingHiddenNeverTouchesTheWidth()
    {
        assertEquals(OptionalInt.of(0), widths.widthFor(IssueColumn.RULE, true, DEFAULT_WIDTH, DEFAULT_WIDTH));
        assertFalse(widths.widthFor(IssueColumn.RULE, true, 0, DEFAULT_WIDTH).isPresent());
    }

    @Test
    public void hidingZeroesTheWidthAndShowingRestoresTheUserWidth()
    {
        assertEquals(OptionalInt.of(0), widths.widthFor(IssueColumn.RULE, true, 200, DEFAULT_WIDTH));
        assertEquals(OptionalInt.of(200), widths.widthFor(IssueColumn.RULE, false, 0, DEFAULT_WIDTH));
    }

    @Test
    public void showingAColumnHiddenFromTheStartUsesTheDefaultWidth()
    {
        // Hidden while its width already read 0 (nothing usable to remember), so the designed width wins.
        assertEquals(OptionalInt.of(0), widths.widthFor(IssueColumn.RULE, true, 0, DEFAULT_WIDTH));
        assertEquals(OptionalInt.of(DEFAULT_WIDTH), widths.widthFor(IssueColumn.RULE, false, 0, DEFAULT_WIDTH));
    }

    @Test
    public void columnsAreTrackedIndependently()
    {
        assertEquals(OptionalInt.of(0), widths.widthFor(IssueColumn.SEVERITY, true, 90, DEFAULT_WIDTH));
        assertTrue(widths.widthFor(IssueColumn.RULE, true, 200, DEFAULT_WIDTH).isPresent());
        assertEquals(OptionalInt.of(90), widths.widthFor(IssueColumn.SEVERITY, false, 0, DEFAULT_WIDTH));
        assertEquals(OptionalInt.of(200), widths.widthFor(IssueColumn.RULE, false, 0, DEFAULT_WIDTH));
    }
}
