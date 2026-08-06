/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.logging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.junit.Test;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies that the in-bundle SLF4J binding is really picked up, so the vendored
 * {@code io.github.1c-syntax:utils} downloader's diagnostics reach the Eclipse platform log instead of the
 * NOP logger.
 */
public class PlatformLogBindingTest
{
    private static final String SERVICE_RESOURCE = "META-INF/services/org.slf4j.spi.SLF4JServiceProvider";

    private static final String PROVIDER_CLASS = "ru.jimmo.edt.sonarq.core.logging.PlatformLogServiceProvider";

    @Test
    public void serviceDeclarationIsOnTheBundleClassPathWhereServiceLoaderLooks() throws IOException
    {
        // ServiceLoader resolves the file through the class loader of the SLF4J API, which - the API being on
        // this bundle's Bundle-ClassPath - is this bundle's loader. If build.properties ever stops shipping
        // META-INF/services into the jar, this fails before anyone notices a silent NOP logger.
        Enumeration<URL> found = LoggerFactory.class.getClassLoader().getResources(SERVICE_RESOURCE);
        assertTrue("no " + SERVICE_RESOURCE + " visible to the SLF4J API class loader", found.hasMoreElements());
        try (InputStream in = found.nextElement().openStream())
        {
            String declared = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            assertEquals(PROVIDER_CLASS, declared);
        }
    }

    @Test
    public void slf4jResolvesToThePlatformLogBindingRatherThanTheNopLogger()
    {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        assertEquals(PlatformLoggerFactory.class, factory.getClass());
    }

    @Test
    public void loggersAreCachedPerNameAndForwardTheUsefulLevels()
    {
        Logger logger = LoggerFactory.getLogger("com.github._1c_syntax.utils.downloader.Test");
        assertSame(logger, LoggerFactory.getLogger("com.github._1c_syntax.utils.downloader.Test"));
        assertTrue(logger.isErrorEnabled());
        assertTrue(logger.isWarnEnabled());
        assertTrue(logger.isInfoEnabled());
        // Dropped on purpose: the downloader logs per-chunk progress at these levels, and the EDT error log
        // is a user-facing view.
        assertFalse(logger.isDebugEnabled());
        assertFalse(logger.isTraceEnabled());
    }

    @Test
    public void aLoggedWarningReachesThePlatformLogWithItsPlaceholdersFilledIn()
    {
        List<IStatus> captured = new ArrayList<>();
        ILogListener listener = (status, plugin) -> captured.add(status);
        Platform.addLogListener(listener);
        try
        {
            LoggerFactory.getLogger("sonarq.binding.test")
                .warn("engine update check failed: {} of {}", Integer.valueOf(1), Integer.valueOf(2));
        }
        finally
        {
            Platform.removeLogListener(listener);
        }
        IStatus status = captured.stream()
            .filter(entry -> entry.getMessage() != null && entry.getMessage().contains("engine update check"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the warning never reached the platform log: " + captured));
        assertEquals(IStatus.WARNING, status.getSeverity());
        assertEquals("sonarq.binding.test - engine update check failed: 1 of 2", status.getMessage());
    }
}
