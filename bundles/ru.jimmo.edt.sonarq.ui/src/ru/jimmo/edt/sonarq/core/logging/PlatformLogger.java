/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.logging;

import org.eclipse.core.runtime.ILog;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

/**
 * An SLF4J logger that writes to the Eclipse platform log.
 *
 * <p>{@code ERROR}, {@code WARN} and {@code INFO} are forwarded to the matching {@link ILog} methods;
 * {@code DEBUG} and {@code TRACE} are dropped, because the EDT error log is a user-facing view and the
 * vendored downloader logs per-chunk progress at those levels. Markers are ignored, and the logger name is
 * prefixed to the message, since an {@link org.eclipse.core.runtime.IStatus} carries a plug-in id rather than
 * a logger name.
 */
final class PlatformLogger extends LegacyAbstractLogger
{
    private static final long serialVersionUID = 1L;

    private static final String NAME_SEPARATOR = " - "; //$NON-NLS-1$

    private final transient ILog log;

    /**
     * Creates a logger.
     *
     * @param loggerName the SLF4J logger name, not {@code null}
     * @param platformLog the platform log to write to, not {@code null}
     */
    PlatformLogger(String loggerName, ILog platformLog)
    {
        this.name = loggerName;
        this.log = platformLog;
    }

    @Override
    public boolean isTraceEnabled()
    {
        return false;
    }

    @Override
    public boolean isDebugEnabled()
    {
        return false;
    }

    @Override
    public boolean isInfoEnabled()
    {
        return true;
    }

    @Override
    public boolean isWarnEnabled()
    {
        return true;
    }

    @Override
    public boolean isErrorEnabled()
    {
        return true;
    }

    @Override
    protected String getFullyQualifiedCallerName()
    {
        // Only used by bindings that compute caller location data; the platform log records none.
        return null;
    }

    @Override
    protected void handleNormalizedLoggingCall(Level level, Marker marker, String messagePattern,
        Object[] arguments, Throwable throwable)
    {
        String message = name + NAME_SEPARATOR + MessageFormatter.basicArrayFormat(messagePattern, arguments);
        switch (level)
        {
            case ERROR -> log.error(message, throwable);
            case WARN -> log.warn(message, throwable);
            case INFO -> log.info(message, throwable);
            default ->
            {
                // DEBUG and TRACE are not enabled (see the isXEnabled methods), so they never reach here.
            }
        }
    }
}
