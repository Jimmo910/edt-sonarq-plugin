/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.sync;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the once-per-state-change gate on the "background auto-sync skipped" log line.
 *
 * <p>The recurring job keeps rescheduling while the plug-in is in local-analysis mode - by default every 15
 * minutes, for as long as EDT runs - and used to log the same INFO line on every cycle (review minor M12).
 */
public class AutoSyncLocalModeSkipLogTest
{
    @Before
    public void armGate()
    {
        AutoSyncScheduler.leaveLocalModeSkip();
    }

    @After
    public void disarmGate()
    {
        AutoSyncScheduler.leaveLocalModeSkip();
    }

    @Test
    public void repeatedSkipCyclesLogOnlyOnce()
    {
        assertTrue(AutoSyncScheduler.enterLocalModeSkip());
        assertFalse(AutoSyncScheduler.enterLocalModeSkip());
        assertFalse(AutoSyncScheduler.enterLocalModeSkip());
    }

    /** A genuinely new occurrence - local mode re-entered after a server-mode cycle - is not silenced. */
    @Test
    public void returningToLocalModeAfterAServerCycleLogsAgain()
    {
        assertTrue(AutoSyncScheduler.enterLocalModeSkip());
        assertFalse(AutoSyncScheduler.enterLocalModeSkip());

        AutoSyncScheduler.leaveLocalModeSkip();

        assertTrue(AutoSyncScheduler.enterLocalModeSkip());
    }
}
