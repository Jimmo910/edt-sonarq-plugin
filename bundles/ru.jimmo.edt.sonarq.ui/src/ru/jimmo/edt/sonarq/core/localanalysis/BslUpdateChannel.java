/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

/**
 * The BSL Language Server engine update channel used to pick which release {@link BslReleaseResolver}
 * resolves.
 */
public enum BslUpdateChannel
{
    /** Always use the pinned {@link BslServerInstaller#VERSION}; never queries the network. */
    FIXED,

    /** Resolve the newest non-draft, non-prerelease GitHub release. */
    STABLE,

    /** Resolve the newest non-draft GitHub release, including pre-releases. */
    PRERELEASE;
}
