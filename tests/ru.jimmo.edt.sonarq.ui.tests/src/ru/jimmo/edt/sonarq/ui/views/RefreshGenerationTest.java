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
 * Tests for {@link RefreshGeneration}, the guard {@code SonarIssuesView} applies to every asynchronous
 * result before it reaches the tree, the markers or the status line.
 */
public class RefreshGenerationTest
{
    /** The ordinary case: a refresh that nothing interfered with is applied. */
    @Test
    public void anUndisturbedRefreshIsStillCurrentWhenItFinishes()
    {
        RefreshGeneration generation = new RefreshGeneration();

        long refresh = generation.start();

        assertTrue(generation.isCurrent(refresh));
    }

    /** A second Refresh (or a project switch) retires the first one's results. */
    @Test
    public void aNewerRefreshSupersedesAnOlderOne()
    {
        RefreshGeneration generation = new RefreshGeneration();

        long first = generation.start();
        long second = generation.start();

        assertFalse(generation.isCurrent(first));
        assertTrue(generation.isCurrent(second));
    }

    /**
     * The defect this guards: a quick-suppress inserts two lines into a file while an analysis is still
     * running. That analysis read the file before the edit, so its issues - and the markers synchronized
     * from them - carry pre-edit line numbers; applying them would overwrite the snapshot the suppression
     * just shifted and put every line in that file two lines off again. Invalidating makes the in-flight
     * result stale.
     */
    @Test
    public void anEditInvalidatesAnInFlightRefresh()
    {
        RefreshGeneration generation = new RefreshGeneration();
        long inFlight = generation.start();

        generation.invalidate();

        assertFalse("a result computed before the edit must not be applied", generation.isCurrent(inFlight));
    }

    /**
     * Invalidating must not lock the view out of its own bookkeeping: the marker sync the suppression
     * schedules right after the edit reads the generation after the bump, so it is still applied.
     */
    @Test
    public void workScheduledAfterTheEditIsStillApplied()
    {
        RefreshGeneration generation = new RefreshGeneration();
        generation.start();
        generation.invalidate();

        long afterEdit = generation.current();

        assertTrue(generation.isCurrent(afterEdit));
    }

    /** Two suppressions in a row each retire what came before them. */
    @Test
    public void everyInvalidationRetiresThePreviousGeneration()
    {
        RefreshGeneration generation = new RefreshGeneration();
        generation.start();
        generation.invalidate();
        long afterFirstEdit = generation.current();

        generation.invalidate();

        assertFalse(generation.isCurrent(afterFirstEdit));
        assertTrue(generation.isCurrent(generation.current()));
    }
}
