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
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;

import ru.jimmo.edt.sonarq.core.analysis.Processes;

/**
 * Runs the BSL Language Server native launcher as an external process.
 *
 * <p>The launcher is invoked directly through {@link ProcessBuilder} (no shell, no {@code -jar}
 * indirection; see {@link BslAnalyzeCommand}). Standard output and error are merged
 * ({@link ProcessBuilder#redirectErrorStream(boolean)}) and pumped to {@code <outputDir>/analyze.log} on
 * a daemon thread, while the calling thread polls {@code process.waitFor} with a short timeout so it can
 * check the monitor for cancellation in between. A cancelled monitor destroys the process and raises
 * {@link OperationCanceledException}; a non-zero exit raises an {@link IOException} carrying the absolute
 * path to the full log file and the tail of the log (BSL LS itself does not name the offending module, so
 * pointing at the full log is the best we can do). The wait is also wrapped so that an
 * {@link InterruptedException} on the calling thread (e.g.
 * an {@code Eclipse Job} being cancelled from outside the monitor) destroys the process the same way
 * before the exception is rethrown — the process must never be left running just because the thread
 * that was waiting on it gave up.
 *
 * <p>This class was verified against a live run of the native launcher (task L2 smoke run against a real
 * 1C source tree, which confirmed the fixed {@code bsl-ls.sarif} output file name used by
 * {@link BslAnalyzeCommand}). Spawning the real native launcher in a headless unit test is still out of
 * scope, but the interrupted-thread teardown path is covered by a headless test that runs a throwaway
 * long-lived script in its place (see {@code ProcessAnalyzeRunnerTest}); command construction alone is
 * covered by {@code BslAnalyzeCommandTest}.
 *
 * <p>The child process's working directory is pinned to the analyzed source directory (see
 * {@link #analyze}) because the BSL Language Server relativizes each analyzed file's path against the
 * process's current working directory; if that directory sits on a different drive than the sources
 * (Windows only), {@code Path.relativize} throws {@code IllegalArgumentException: 'other' has different
 * root}, surfacing as {@code IllegalStateException: Error analyzing files ...}. This was only masked so
 * far because the test stand and its workspace happen to share the same drive letter. Verified live
 * 2026-07-17: the exact launcher invocation crashes when the process cwd is on {@code C:} while the
 * source tree is on {@code E:}, and succeeds once the cwd is moved onto the source tree's drive.
 *
 * <p>The child process's JVM max heap is capped two ways, belt-and-suspenders: primarily through the
 * {@code _JAVA_OPTIONS} environment variable (see {@link #analyze} and {@link #mergeJavaOptions}), which
 * every standard JVM launcher honors regardless of the jpackage app-image layout, and as a fallback
 * through {@link BslServerInstaller#configureHeap} rewriting the launcher's {@code .cfg} file (invoked by
 * {@link LocalIssueProvider#configureHeapBestEffort}) before this class is ever called. Both are set to
 * the same value; the environment variable was verified live against the real 1.0.4 native build
 * (2026-07-19): {@code _JAVA_OPTIONS=-Xmx16m} makes the JVM print {@code Picked up _JAVA_OPTIONS: -Xmx16m}
 * to stdout and the analysis then fails with an {@link OutOfMemoryError}, confirming it overrides the
 * {@code .cfg} file's {@code java-options=-Xmx4g}.
 */
public final class ProcessAnalyzeRunner implements AnalyzeRunner
{
    private static final String LOG_FILE_NAME = "analyze.log"; //$NON-NLS-1$
    private static final String SARIF_FILE_NAME = "bsl-ls.sarif"; //$NON-NLS-1$
    private static final String PUMP_THREAD_NAME = "sonarq-bsl-ls-output"; //$NON-NLS-1$
    private static final String EMPTY = ""; //$NON-NLS-1$
    private static final String OUT_OF_MEMORY_MARKER = "OutOfMemoryError"; //$NON-NLS-1$
    private static final String OUT_OF_MEMORY_HINT =
        "BSL Language Server ran out of memory. Increase 'BSL LS max heap' in Settings -> SonarQube, " //$NON-NLS-1$
            + "then retry."; //$NON-NLS-1$
    private static final String JAVA_OPTIONS_ENV_VAR = "_JAVA_OPTIONS"; //$NON-NLS-1$
    private static final String XMX_FLAG_PREFIX = "-Xmx"; //$NON-NLS-1$
    private static final String HEAP_UNIT_SUFFIX = "g"; //$NON-NLS-1$

    private static final long POLL_MILLIS = 500L;
    private static final long PUMP_JOIN_MILLIS = 2000L;
    private static final int CHUNK_SIZE = 8192;
    private static final int LOG_TAIL_LINES = 20;

    /** The JVM max heap used when this runner is built with the no-arg constructor. */
    private static final int DEFAULT_MAX_HEAP_GB = 4;

    /** The floor {@link #maxHeapGb} is clamped to, mirroring {@code BslServerInstaller#MIN_HEAP_GB}. */
    private static final int MIN_HEAP_GB = 1;

