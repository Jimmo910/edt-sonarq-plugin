/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import ru.jimmo.edt.sonarq.ui.views.SonarIssuesView.IssueColumn;

/**
 * Remembers the visible width of the issue tree's hideable columns and decides when a column's width may be
 * touched at all.
 *
 * <p>Exists because the issue tree is rebuilt on every refresh - including every unattended auto-sync cycle -
 * and the hide/show pass that runs with it used to re-apply a hardcoded width to both hideable columns
 * unconditionally, silently discarding any width the user had dragged the Severity or Rule column to. The rule
 * enforced here is that a column is only ever resized when its hidden state actually changes; a rebuild that
 * leaves the grouping (and therefore the hidden state) alone leaves the widths alone too.
 *
 * <p>When a column is hidden its current width is remembered, and showing it again restores that width rather
 * than the hardcoded default - so a user-widened column survives a round trip through a grouping that hides
 * it. The default is used only when nothing usable was ever remembered.
 *
 * <p>Pure and SWT-free by design (widths are plain integers), so the whole decision is unit-testable without a
 * display; the caller applies the returned width to the real {@code TreeColumn}.
 */
final class IssueColumnWidths
{
    private final Map<IssueColumn, Integer> visibleWidths = new EnumMap<>(IssueColumn.class);

    private final Set<IssueColumn> hidden = EnumSet.noneOf(IssueColumn.class);

    /**
     * Decides the width to apply to a column that should now be hidden or shown.
     *
     * <p>Returns an empty result when the column is already in the requested state, which is the common case
     * on a plain refresh: the caller must then leave the column untouched, preserving the user's width.
     * Hiding remembers {@code currentWidth} (when it is positive) and yields {@code 0}; showing yields the
     * remembered width, or {@code defaultWidth} when none was remembered or the remembered one was not
     * usable.
     *
     * @param column the hideable column, not {@code null}
     * @param hide {@code true} when the column must be hidden, {@code false} when it must be visible
     * @param currentWidth the column's current width, in pixels
     * @param defaultWidth the column's designed width, in pixels, used when no visible width is remembered
     * @return the width to set on the column, or empty when the column must be left as it is
     */
    OptionalInt widthFor(IssueColumn column, boolean hide, int currentWidth, int defaultWidth)
    {
        if (hidden.contains(column) == hide)
        {
            return OptionalInt.empty();
        }
        if (hide)
        {
            if (currentWidth > 0)
            {
                visibleWidths.put(column, Integer.valueOf(currentWidth));
            }
            hidden.add(column);
            return OptionalInt.of(0);
        }
        hidden.remove(column);
        Integer remembered = visibleWidths.get(column);
        return OptionalInt.of(remembered != null && remembered.intValue() > 0 ? remembered.intValue()
            : defaultWidth);
    }
}
