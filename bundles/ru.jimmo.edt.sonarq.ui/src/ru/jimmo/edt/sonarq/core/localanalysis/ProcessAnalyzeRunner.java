/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;

/**
 * Runs the BSL Language Server native launcher as an external process.
 *
 * <p>The launcher is invoked directly through {@link ProcessBuilder} (no shell, no {@code -jar}
 * indirection; see {@link BslAnalyzeCommand}). Standard output and error are merged
 * ({@link ProcessBuilder#redirectErrorStream(boolean)}) and pumped to {@code <outputDir>/analyze.log} on
 * a daemon thread, while the calling thread polls {@code process.waitFor} with a short timeout so it can
 * check the monitor for cancellation in between. A cancelled monitor destroys the process and raises
 * {@link OperationCanceledException}; a non-zero exit raises an {@link IOException} carrying the tail of
 * the log.
 *
 * <p>This class was verified against a live run of the native launcher (task L2 smoke run against a real
 * 1C source tree, which confirmed the fixed {@code bsl-ls.sarif} output file name used by
 * {@link BslAnalyzeCommand}). It has no headless unit test of its own, since that would require starting
 * a real OS process; only its command construction is unit-tested, in {@code BslAnalyzeCommandTest}.
 */
public final class ProcessAnalyzeRunner implements AnalyzeRunner
{
    private static final String LOG_FILE_NAME = "analyze.log"; //$NON-NLS-1$
    private static final String SARIF_FILE_NAME = "bsl-ls.sarif"; //$NON-NLS-1$
    private static final String PUMP_THREAD_NAME = "sonarq-bsl-ls-output"; //$NON-NLS-1$
    private static final String EMPTY = ""; //$NON-NLS-1$

    private static final long POLL_MILLIS = 500L;
    private static final long PUMP_JOIN_MILLIS = 2000L;
    private static final int CHUNK_SIZE = 8192;
    private static final int LOG_TAIL_LINES = 20;

    @Override
    public Path analyze(Path serverExecutable, Path srcDir, Path outputDir, IProgressMonitor monitor)
        throws IOException, InterruptedException
    {
        Files.createDirectories(outputDir);
        List<String> command = BslAnalyzeCommand.build(serverExecutable, srcDir, outputDir);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Path logFile = outputDir.resolve(LOG_FILE_NAME);

        Process process = builder.start();
        Thread pump = new Thread(() -> pumpToFile(process, logFile), PUMP_THREAD_NAME);
        pump.setDaemon(true);
        pump.start();

        while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS))
        {
            if (monitor != null && monitor.isCanceled())
            {
                process.destroy();
                process.waitFor();
                join(pump);
                throw new OperationCanceledException();
            }
        }
        join(pump);

        int exit = process.exitValue();
        if (exit != 0)
        {
            throw new IOException("BSL Language Server analysis exited with code " //$NON-NLS-1$
                + exit + ":" + System.lineSeparator() + tailLog(logFile)); //$NON-NLS-1$
        }
        return outputDir.resolve(SARIF_FILE_NAME);
    }

    /**
     * Pumps the process's merged output stream to the log file in fixed-size chunks until the stream
     * ends.
     *
     * @param process the running process, not {@code null}
     * @param logFile the file to write the merged output to, not {@code null}
     */
    private static void pumpToFile(Process process, Path logFile)
    {
        try (InputStream in = process.getInputStream();
            OutputStream out = Files.newOutputStream(logFile))
        {
            byte[] buffer = new byte[CHUNK_SIZE];
            int read = in.read(buffer);
            while (read >= 0)
            {
                out.write(buffer, 0, read);
                read = in.read(buffer);
            }
        }
        catch (IOException e)
        {
            // The stream closes when the process is destroyed; nothing actionable to report here.
        }
    }

    /**
     * Reads the last {@value #LOG_TAIL_LINES} lines of the log file, for inclusion in a failure message.
     *
     * @param logFile the log file, not {@code null}
     * @return the tail of the log, or an empty string if it cannot be read
     */
    private static String tailLog(Path logFile)
    {
        try
        {
            List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - LOG_TAIL_LINES);
            return String.join(System.lineSeparator(), lines.subList(from, lines.size()));
        }
        catch (IOException e)
        {
            return EMPTY;
        }
    }

    /**
     * Joins the output-pump thread, restoring the interrupt flag if interrupted while waiting.
     *
     * @param thread the pump thread, not {@code null}
     */
    private static void join(Thread thread)
    {
        try
        {
            thread.join(PUMP_JOIN_MILLIS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }
}
