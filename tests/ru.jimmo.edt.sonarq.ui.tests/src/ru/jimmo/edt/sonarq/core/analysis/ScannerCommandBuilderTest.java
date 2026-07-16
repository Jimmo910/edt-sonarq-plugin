/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import ru.jimmo.edt.sonarq.core.client.SonarConnection;

/** Tests for {@link ScannerCommandBuilder}. */
public class ScannerCommandBuilderTest
{
    private static final Path SCANNER = Path.of("scanner", "bin", "sonar-scanner.bat");
    private static final Path BASE_DIR = Path.of("project", "root");
    private static final Path WORK_DIR = Path.of("project", "root", ".sonar");
    private static final String TOKEN = "squ_secret_token";

    private static SonarConnection connection()
    {
        return SonarConnection.of("https://sonar.example.com", TOKEN, 30);
    }

    @Test
    public void assemblesFullCommandWithBranch()
    {
        ScannerLaunch launch = ScannerCommandBuilder.build(SCANNER, connection(), "my.project",
            "release/1.0", BASE_DIR, "src", WORK_DIR, null);

        List<String> command = launch.command();
        assertEquals(String.valueOf(SCANNER), command.get(0));
        assertTrue(command.contains("-Dsonar.host.url=https://sonar.example.com"));
        assertTrue(command.contains("-Dsonar.projectKey=my.project"));
        assertTrue(command.contains("-Dsonar.sources=src"));
        assertTrue(command.contains("-Dsonar.sourceEncoding=UTF-8"));
        assertTrue(command.contains("-Dsonar.working.directory=" + WORK_DIR));
        assertTrue(command.contains("-Dsonar.branch.name=release/1.0"));
    }

    @Test
    public void tokenLivesOnlyInEnvironment()
    {
        ScannerLaunch launch = ScannerCommandBuilder.build(SCANNER, connection(), "my.project",
            null, BASE_DIR, "src", WORK_DIR, null);

        for (String argument : launch.command())
        {
            assertFalse("token must not leak into the command line", argument.contains(TOKEN));
        }
        Map<String, String> environment = launch.environment();
        assertEquals(TOKEN, environment.get("SONAR_TOKEN"));
        assertEquals(System.getProperty("java.home"), environment.get("JAVA_HOME"));
    }

    @Test
    public void directoryIsBaseDir()
    {
        ScannerLaunch launch = ScannerCommandBuilder.build(SCANNER, connection(), "my.project",
            null, BASE_DIR, "src", WORK_DIR, null);
        assertEquals(BASE_DIR, launch.directory());
    }

    @Test
    public void branchOmittedWhenNull()
    {
        ScannerLaunch launch = ScannerCommandBuilder.build(SCANNER, connection(), "my.project",
            null, BASE_DIR, "src", WORK_DIR, null);
        for (String argument : launch.command())
        {
            assertFalse(argument.startsWith("-Dsonar.branch.name="));
        }
    }

    @Test
    public void branchOmittedWhenEmpty()
    {
        ScannerLaunch launch = ScannerCommandBuilder.build(SCANNER, connection(), "my.project",
            "", BASE_DIR, "src", WORK_DIR, null);
        for (String argument : launch.command())
        {
            assertFalse(argument.startsWith("-Dsonar.branch.name="));
        }
    }

    @Test
    public void emptyExtraArgsAddNothing()
    {
        ScannerLaunch nullArgs = ScannerCommandBuilder.build(SCANNER, connection(), "my.project",
            null, BASE_DIR, "src", WORK_DIR, null);
        ScannerLaunch emptyArgs = ScannerCommandBuilder.build(SCANNER, connection(), "my.project",
            null, BASE_DIR, "src", WORK_DIR, "   ");
        // executable + 5 fixed properties, no branch, no extras
        assertEquals(6, nullArgs.command().size());
        assertEquals(6, emptyArgs.command().size());
    }

    @Test
    public void quotedExtraArgsSplitIntoTwoTokens()
    {
        ScannerLaunch launch = ScannerCommandBuilder.build(SCANNER, connection(), "my.project",
            null, BASE_DIR, "src", WORK_DIR, "-Dsonar.exclusions=\"a b\" -X");

        List<String> command = launch.command();
        int size = command.size();
        assertEquals("-Dsonar.exclusions=a b", command.get(size - 2));
        assertEquals("-X", command.get(size - 1));
    }

    @Test
    public void plainExtraArgsSplitOnWhitespace()
    {
        ScannerLaunch launch = ScannerCommandBuilder.build(SCANNER, connection(), "my.project",
            null, BASE_DIR, "src", WORK_DIR, "-X  -Dsonar.verbose=true");

        List<String> command = launch.command();
        int size = command.size();
        assertEquals("-X", command.get(size - 2));
        assertEquals("-Dsonar.verbose=true", command.get(size - 1));
    }
}
