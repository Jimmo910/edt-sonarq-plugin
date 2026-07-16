/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.analysis;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A fully assembled SonarScanner CLI invocation.
 *
 * @param command the command line starting with the scanner executable, never {@code null}
 * @param environment extra environment variables for the process, never {@code null}
 * @param directory the working directory to run the scanner in, never {@code null}
 */
public record ScannerLaunch(List<String> command, Map<String, String> environment, Path directory)
{
}