    private final int maxHeapGb;

    /**
     * Creates a runner that caps the analysis process's JVM heap at {@link #DEFAULT_MAX_HEAP_GB} gigabytes
     * via {@code _JAVA_OPTIONS} (see {@link #analyze}).
     */
    public ProcessAnalyzeRunner()
    {
        this(DEFAULT_MAX_HEAP_GB);
    }

    /**
     * Creates a runner that caps the analysis process's JVM heap at {@code maxHeapGb} gigabytes via
     * {@code _JAVA_OPTIONS} (see {@link #analyze}).
     *
     * @param maxHeapGb the desired maximum heap, in gigabytes; clamped up to {@link #MIN_HEAP_GB} if lower
     */
    public ProcessAnalyzeRunner(int maxHeapGb)
    {
        this.maxHeapGb = Math.max(MIN_HEAP_GB, maxHeapGb);
    }

    @Override
    public Path analyze(Path serverExecutable, Path srcDir, Path outputDir, Path configPath,
        IProgressMonitor monitor) throws IOException, InterruptedException
    {
        Files.createDirectories(outputDir);
        List<String> command = BslAnalyzeCommand.build(serverExecutable, srcDir, outputDir, configPath);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(srcDir.toFile());
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put(JAVA_OPTIONS_ENV_VAR, mergeJavaOptions(env.get(JAVA_OPTIONS_ENV_VAR), maxHeapGb));
        Path logFile = outputDir.resolve(LOG_FILE_NAME);

        Process process = builder.start();
        Thread pump = new Thread(() -> pumpToFile(process, logFile), PUMP_THREAD_NAME);
        pump.setDaemon(true);
        pump.start();

        try
        {
            while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS))
            {
                if (monitor != null && monitor.isCanceled())
                {
                    Processes.terminate(process);
                    join(pump);
                    throw new OperationCanceledException();
                }
            }
        }
        catch (InterruptedException e)
        {
            // The calling thread gave up waiting; the process must not be left running behind it.
            Processes.terminate(process);
            join(pump);
            throw e;
        }
        join(pump);

        int exit = process.exitValue();
        if (exit != 0)
        {
            String hint = logContainsOutOfMemory(logFile)
                ? OUT_OF_MEMORY_HINT + System.lineSeparator()
                : EMPTY;
            throw new IOException(hint + "The BSL Language Server exited with code " + exit //$NON-NLS-1$
                + " while analyzing the sources (this usually means it failed to parse a module)." //$NON-NLS-1$
                + System.lineSeparator() + "Full log: " + logFile.toAbsolutePath() //$NON-NLS-1$
                + System.lineSeparator() + tailLog(logFile));
        }
        return outputDir.resolve(SARIF_FILE_NAME);
    }

    /**
     * Builds the {@code _JAVA_OPTIONS} value to set on the analysis process's environment, appending our
     * heap cap to whatever the user (or the calling process's own environment) already set there.
     *
     * <p>Appending, rather than replacing, preserves any user-set {@code _JAVA_OPTIONS} the child process
     * would otherwise have inherited; putting our {@code -Xmx} flag last means it wins, since the JVM
     * applies later {@code -Xmx} occurrences over earlier ones. This variable takes priority over the
     * launcher's own {@code .cfg} file heap setting - verified live against the real 1.0.4 native build
     * (see the class javadoc) - which is why {@link #analyze} sets it in addition to, not instead of,
     * {@link BslServerInstaller#configureHeap}.
     *
     * @param existing the analysis process's inherited {@code _JAVA_OPTIONS} value, or {@code null}/blank
     *     if unset
     * @param maxHeapGb the desired maximum heap, in gigabytes; clamped up to {@link #MIN_HEAP_GB} if lower
     * @return the {@code _JAVA_OPTIONS} value to set, never {@code null}
     */
    static String mergeJavaOptions(String existing, int maxHeapGb)
    {
        String xmxFlag = XMX_FLAG_PREFIX + Math.max(MIN_HEAP_GB, maxHeapGb) + HEAP_UNIT_SUFFIX;
        if (existing == null || existing.isBlank())
        {
            return xmxFlag;
        }
        return existing.strip() + ' ' + xmxFlag;
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
     * Tells whether the log file's content mentions an {@code OutOfMemoryError}, so a non-zero exit caused
     * by the bundled BSL Language Server running out of its configurable heap limit - set via
     * {@code _JAVA_OPTIONS} (see {@link #analyze} and {@link #mergeJavaOptions}) and, as a fallback,
     * {@code BslServerInstaller#configureHeap} - can be reported with an actionable hint instead of a bare
     * exit code - the language server itself gives no other indication of the cause.
     *
     * @param logFile the merged output log file, not {@code null}
     * @return {@code true} if the log file could be read and its content contains the marker
     */
    private static boolean logContainsOutOfMemory(Path logFile)
    {
        try
        {
            return Files.readString(logFile, StandardCharsets.UTF_8).contains(OUT_OF_MEMORY_MARKER);
        }
        catch (IOException e)
        {
            return false;
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
