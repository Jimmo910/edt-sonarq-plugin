/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

/** Tests for {@link BslAnalyzeCommand}. */
public class BslAnalyzeCommandTest
{
    private static final Path EXECUTABLE = Path.of("state", "bsl-ls", "bsl-language-server", "bsl-language-server");
    private static final Path SRC_DIR = Path.of("project", "root", "src");
    private static final Path OUTPUT_DIR = Path.of("state", "reports");

    @Test
    public void buildsAnalyzeCommandInExactOrder()
    {
        List<String> command = BslAnalyzeCommand.build(EXECUTABLE, SRC_DIR, OUTPUT_DIR);

        assertEquals(String.valueOf(EXECUTABLE), command.get(0));
        assertEquals("--analyze", command.get(1));
        assertEquals("--srcDir", command.get(2));
        assertEquals(String.valueOf(SRC_DIR), command.get(3));
        assertEquals("--reporter", command.get(4));
        assertEquals("sarif", command.get(5));
        assertEquals("--outputDir", command.get(6));
        assertEquals(String.valueOf(OUTPUT_DIR), command.get(7));
        assertEquals(8, command.size());
    }

    @Test
    public void executableIsFirstTokenAndNoJavaIndirection()
    {
        List<String> command = BslAnalyzeCommand.build(EXECUTABLE, SRC_DIR, OUTPUT_DIR);
        assertEquals(String.valueOf(EXECUTABLE), command.get(0));
        for (String token : command)
        {
            assertTrue("native command must not shell out through -jar", !"-jar".equals(token));
        }
    }

    @Test
    public void sarifReporterKeyIsUsed()
    {
        List<String> command = BslAnalyzeCommand.build(EXECUTABLE, SRC_DIR, OUTPUT_DIR);
        int reporterIndex = command.indexOf("--reporter");
        assertTrue(reporterIndex >= 0);
        assertEquals("sarif", command.get(reporterIndex + 1));
    }

    @Test
    public void threeArgOverloadOmitsConfigurationFlag()
    {
        List<String> command = BslAnalyzeCommand.build(EXECUTABLE, SRC_DIR, OUTPUT_DIR);

        assertFalse(command.contains("--configuration"));
        assertEquals(8, command.size());
    }

    @Test
    public void fourArgOverloadWithNullConfigPathOmitsConfigurationFlag()
    {
        List<String> command = BslAnalyzeCommand.build(EXECUTABLE, SRC_DIR, OUTPUT_DIR, null);

        assertFalse(command.contains("--configuration"));
        assertEquals(8, command.size());
    }

    @Test
    public void configPathIsAppendedAfterOutputDirWithVerifiedConfigurationFlag()
    {
        Path configPath = Path.of("project", "root", "checks-config.json");
        List<String> command = BslAnalyzeCommand.build(EXECUTABLE, SRC_DIR, OUTPUT_DIR, configPath);

        assertEquals(10, command.size());
        assertEquals("--outputDir", command.get(6));
        assertEquals(String.valueOf(OUTPUT_DIR), command.get(7));
        // Verified against the real native launcher: `bsl-language-server analyze --help` lists
        // "-c, --configuration=<path>  Path to language server configuration file".
        assertEquals("--configuration", command.get(8));
        assertEquals(String.valueOf(configPath), command.get(9));
    }
}
