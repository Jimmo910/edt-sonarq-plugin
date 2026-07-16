/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.junit.Test;

/** Tests for {@link BslAnalyzeCommand}. */
public class BslAnalyzeCommandTest
{
    private static final Path JAVA = Path.of("jvm", "bin", "java");
    private static final Path SERVER_JAR = Path.of("state", "bsl-ls", "bsl-language-server-1.0.4-exec.jar");
    private static final Path SRC_DIR = Path.of("project", "root", "src");
    private static final Path OUTPUT_DIR = Path.of("state", "reports");

    @Test
    public void buildsAnalyzeCommandInExactOrder()
    {
        List<String> command = BslAnalyzeCommand.build(JAVA, SERVER_JAR, SRC_DIR, OUTPUT_DIR);

        assertEquals(String.valueOf(JAVA), command.get(0));
        assertEquals("-jar", command.get(1));
        assertEquals(String.valueOf(SERVER_JAR), command.get(2));
        assertEquals("--analyze", command.get(3));
        assertEquals("--srcDir", command.get(4));
        assertEquals(String.valueOf(SRC_DIR), command.get(5));
        assertEquals("--reporter", command.get(6));
        assertEquals("sarif", command.get(7));
        assertEquals("--outputDir", command.get(8));
        assertEquals(String.valueOf(OUTPUT_DIR), command.get(9));
        assertEquals(10, command.size());
    }

    @Test
    public void javaExecutableResolvesUnderJavaHomeBin()
    {
        Path executable = BslAnalyzeCommand.javaExecutable();

        Path javaHome = Path.of(System.getProperty("java.home"));
        assertEquals(javaHome.resolve("bin"), executable.getParent());
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String expectedName = os.contains("win") ? "java.exe" : "java";
        assertEquals(expectedName, executable.getFileName().toString());
    }

    @Test
    public void sarifReporterKeyIsUsed()
    {
        List<String> command = BslAnalyzeCommand.build(JAVA, SERVER_JAR, SRC_DIR, OUTPUT_DIR);
        int reporterIndex = command.indexOf("--reporter");
        assertTrue(reporterIndex >= 0);
        assertEquals("sarif", command.get(reporterIndex + 1));
    }
}
