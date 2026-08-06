/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.logging;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * The SLF4J binding of this bundle: routes everything the vendored {@code io.github.1c-syntax:utils}
 * downloader logs into the Eclipse platform log, so it shows up in the EDT error log.
 *
 * <p>Without a provider, SLF4J 2.x falls back to its NOP logger and prints {@code SLF4J: No SLF4J providers
 * were found.} to the console - and, more importantly, silently discards the only explanation the upstream
 * {@code BslLanguageServerDownloader} ever gives for <em>why</em> an engine update check failed (a rate-limited
 * GitHub API, a release without a matching asset, a truncated download). This plug-in degrades such a failure
 * to an offline fallback rather than surfacing it (see {@code BslServerInstaller#installWithFallback}), so
 * without these log entries the user only sees that the engine did not update, never why.
 *
 * <p>Deliberately minimal and dependency-free: no configuration file, no level filtering beyond the fixed
 * cut-off in {@link PlatformLogger} and no MDC. It is discovered by {@link java.util.ServiceLoader} through
 * {@code META-INF/services/org.slf4j.spi.SLF4JServiceProvider} in this bundle's own jar root. That works
 * without any SPI weaving because the SLF4J API is on this bundle's {@code Bundle-ClassPath}: API and provider
 * share one class loader, which is the loader {@code LoggerFactory} hands to {@code ServiceLoader}, so this is
 * an intra-bundle lookup rather than the cross-bundle case {@code org.apache.aries.spifly} exists for.
 */
public final class PlatformLogServiceProvider implements SLF4JServiceProvider
{
    /**
     * The SLF4J API version this binding is built against, mirroring the value the API's own fallback
     * provider reports; {@code LoggerFactory} compares it against the API on the class path and warns on a
     * mismatch.
     */
    private static final String REQUESTED_API_VERSION = "2.0.99"; //$NON-NLS-1$

    private final ILoggerFactory loggerFactory = new PlatformLoggerFactory();

    private final IMarkerFactory markerFactory = new BasicMarkerFactory();

    private final MDCAdapter mdcAdapter = new NOPMDCAdapter();

    @Override
    public ILoggerFactory getLoggerFactory()
    {
        return loggerFactory;
    }

    @Override
    public IMarkerFactory getMarkerFactory()
    {
        return markerFactory;
    }

    @Override
    public MDCAdapter getMDCAdapter()
    {
        return mdcAdapter;
    }

    @Override
    public String getRequestedApiVersion()
    {
        return REQUESTED_API_VERSION;
    }

    @Override
    public void initialize()
    {
        // Everything this binding needs is built in the field initializers above; nothing to set up lazily.
    }
}
