/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.analysis;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Waits for a short-lived process to exit while draining its merged output, and returns that output.
 *
 * <p>Draining has to happen <em>while</em> the process runs, not after it exits: the operating system gives
 * a pipe a fixed buffer (typically 4-64 KB), and a child that fills it blocks on its next write until
 * somebody reads. Waiting first and only then calling {@code readAllBytes()} therefore deadlocks against any
 * program that prints more than that buffer holds, and the wait's own timeout turns the deadlock into a
 * misleading "timed out" report (review minor M6). The long-running analyzer paths already pump concurrently
 * on a daemon thread ({@code ProcessAnalyzeRunner}, {@code AnalysisJob}); this class is the same technique
 * for the short probes whose output is wanted in memory.
 *
 * <p>The captured text is capped at {@value #MAX_CAPTURED_BYTES} bytes, but the stream keeps being drained
 * past the cap - so a chatty program is truncated rather than either blocked or allowed to exhaust the heap.
 */
public final class ProcessOutput
{
    /** The maximum number of bytes kept from the process output; the rest is drained and discarded. */
    private static final int MAX_CAPTURED_BYTES = 64 * 1024;

    private static final String PUMP_THREAD_NAME = "sonarq-process-probe"; //$NON-NLS-1$
    private static final long PUMP_JOIN_MILLIS = 2000L;
    private static final int CHUNK_SIZE = 8192;

    private ProcessOutput()
    {
    }

    /**
     * Waits for the process to exit, consuming its merged output stream concurrently, and returns what it
     * printed. A process that outlives the timeout is terminated (with its descendants, see
     * {@link Processes#terminate}) and reported as empty.
     *
     * <p>The process must have been started with {@link ProcessBuilder#redirectErrorStream(boolean)} set, so
     * that its standard error cannot fill a second, unread pipe.
     *
     * @param process the running process, not {@code null}
     * @param timeout how long to wait for the process to exit, not {@code null}
     * @param charset the charset to decode the output with, not {@code null}
     * @return the captured output, or empty when the process did not exit within {@code timeout}
     * @throws InterruptedException if the calling thread is interrupted while waiting; the process is
     *     terminated first, so it is never left running behind the thread that gave up on it
     */
    public static Optional<String> awaitMergedOutput(Process process, Duration timeout, Charset charset)
        throws InterruptedException
    {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        Thread pump = new Thread(() -> drain(process.getInputStream(), sink), PUMP_THREAD_NAME);
        pump.setDaemon(true);
        pump.start();
        boolean exited;
        try
        {
            exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e)
        {
            Processes.terminate(process);
            join(pump);
            throw e;
        }
        if (!exited)
        {
            Processes.terminate(process);
            join(pump);
            return Optional.empty();
        }
        join(pump);
        return Optional.of(sink.toString(charset));
    }

    /**
     * Reads the stream to its end, keeping at most {@value #MAX_CAPTURED_BYTES} bytes.
     *
     * @param in the process output stream, not {@code null}
     * @param sink the buffer to fill, not {@code null}
     */
    private static void drain(InputStream in, ByteArrayOutputStream sink)
    {
        try (InputStream stream = in)
        {
            byte[] buffer = new byte[CHUNK_SIZE];
            int read = stream.read(buffer);
            while (read >= 0)
            {
                int free = MAX_CAPTURED_BYTES - sink.size();
                if (free > 0)
                {
                    sink.write(buffer, 0, Math.min(read, free));
                }
                read = stream.read(buffer);
            }
        }
        catch (IOException e)
        {
            // The stream closes when the process is destroyed; nothing actionable to report here.
        }
    }

    /**
     * Joins the drain thread, restoring the interrupt flag if interrupted while waiting.
     *
     * @param thread the drain thread, not {@code null}
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
