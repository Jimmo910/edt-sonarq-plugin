/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.analysis;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Tests for {@link AnalysisLaunchMode}. */
public class AnalysisLaunchModeTest
{
    @Test
    public void fromKeyRoundTripsEachMode()
    {
        for (AnalysisLaunchMode mode : AnalysisLaunchMode.values())
        {
            assertEquals(mode, AnalysisLaunchMode.fromKey(mode.name()));
        }
    }

    @Test
    public void unknownKeyFallsBackToLocalAuto()
    {
        assertEquals(AnalysisLaunchMode.LOCAL_AUTO, AnalysisLaunchMode.fromKey("does-not-exist"));
    }

    @Test
    public void nullKeyFallsBackToLocalAuto()
    {
        assertEquals(AnalysisLaunchMode.LOCAL_AUTO, AnalysisLaunchMode.fromKey(null));
    }

    @Test
    public void lowerCaseKeyIsAccepted()
    {
        assertEquals(AnalysisLaunchMode.CI_TRIGGER, AnalysisLaunchMode.fromKey("ci_trigger"));
    }
}
