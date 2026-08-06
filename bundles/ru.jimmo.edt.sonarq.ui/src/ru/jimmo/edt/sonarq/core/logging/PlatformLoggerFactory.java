/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.logging;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

/**
 * Builds {@link PlatformLogger} instances on top of this bundle's {@link ILog}, one per logger name.
 *
 * <p>The {@link ILog} is this bundle's own, whatever class asks for a logger: the entries come from code
 * running inside this plug-in (the vendored downloader), so they belong in this plug-in's log. The
 * SLF4J logger name is kept as a prefix of each message instead (see {@link PlatformLogger}).
 */
final class PlatformLoggerFactory implements ILoggerFactory
{
    private final Map<String, Logger> loggers = new ConcurrentHashMap<>();

    private final ILog log = Platform.getLog(PlatformLoggerFactory.class);

    @Override
    public Logger getLogger(String name)
    {
        return loggers.computeIfAbsent(name, key -> new PlatformLogger(key, log));
    }
}
