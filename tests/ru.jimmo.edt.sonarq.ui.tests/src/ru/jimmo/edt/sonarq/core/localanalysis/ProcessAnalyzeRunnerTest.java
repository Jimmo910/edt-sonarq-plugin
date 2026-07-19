/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
     * Writes a throwaway "server executable" that prints its working directory to stdout and exits
     * immediately, ignoring any arguments, so the child process's actual working directory can be
     * observed without spawning the real native launcher.
     *
     * @param dir the directory to write the script into, not {@code null}
     * @return the script path, never {@code null}
     * @throws IOException if the script cannot be written
     */
    private static Path writePrintCwdScript(Path dir) throws IOException
    {
        if (isWindows())
        {
            Path script = dir.resolve("sonarq-print-cwd.bat");
            Files.writeString(script, "@echo off\r\necho %CD%\r\n");
            return script;
        }
        Path script = dir.resolve("sonarq-print-cwd.sh");
        Files.writeString(script, "#!/bin/sh\npwd\n");
        script.toFile().setExecutable(true);
        return script;
    }

    /**
     * Writes a throwaway "server executable" that prints its received {@code _JAVA_OPTIONS} environment
     * variable to stdout and exits immediately, ignoring any arguments, so the environment variable
     * {@link ProcessAnalyzeRunner#analyze} sets on the child process can be observed without spawning the
     * real native launcher.
     *
     * @param dir the directory to write the script into, not {@code null}
     * @return the script path, never {@code null}
     * @throws IOException if the script cannot be written
     */
    private static Path writePrintJavaOptionsScript(Path dir) throws IOException
    {
        if (isWindows())
        {
            Path script = dir.resolve("sonarq-print-java-options.bat");
            Files.writeString(script, "@echo off\r\necho %_JAVA_OPTIONS%\r\n");
            return script;
        }
        Path script = dir.resolve("sonarq-print-java-options.sh");
        Files.writeString(script, "#!/bin/sh\necho \"$_JAVA_OPTIONS\"\n");
        script.toFile().setExecutable(true);
        return script;
    }

    /**
     * Writes a throwaway "server executable" that prints a known line to stdout and exits with a
     * non-zero code, ignoring any arguments, so the failure-message wiring in {@link ProcessAnalyzeRunner}
     * can be exercised without spawning the real native launcher.
     *
     * @param dir the directory to write the script into, not {@code null}
     * @param knownLine the line the script prints before exiting, not {@code null}
     * @return the script path, never {@code null}
     * @throws IOException if the script cannot be written
     */
    private static Path writeFailingScript(Path dir, String knownLine) throws IOException
    {
        if (isWindows())
        {
            Path script = dir.resolve("sonarq-failer.bat");
            Files.writeString(script, "@echo off\r\necho " + knownLine + "\r\nexit /b 1\r\n");
            return script;
        }
        Path script = dir.resolve("sonarq-failer.sh");
        Files.writeString(script, "#!/bin/sh\necho " + knownLine + "\nexit 1\n");
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
                runner.analyze(script, srcDir, outputDir, null, new NullProgressMonitor());
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
     * Regression test for the drive-mismatch crash fixed alongside this test: the analysis process's
     * working directory must be pinned to {@code srcDir}, not inherited from the calling process, because
     * the BSL Language Server relativizes analyzed file paths against its working directory and throws
     * when that directory sits on a different drive than the sources being analyzed (Windows-only
     * failure mode; verified live 2026-07-17).
     */
    @Test
    public void analyzeSetsTheProcessWorkingDirectoryToSrcDir() throws Exception
    {
        scratchDir = Files.createTempDirectory("sonarq-process-analyze-runner-cwd-test");
        Path script = writePrintCwdScript(scratchDir);
        Path srcDir = scratchDir.resolve("src");
        Files.createDirectories(srcDir);
        Path outputDir = scratchDir.resolve("out");
        Files.createDirectories(outputDir);

        ProcessAnalyzeRunner runner = new ProcessAnalyzeRunner();
        runner.analyze(script, srcDir, outputDir, null, new NullProgressMonitor());

        Path logFile = outputDir.resolve("analyze.log");
        String logged = Files.readString(logFile).trim();
        assertEquals(srcDir.toRealPath(), Path.of(logged).toRealPath());
    }

    /**
     * Regression test for the {@code _JAVA_OPTIONS} heap cap (belt-and-suspenders alongside
     * {@code BslServerInstaller#configureHeap}, verified live against the real 1.0.4 native build): the
     * analysis process must actually receive a {@code _JAVA_OPTIONS} environment variable ending in the
     * requested {@code -Xmx<N>g} flag.
     */
    @Test
    public void analyzeSetsJavaOptionsEnvironmentVariableWithRequestedHeap() throws Exception
    {
        scratchDir = Files.createTempDirectory("sonarq-process-analyze-runner-java-options-test");
        Path script = writePrintJavaOptionsScript(scratchDir);
        Path srcDir = scratchDir.resolve("src");
        Files.createDirectories(srcDir);
        Path outputDir = scratchDir.resolve("out");
        Files.createDirectories(outputDir);

        ProcessAnalyzeRunner runner = new ProcessAnalyzeRunner(7);
        runner.analyze(script, srcDir, outputDir, null, new NullProgressMonitor());

        Path logFile = outputDir.resolve("analyze.log");
        String logged = Files.readString(logFile).trim();
        assertTrue("expected the child's _JAVA_OPTIONS to end with -Xmx7g, got: " + logged,
            logged.endsWith("-Xmx7g"));
    }

    /**
     * Regression test for issue #5: when the BSL Language Server exits with a non-zero code, the
     * {@link IOException} it raises must point the user at the absolute path of the full log file (in
     * addition to the existing log tail), since BSL LS itself never names the module that failed to parse.
     */
    @Test
    public void analyzeThrowsIOExceptionPointingToFullLogFileOnNonZeroExit() throws Exception
    {
        scratchDir = Files.createTempDirectory("sonarq-process-analyze-runner-exit-test");
        String knownLine = "sonarq-test-known-failure-line";
        Path script = writeFailingScript(scratchDir, knownLine);
        Path srcDir = scratchDir.resolve("src");
        Files.createDirectories(srcDir);
        Path outputDir = scratchDir.resolve("out");
        Files.createDirectories(outputDir);

        ProcessAnalyzeRunner runner = new ProcessAnalyzeRunner();
        try
        {
            runner.analyze(script, srcDir, outputDir, null, new NullProgressMonitor());
            fail("expected an IOException for a non-zero exit code");
        }
        catch (IOException e)
        {
            Path logFile = outputDir.resolve("analyze.log");
            String message = e.getMessage();
            assertTrue("expected message to contain the absolute log path, got: " + message,
                message.contains(logFile.toAbsolutePath().toString()));
            assertTrue("expected message to contain the known tail line, got: " + message,
                message.contains(knownLine));
        }
    }

    /**
     * Writes a throwaway "server executable" that prints a line containing {@code OutOfMemoryError} to
     * stdout and exits with a non-zero code, so the OOM-aware hint wiring in {@link ProcessAnalyzeRunner}
     * can be exercised without spawning the real native launcher.
     *
     * @param dir the directory to write the script into, not {@code null}
     * @return the script path, never {@code null}
     * @throws IOException if the script cannot be written
     */
    private static Path writeOutOfMemoryFailingScript(Path dir) throws IOException
    {
        String line = "java.lang.OutOfMemoryError: Java heap space";
        if (isWindows())
        {
            Path script = dir.resolve("sonarq-oom-failer.bat");
            Files.writeString(script, "@echo off\r\necho " + line + "\r\nexit /b 1\r\n");
            return script;
        }
        Path script = dir.resolve("sonarq-oom-failer.sh");
        Files.writeString(script, "#!/bin/sh\necho " + line + "\nexit 1\n");
        script.toFile().setExecutable(true);
        return script;
    }

    /**
     * Regression test for issue #5: a non-zero exit whose log mentions {@code OutOfMemoryError} must raise
     * an {@link IOException} with an actionable hint prepended, pointing the user at the configurable
     * heap setting - while still carrying the existing exit-code message and the absolute log path.
     */
    @Test
    public void analyzeThrowsActionableHintWhenLogMentionsOutOfMemoryError() throws Exception
    {
        scratchDir = Files.createTempDirectory("sonarq-process-analyze-runner-oom-test");
        Path script = writeOutOfMemoryFailingScript(scratchDir);
        Path srcDir = scratchDir.resolve("src");
        Files.createDirectories(srcDir);
        Path outputDir = scratchDir.resolve("out");
        Files.createDirectories(outputDir);

        ProcessAnalyzeRunner runner = new ProcessAnalyzeRunner();
        try
        {
            runner.analyze(script, srcDir, outputDir, null, new NullProgressMonitor());
            fail("expected an IOException for a non-zero exit code");
        }
        catch (IOException e)
        {
            Path logFile = outputDir.resolve("analyze.log");
            String message = e.getMessage();
            assertTrue("expected message to mention running out of memory, got: " + message,
                message.contains("ran out of memory"));
            assertTrue("expected message to point at Settings, got: " + message, message.contains("Settings"));
            assertTrue("expected message to still contain the absolute log path, got: " + message,
                message.contains(logFile.toAbsolutePath().toString()));
            assertTrue("expected message to still contain the exit code sentence, got: " + message,
                message.contains("exited with code"));
        }
    }

    /**
     * A {@code null} existing {@code _JAVA_OPTIONS} value must produce a bare {@code -Xmx<N>g} flag, since
     * there is nothing to append to.
     */
    @Test
    public void mergeJavaOptionsWithNullExistingReturnsBareXmxFlag()
    {
        assertEquals("-Xmx4g", ProcessAnalyzeRunner.mergeJavaOptions(null, 4));
    }

    /**
     * A blank existing {@code _JAVA_OPTIONS} value (unset or whitespace-only) must also produce a bare
     * {@code -Xmx<N>g} flag, not an appended one with leading whitespace.
     */
    @Test
    public void mergeJavaOptionsWithBlankExistingReturnsBareXmxFlag()
    {
        assertEquals("-Xmx4g", ProcessAnalyzeRunner.mergeJavaOptions("   ", 4));
    }

    /**
     * A non-blank existing {@code _JAVA_OPTIONS} value must be preserved, with our {@code -Xmx} flag
     * appended last so it wins over any {@code -Xmx} the user may have set.
     */
    @Test
    public void mergeJavaOptionsWithExistingValueAppendsXmxLast()
    {
        assertEquals("-Dx=y -Xmx6g", ProcessAnalyzeRunner.mergeJavaOptions("-Dx=y", 6));
    }

    /**
     * A requested heap of zero (or negative) must clamp up to the 1 GB floor, mirroring
     * {@code BslServerInstaller#configureHeap}'s own clamp.
     */
    @Test
    public void mergeJavaOptionsClampsNonPositiveHeapToOneGigabyte()
    {
        assertEquals("-Xmx1g", ProcessAnalyzeRunner.mergeJavaOptions(null, 0));
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
