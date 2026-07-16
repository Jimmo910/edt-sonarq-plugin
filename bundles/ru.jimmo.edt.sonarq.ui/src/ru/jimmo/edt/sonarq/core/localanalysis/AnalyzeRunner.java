/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import java.io.IOException;
import java.nio.file.Path;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;

/**
 * Runs a headless BSL Language Server analysis and returns the path to the produced SARIF report.
 *
 * <p>The production implementation is {@link ProcessAnalyzeRunner}, which spawns the native launcher as
 * an external process; tests substitute a fake that writes a fixture report directly.
 */
@FunctionalInterface
public interface AnalyzeRunner
{
    /**
     * Analyzes {@code srcDir} with {@code serverExecutable} and writes a SARIF report into
     * {@code outputDir}.
     *
     * @param serverExecutable the BSL Language Server native launcher, not {@code null}
     * @param srcDir the source directory to analyze, not {@code null}
     * @param outputDir the directory to write the report into, not {@code null}
     * @param monitor the progress monitor checked for cancellation, or {@code null}
     * @return the path to the produced SARIF file, never {@code null}
     * @throws IOException if the analysis cannot be run or fails
     * @throws InterruptedException if the calling thread is interrupted while waiting for the analysis
     * @throws OperationCanceledException if the monitor is cancelled while waiting for the analysis
     */
    Path analyze(Path serverExecutable, Path srcDir, Path outputDir, IProgressMonitor monitor)
        throws IOException, InterruptedException;
}
