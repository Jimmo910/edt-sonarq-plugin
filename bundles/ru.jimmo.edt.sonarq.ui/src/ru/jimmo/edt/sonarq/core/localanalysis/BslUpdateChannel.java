/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

/**
 * The BSL Language Server engine update channel, our preference-facing choice of which release
 * {@link BslServerInstaller#ensureServer} installs.
 *
 * <p>{@code STABLE} and {@code PRERELEASE} map onto the upstream
 * {@code BslLanguageServerReleaseChannel} of {@code io.github.1c-syntax:utils}; {@code FIXED} has no
 * upstream counterpart and is handled entirely by {@link BslServerInstaller}.
 */
public enum BslUpdateChannel
{
    /** Always use the pinned {@link BslServerInstaller#VERSION}; never queries the network. */
    FIXED,

    /** Track the newest non-draft, non-prerelease GitHub release. */
    STABLE,

    /** Track the newest non-draft GitHub release, including pre-releases. */
    PRERELEASE;
}
