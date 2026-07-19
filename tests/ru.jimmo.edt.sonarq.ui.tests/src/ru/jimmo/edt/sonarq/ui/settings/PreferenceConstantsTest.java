/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.settings;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import ru.jimmo.edt.sonarq.core.localanalysis.BslUpdateChannel;

/** Tests for {@link PreferenceConstants#channelFromPreference(String)}. */
public class PreferenceConstantsTest
{
    @Test
    public void fixedMapsToFixedChannel()
    {
        assertEquals(BslUpdateChannel.FIXED,
            PreferenceConstants.channelFromPreference(PreferenceConstants.UPDATE_CHANNEL_FIXED));
    }

    @Test
    public void stableMapsToStableChannel()
    {
        assertEquals(BslUpdateChannel.STABLE,
            PreferenceConstants.channelFromPreference(PreferenceConstants.UPDATE_CHANNEL_STABLE));
    }

    @Test
    public void prereleaseMapsToPrereleaseChannel()
    {
        assertEquals(BslUpdateChannel.PRERELEASE,
            PreferenceConstants.channelFromPreference(PreferenceConstants.UPDATE_CHANNEL_PRERELEASE));
    }

    @Test
    public void unknownValueDefaultsToStableChannel()
    {
        assertEquals(BslUpdateChannel.STABLE, PreferenceConstants.channelFromPreference("bogus")); //$NON-NLS-1$
    }

    @Test
    public void blankValueDefaultsToStableChannel()
    {
        assertEquals(BslUpdateChannel.STABLE, PreferenceConstants.channelFromPreference("")); //$NON-NLS-1$
    }

    @Test
    public void nullValueDefaultsToStableChannel()
    {
        assertEquals(BslUpdateChannel.STABLE, PreferenceConstants.channelFromPreference(null));
    }
}
