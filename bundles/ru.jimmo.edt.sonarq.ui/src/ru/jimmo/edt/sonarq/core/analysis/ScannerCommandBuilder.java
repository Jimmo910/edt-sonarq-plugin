/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.analysis;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ru.jimmo.edt.sonarq.core.client.SonarConnection;

/** Assembles the SonarScanner CLI command line and environment for an analysis run. */
public final class ScannerCommandBuilder
{
    private static final char QUOTE = '"';

    private ScannerCommandBuilder()
    {
    }

    /**
     * Builds the scanner invocation.
     *
     * <p>The authentication token is passed only through the {@code SONAR_TOKEN} environment
     * variable and never appears on the command line.
     *
     * @param scannerExecutable the scanner executable, not {@code null}
     * @param connection the server connection providing host URL and token, not {@code null}
     * @param projectKey the SonarQube project key, not {@code null}
     * @param branch the branch name, or {@code null}/empty to omit the branch property
     * @param baseDir the working directory to run the scanner in, not {@code null}
     * @param sourcesPath the {@code sonar.sources} value relative to {@code baseDir}, not {@code null}
     * @param workDir the scanner working directory, not {@code null}
     * @param extraArgs additional arguments split on whitespace honoring double quotes, may be {@code null}
     * @return the assembled launch, never {@code null}
     */
    public static ScannerLaunch build(Path scannerExecutable, SonarConnection connection, String projectKey,
        String branch, Path baseDir, String sourcesPath, Path workDir, String extraArgs)
    {
        List<String> command = new ArrayList<>();
        command.add(String.valueOf(scannerExecutable));
        command.add("-Dsonar.host.url=" + connection.baseUrl()); //$NON-NLS-1$
        command.add("-Dsonar.projectKey=" + projectKey); //$NON-NLS-1$
        command.add("-Dsonar.sources=" + sourcesPath); //$NON-NLS-1$
        command.add("-Dsonar.sourceEncoding=UTF-8"); //$NON-NLS-1$
        command.add("-Dsonar.working.directory=" + workDir); //$NON-NLS-1$
        if (branch != null && !branch.isEmpty())
        {
            command.add("-Dsonar.branch.name=" + branch); //$NON-NLS-1$
        }
        command.addAll(splitArguments(extraArgs));

        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("SONAR_TOKEN", connection.token()); //$NON-NLS-1$
        environment.put("JAVA_HOME", System.getProperty("java.home")); //$NON-NLS-1$ //$NON-NLS-2$

        return new ScannerLaunch(command, environment, baseDir);
    }

    /**
     * Splits a raw argument string into tokens on whitespace, treating double-quoted spans as literal.
     *
     * <p>Surrounding double quotes are removed; whitespace inside quotes is preserved. A {@code null}
     * or blank input yields an empty list.
     *
     * @param extraArgs the raw arguments, may be {@code null}
     * @return the parsed tokens, never {@code null}
     */
    private static List<String> splitArguments(String extraArgs)
    {
        List<String> tokens = new ArrayList<>();
        if (extraArgs == null || extraArgs.isEmpty())
        {
            return tokens;
        }
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean hasToken = false;
        for (int i = 0; i < extraArgs.length(); i++)
        {
            char c = extraArgs.charAt(i);
            if (c == QUOTE)
            {
                inQuotes = !inQuotes;
                hasToken = true;
            }
            else if (Character.isWhitespace(c) && !inQuotes)
            {
                if (hasToken)
                {
                    tokens.add(current.toString());
                    current.setLength(0);
                    hasToken = false;
                }
            }
            else
            {
                current.append(c);
                hasToken = true;
            }
        }
        if (hasToken)
        {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
