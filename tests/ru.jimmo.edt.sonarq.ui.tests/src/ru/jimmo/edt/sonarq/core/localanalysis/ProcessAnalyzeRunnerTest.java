/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.Test;

/**
 * Regression test for the process-leak fix in {@link ProcessAnalyzeRunner}: interrupting the calling
 * thread while it waits for the analysis process must destroy that process, not merely let the
 * {@link InterruptedException} propagate while the process keeps running unattended.
 *
 * <p>The test spawns a real, long-lived throwaway "server executable" (a shell script that sleeps,
 * ignoring the fixed {@code --analyze --srcDir ... --reporter sarif --outputDir ...} arguments
 * {@link BslAnalyzeCommand} always appends) so there is a genuine OS process to observe. It identifies
 * that process not by its command line — on this JDK/Windows combination {@code ProcessHandle.Info}
 * does not expose the command line even for one's own child process, which an earlier version of this
 * test discovered the hard way — but by diffing {@code ProcessHandle.current().children()} before and
 * after starting the analysis, which needs no such access and is exactly the direct child
 * {@link ProcessAnalyzeRunner} tracks and calls {@code destroy()} on. Grandchildren the script's own
 * interpreter may spawn (e.g. on Windows the batch file runs {@code ping} as a sleep substitute, which
 * becomes a grandchild, not a child, of the tracked process) are deliberately not tracked: killing that
 * kind of descendant is not something {@code ProcessAnalyzeRunner} claims to do for the real,
 * single-process native launcher it targets in production.
 */
public class ProcessAnalyzeRunnerTest
{
    private static final long POLL_STEP_MILLIS = 100L;
    private static final long START_TIMEOUT_MILLIS = 3000L;
    private static final long DEATH_TIMEOUT_MILLIS = 5000L;
    private static final long JOIN_TIMEOUT_MILLIS = 5000L;

    private Path scratchDir;

    @After
    public void tearDown() throws IOException
    {
        if (scratchDir == null)
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(scratchDir))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static boolean isWindows()
    {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Writes a throwaway "server executable" that sleeps for a long time, ignoring any arguments, so it
     * can stand in for the real native launcher behind {@code BslAnalyzeCommand}'s fixed invocation.
     *
     * @param dir the directory to write the script into, not {@code null}
     * @return the script path, never {@code null}
     * @throws IOException if the script cannot be written
     */
    private static Path writeSleeperScript(Path dir) throws IOException
    {
        if (isWindows())
        {
            Path script = dir.resolve("sonarq-sleeper.bat");
            Files.writeString(script, "@echo off\r\nping -n 120 127.0.0.1 >nul\r\n");
            return script;
        }
        Path script = dir.resolve("sonarq-sleeper.sh");
        Files.writeString(script, "#!/bin/sh\nsleep 120\n");
        script.toFile().setExecutable(true);
        return script;
    }

    /**
     * Snapshots the process ids of the current JVM's direct child processes.
     *
     * @return the child process ids, never {@code null}
     */
    private static Set<Long> currentChildPids()
    {
        return ProcessHandle.current().children().map(ProcessHandle::pid).collect(Collectors.toSet());
    }

    /**
     * Tells whether the process with the given id is still alive.
     *
     * @param pid the process id
     * @return {@code true} if a live process with that id exists
     */
    private static boolean isAlive(long pid)
    {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(Boolean.FALSE).booleanValue();
    }

    /**
     * Polls for a new direct child process (one absent from {@code before}), failing the test if none
     * appears within the timeout.
     *
     * @param before the child pids snapshotted before starting the analysis, not {@code null}
     * @param timeoutMillis the maximum time to wait, in milliseconds
     * @return the new child's process id
     * @throws InterruptedException if the polling thread is interrupted while waiting
     */
    private static long waitForNewChild(Set<Long> before, long timeoutMillis) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline)
        {
            Optional<Long> found = currentChildPids().stream().filter(pid -> !before.contains(pid)).findFirst();
            if (found.isPresent())
            {
                return found.get().longValue();
            }
            Thread.sleep(POLL_STEP_MILLIS);
        }
        throw new AssertionError("sleeper process never started");
    }

    @Test
    public void interruptingCallingThreadDestroysTheAnalysisProcess() throws Exception
    {
        scratchDir = Files.createTempDirectory("sonarq-process-analyze-runner-test");
        Path script = writeSleeperScript(scratchDir);
        Path srcDir = scratchDir.resolve("src");
        Files.createDirectories(srcDir);
        Path outputDir = scratchDir.resolve("out");
        Files.createDirectories(outputDir);

        Set<Long> childrenBefore = currentChildPids();
        ProcessAnalyzeRunner runner = new ProcessAnalyzeRunner();
        AtomicReference<Throwable> captured = new AtomicReference<>();
        Thread analyzeThread = new Thread(() ->
        {
            try
            {
                runner.analyze(script, srcDir, outputDir, new NullProgressMonitor());
            }
            catch (Throwable t)
            {
                captured.set(t);
            }
        }, "sonarq-test-analyze");
        analyzeThread.start();

        long childPid = waitForNewChild(childrenBefore, START_TIMEOUT_MILLIS);

        analyzeThread.interrupt();
        analyzeThread.join(JOIN_TIMEOUT_MILLIS);
        assertFalse("analyze() must not hang after the calling thread is interrupted", analyzeThread.isAlive());
        assertTrue("expected InterruptedException to surface, got " + captured.get(),
            captured.get() instanceof InterruptedException);

        waitUntil(() -> !isAlive(childPid), DEATH_TIMEOUT_MILLIS,
            "analysis process (pid " + childPid + ") was not destroyed after the calling thread was interrupted");
    }

    /**
     * Polls a condition until it holds or a timeout elapses, failing the test if it never does.
     *
     * @param condition the condition to poll, not {@code null}
     * @param timeoutMillis the maximum time to wait, in milliseconds
     * @param failureMessage the assertion failure message if the condition never holds, not {@code null}
     * @throws InterruptedException if the polling thread is interrupted while waiting
     */
    private static void waitUntil(BooleanSupplier condition, long timeoutMillis, String failureMessage)
        throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline)
        {
            if (condition.getAsBoolean())
            {
                return;
            }
            Thread.sleep(POLL_STEP_MILLIS);
        }
        assertTrue(failureMessage, condition.getAsBoolean());
    }
}
