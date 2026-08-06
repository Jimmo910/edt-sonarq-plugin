/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/** Verifies the vendored utils downloader classes are on the bundle class path. */
public class UtilsDownloaderAvailableTest
{
    private static final String DOWNLOADER_CLASS =
        "com.github._1c_syntax.utils.downloader.BslLanguageServerDownloader"; //$NON-NLS-1$

    @Test
    public void downloaderClassesAreOnTheBundleClassPath() throws Exception
    {
        assertNotNull(Class.forName(DOWNLOADER_CLASS));
        assertNotNull(Class.forName("com.github._1c_syntax.utils.downloader.GitHubReleaseClient")); //$NON-NLS-1$
        assertNotNull(
            Class.forName("com.github._1c_syntax.utils.downloader.BslLanguageServerReleaseChannel")); //$NON-NLS-1$
        assertNotNull(Class.forName("org.semver4j.Semver")); //$NON-NLS-1$
    }

    @Test
    public void downloaderClassLoadsNotJustResolves() throws Exception
    {
        // Regression guard: before the EDT 2026.2 retarget (BREE JavaSE-21), loading this
        // class on the plugin's JVM threw UnsupportedClassVersionError because utils was
        // compiled for Java 21 while EDT ran on 17. Class.forName here actually LOADS and
        // links the class (unlike a compile-time reference), so it catches that failure mode.
        Class<?> downloaderClass = Class.forName(DOWNLOADER_CLASS);
        assertEquals(DOWNLOADER_CLASS, downloaderClass.getName());
    }

    @Test
    public void gsonComesFromTheTargetPlatformAndSatisfiesUtils()
    {
        // utils 0.10.1 parses GitHub JSON with these gson APIs; the EDT target ships gson
        // that satisfies utils' usage (JsonParser.parseString, getAsString, isJsonPrimitive).
        assertNotNull(com.google.gson.JsonParser.parseString("{\"a\":1}").getAsJsonObject()); //$NON-NLS-1$
    }
}
