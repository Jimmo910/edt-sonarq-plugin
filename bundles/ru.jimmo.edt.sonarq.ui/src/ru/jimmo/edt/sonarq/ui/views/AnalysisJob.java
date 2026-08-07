/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntFunction;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.osgi.util.NLS;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;

import ru.jimmo.edt.sonarq.core.analysis.AnalysisLaunchMode;
import ru.jimmo.edt.sonarq.core.analysis.CiTriggerClient;
import ru.jimmo.edt.sonarq.core.analysis.ICiTrigger;
import ru.jimmo.edt.sonarq.core.analysis.ReportTaskParser;
import ru.jimmo.edt.sonarq.core.analysis.ScannerCommandBuilder;
import ru.jimmo.edt.sonarq.core.analysis.ScannerInstaller;
import ru.jimmo.edt.sonarq.core.analysis.ScannerLaunch;
import ru.jimmo.edt.sonarq.core.analysis.SecretScrubber;
import ru.jimmo.edt.sonarq.core.analysis.TimeoutDownloads;
import ru.jimmo.edt.sonarq.core.analysis.Processes;
import ru.jimmo.edt.sonarq.core.client.SonarServerException;
import ru.jimmo.edt.sonarq.core.model.CeTask;
import ru.jimmo.edt.sonarq.core.settings.ProjectBinding;
import ru.jimmo.edt.sonarq.ui.Messages;
import ru.jimmo.edt.sonarq.ui.sync.ProjectAnalysisRule;

/**
 * Runs a SonarQube analysis for one project as a user-visible background job.
 *
 * <p>The job supports three launch modes carried by the {@link AnalysisRequest}: triggering an
 * external CI pipeline ({@link AnalysisLaunchMode#CI_TRIGGER}), or running the SonarScanner CLI
 * either from a managed download ({@link AnalysisLaunchMode#LOCAL_AUTO}) or a user-provided path
 * ({@link AnalysisLaunchMode#LOCAL_PATH}). All progress and failures are surfaced through the
 * {@code statusReporter}; the job never opens dialogs. On a successful scanner run the
 * {@code onSuccess} callback is invoked from the job thread.
 */
public class AnalysisJob extends Job
{
    private static final String BSL_LANGUAGE = "bsl"; //$NON-NLS-1$
    private static final String CONSOLE_NAME = "SonarQube Analysis"; //$NON-NLS-1$
    private static final String CONSOLE_PUMP_THREAD = "sonarq-scanner-output"; //$NON-NLS-1$
    private static final String SCANNERWORK_DIR = "scannerwork"; //$NON-NLS-1$
    private static final String GIT_DIR = ".git"; //$NON-NLS-1$
    private static final String DEFAULT_SOURCES = "src"; //$NON-NLS-1$
    private static final String SOURCES_SUFFIX = "/src"; //$NON-NLS-1$
    private static final String TRAILING_SLASHES = "/+$"; //$NON-NLS-1$

    private static final int HTTP_OK_MIN = 200;
    private static final int HTTP_OK_MAX = 300;
    private static final int MAX_GIT_WALK = 10;
    private static final long PROCESS_POLL_MILLIS = 500L;
    private static final long PUMP_JOIN_MILLIS = 2000L;
    private static final long POLL_STEP_MILLIS = 200L;
    private static final long MILLIS_PER_MINUTE = 60L * 1000L;

    /** The Compute Engine polling budget used in production: ten minutes, probed every three seconds. */
    private static final PollingBudget DEFAULT_POLLING = new PollingBudget(10L * MILLIS_PER_MINUTE, 3000L);

    private final AnalysisRequest request;
    private final Runnable onSuccess;
    private final Consumer<String> statusReporter;
    private final IntFunction<ICiTrigger> ciTriggerFactory;
    private final PollingBudget polling;

    /**
     * Creates the job.
     *
     * @param request the launch inputs, not {@code null}
     * @param onSuccess invoked in the job thread only on a successful scanner-path run, not {@code null}
     * @param statusReporter receives user-facing status text; must be thread-safe, not {@code null}
     */
    public AnalysisJob(AnalysisRequest request, Runnable onSuccess, Consumer<String> statusReporter)
    {
        this(request, onSuccess, statusReporter, CiTriggerClient::new, DEFAULT_POLLING);
    }

    /**
     * Creates the job with the CI transport and the Compute Engine polling budget supplied from outside.
     *
     * <p>Package-private: it exists so the headless test fragment can drive the CI trigger path against a
     * fake transport and exhaust the polling budget in milliseconds instead of ten minutes. Production code
     * uses the three-argument constructor.
     *
     * @param request the launch inputs, not {@code null}
     * @param onSuccess invoked in the job thread only on a successful scanner-path run, not {@code null}
     * @param statusReporter receives user-facing status text; must be thread-safe, not {@code null}
     * @param ciTriggerFactory builds the CI transport from the connection timeout, not {@code null}
     * @param polling the Compute Engine polling budget, not {@code null}
     */
    AnalysisJob(AnalysisRequest request, Runnable onSuccess, Consumer<String> statusReporter,
        IntFunction<ICiTrigger> ciTriggerFactory, PollingBudget polling)
    {
        super(Messages.AnalysisJob_Name);
        this.request = request;
        this.onSuccess = onSuccess;
        this.statusReporter = statusReporter;
        this.ciTriggerFactory = ciTriggerFactory;
        this.polling = polling;
        setUser(true);
        // Serialize with the refresh job of the same project so a replacement run waits for the previous
        // one to release instead of racing over the shared analyzer install and scannerwork directories.
        setRule(new ProjectAnalysisRule(request.project().getName()));
    }

    @Override
    protected IStatus run(IProgressMonitor monitor)
    {
        try
        {
            if (request.config().mode() == AnalysisLaunchMode.CI_TRIGGER)
            {
                return runCiTrigger();
            }
            return runScanner(monitor);
        }
        catch (RuntimeException e)
        {
            // The CI path contains its own runtime failures (see runCiTrigger), so nothing reaching this
            // handler carries the CI trigger URL; the text is still scrubbed as defence in depth.
            String safe = SecretScrubber.scrub(String.valueOf(e));
            Platform.getLog(getClass()).error(safe, e);
            report(NLS.bind(Messages.IssuesView_Status_Error, safe));
            return Status.OK_STATUS;
        }
    }

    /**
     * Triggers the configured CI pipeline; always returns OK, reporting the outcome via the status line.
     *
     * <p>The transport is closed on every path (try-with-resources): it owns a selector thread, and one
     * click on "Analyze" used to leak one (review minor M2).
     *
     * @return {@link Status#OK_STATUS}
     */
    IStatus runCiTrigger()
    {
        String branch = request.requestedBranch() != null ? request.requestedBranch() : ""; //$NON-NLS-1$
        try (ICiTrigger trigger = ciTriggerFactory.apply(request.connection().timeoutSeconds()))
        {
            int status = trigger.trigger(request.config().ciUrl(), branch, request.ciSecret());
            if (status >= HTTP_OK_MIN && status < HTTP_OK_MAX)
            {
                report(NLS.bind(Messages.Analysis_CiTriggered, Integer.valueOf(status)));
            }
            else
            {
                report(NLS.bind(Messages.Analysis_CiFailed, String.valueOf(status)));
            }
        }
        catch (IOException | RuntimeException e)
        {
            // RuntimeException is caught here, not by the generic handler in run(), precisely so a failure
            // quoting the token-bearing URL (URI.create throws IllegalArgumentException with the whole URL
            // in its message) never reaches Platform.getLog unscrubbed.
            report(NLS.bind(Messages.Analysis_CiFailed, ciFailureDetail(e)));
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            report(NLS.bind(Messages.Analysis_CiFailed, ciFailureDetail(e)));
        }
        return Status.OK_STATUS;
    }

    /**
     * Describes a CI trigger failure in a single line with every credential redacted.
     *
     * @param e the failure, not {@code null}
     * @return the scrubbed description, never {@code null}
     */
    private String ciFailureDetail(Throwable e)
    {
        return SecretScrubber.scrub(detail(e), request.ciSecret());
    }

    /**
     * Runs the SonarScanner CLI and waits for the server to process the report.
     *
     * @param monitor the progress monitor, checked for cancellation, not {@code null}
     * @return the job status
     */
    private IStatus runScanner(IProgressMonitor monitor)
    {
        Set<String> languages;
        try
        {
            languages = request.client().serverLanguages();
        }
        catch (SonarServerException e)
        {
            report(detail(e));
            return Status.OK_STATUS;
        }
        if (!languages.contains(BSL_LANGUAGE))
        {
            report(Messages.Analysis_NoBslOnServer);
            return Status.OK_STATUS;
        }

        Path executable;
        try
        {
            executable = resolveExecutable(monitor);
        }
        catch (OperationCanceledException e)
        {
            return Status.CANCEL_STATUS;
        }
        catch (IOException e)
        {
            report(detail(e));
            return Status.OK_STATUS;
        }
        if (executable == null)
        {
            report(Messages.Analysis_ScannerNotFound);
            return Status.OK_STATUS;
        }

        try
        {
            return runProcessAndAwait(executable, resolveSourceRoot(), monitor);
        }
        catch (OperationCanceledException e)
        {
            return Status.CANCEL_STATUS;
        }
        catch (IOException e)
        {
            report(detail(e));
            return Status.OK_STATUS;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return Status.CANCEL_STATUS;
        }
    }

    /**
     * Resolves the scanner executable for the configured mode.
     *
     * @param monitor the progress monitor for a managed download, not {@code null}
     * @return the executable path, or {@code null} when a user-provided path is missing or invalid
     * @throws IOException if a managed download or unpack fails
     */
    private Path resolveExecutable(IProgressMonitor monitor) throws IOException
    {
        if (request.config().mode() == AnalysisLaunchMode.LOCAL_PATH)
        {
            String path = request.config().scannerPath();
            if (path == null || path.isBlank())
            {
                return null;
            }
            Path candidate = Path.of(path);
            return Files.isRegularFile(candidate) ? candidate : null;
        }
        return ScannerInstaller.ensureScanner(request.stateLocation(), TimeoutDownloads::open, monitor);
    }

    /**
     * Launches the scanner process, streams its output to the console and awaits the server report.
     *
     * @param executable the scanner executable, not {@code null}
     * @param root the base directory and sources path, not {@code null}
     * @param monitor the progress monitor, not {@code null}
     * @return the job status
     * @throws IOException if the process cannot be started or the work directory cannot be created
     * @throws InterruptedException if the job thread is interrupted while waiting for the process
     */
    private IStatus runProcessAndAwait(Path executable, SourceRoot root, IProgressMonitor monitor)
        throws IOException, InterruptedException
    {
        Path workDir = request.stateLocation().resolve(SCANNERWORK_DIR).resolve(request.project().getName());
        Files.createDirectories(workDir);
        ScannerLaunch launch = ScannerCommandBuilder.build(executable, request.connection(),
            request.binding().projectKey(), request.branchesSupported() ? request.requestedBranch() : null,
            root.baseDir(), root.sources(), workDir, request.config().extraArgs());

        ProcessBuilder builder = new ProcessBuilder(launch.command());
        builder.environment().putAll(launch.environment());
        builder.directory(launch.directory().toFile());
        builder.redirectErrorStream(true);

        MessageConsole console = findOrCreateConsole();
        console.activate();
        Process process = builder.start();
        Thread pump = new Thread(() -> pumpToConsole(process, console), CONSOLE_PUMP_THREAD);
        pump.setDaemon(true);
        pump.start();
        try
        {
            while (!process.waitFor(PROCESS_POLL_MILLIS, TimeUnit.MILLISECONDS))
            {
                if (monitor.isCanceled())
                {
                    Processes.terminate(process);
                    join(pump);
                    return Status.CANCEL_STATUS;
                }
            }
        }
        catch (InterruptedException e)
        {
            // The job thread gave up waiting; the scanner (which still holds the token) must not run on.
            Processes.terminate(process);
            join(pump);
            throw e;
        }
        join(pump);
        int exit = process.exitValue();
        if (exit != 0)
        {
            report(NLS.bind(Messages.Analysis_ScannerFailed, Integer.valueOf(exit)));
            return Status.OK_STATUS;
        }
        return awaitServerProcessing(workDir, monitor);
    }

    /**
     * Polls the Compute Engine task produced by the scanner until it reaches a terminal state.
     *
     * <p>Polling is bounded by the {@link PollingBudget}; exhausting that budget is reported as a timeout
     * rather than passing silently, so the status line never keeps the "server is processing" text forever.
     *
     * <p>Package-private so the headless test fragment can drive the whole poll loop - report-task parsing,
     * terminal outcomes, cancellation and the timeout - against a fake server client.
     *
     * @param workDir the scanner working directory holding {@code report-task.txt}, not {@code null}
     * @param monitor the progress monitor, not {@code null}
     * @return the job status
     */
    IStatus awaitServerProcessing(Path workDir, IProgressMonitor monitor)
    {
        Optional<String> taskId = ReportTaskParser.ceTaskId(workDir);
        if (taskId.isEmpty())
        {
            report(Messages.Analysis_Done);
            onSuccess.run();
            return Status.OK_STATUS;
        }
        report(Messages.Analysis_ServerProcessing);
        long deadline = System.currentTimeMillis() + polling.totalMillis();
        while (System.currentTimeMillis() < deadline)
        {
            if (monitor.isCanceled())
            {
                return Status.CANCEL_STATUS;
            }
            CeTask task;
            try
            {
                task = request.client().ceTaskStatus(taskId.get());
            }
            catch (SonarServerException e)
            {
                report(detail(e));
                return Status.OK_STATUS;
            }
            if (task.terminal())
            {
                return finish(task);
            }
            if (!sleepCancelable(polling.intervalMillis(), monitor))
            {
                return Status.CANCEL_STATUS;
            }
        }
        // Falling out of the loop means the budget ran out while the task was still queued or running.
        // Say so: otherwise the status line keeps the "server is processing the report" text forever.
        report(NLS.bind(Messages.Analysis_ServerTimeout, Long.valueOf(polling.totalMillis() / MILLIS_PER_MINUTE)));
        return Status.OK_STATUS;
    }

    /**
     * Reports the outcome of a terminal Compute Engine task.
     *
     * @param task the terminal task, not {@code null}
     * @return {@link Status#OK_STATUS}
     */
    private IStatus finish(CeTask task)
    {
        if (task.success())
        {
            report(Messages.Analysis_Done);
            onSuccess.run();
        }
        else
        {
            String message = task.errorMessage();
            report(message == null || message.isEmpty() ? task.status() : message);
        }
        return Status.OK_STATUS;
    }

    /**
     * Resolves the scanner base directory and the {@code sonar.sources} path from the project binding.
     *
     * <p>With no path prefix the project location is the base directory and {@code src} the sources.
     * With a prefix, the base directory is the enclosing git repository root and the sources become
     * {@code <prefix>/src}; if no repository is found the project location is used without the prefix.
     *
     * <p>Package-private for the headless test fragment.
     *
     * @return the resolved base directory and sources, never {@code null}
     */
    SourceRoot resolveSourceRoot()
    {
        Path projectDir = request.project().getLocation().toFile().toPath();
        ProjectBinding binding = request.binding();
        if (binding.pathPrefix().isEmpty())
        {
            return new SourceRoot(projectDir, DEFAULT_SOURCES);
        }
        Path repoRoot = findRepositoryRoot(projectDir.toFile());
        if (repoRoot == null)
        {
            return new SourceRoot(projectDir, DEFAULT_SOURCES);
        }
        String prefix = binding.pathPrefix().replaceAll(TRAILING_SLASHES, ""); //$NON-NLS-1$
        return new SourceRoot(repoRoot, prefix + SOURCES_SUFFIX);
    }

    /**
     * Walks up from the given directory looking for a {@code .git} entry (directory or worktree file).
     *
     * <p>Package-private for the headless test fragment.
     *
     * @param start the directory to start from, not {@code null}
     * @return the repository root, or {@code null} if none is found within {@link #MAX_GIT_WALK} levels
     */
    static Path findRepositoryRoot(File start)
    {
        File current = start;
        for (int depth = 0; depth < MAX_GIT_WALK && current != null; depth++)
        {
            if (new File(current, GIT_DIR).exists())
            {
                return current.toPath();
            }
            current = current.getParentFile();
        }
        return null;
    }

    /**
     * Pumps the merged process output to the console, line by line, until the stream closes.
     *
     * @param process the running process, not {@code null}
     * @param console the target console, not {@code null}
     */
    private static void pumpToConsole(Process process, MessageConsole console)
    {
        try (MessageConsoleStream stream = console.newMessageStream();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)))
        {
            String line = reader.readLine();
            while (line != null)
            {
                stream.println(line);
                line = reader.readLine();
            }
        }
        catch (IOException e)
        {
            // The stream closes when the process is destroyed; nothing actionable to report here.
        }
    }

    /**
     * Finds the shared analysis console by name or creates it.
     *
     * @return the analysis console, never {@code null}
     */
    private static MessageConsole findOrCreateConsole()
    {
        IConsoleManager manager = ConsolePlugin.getDefault().getConsoleManager();
        for (IConsole existing : manager.getConsoles())
        {
            if (CONSOLE_NAME.equals(existing.getName()) && existing instanceof MessageConsole message)
            {
                return message;
            }
        }
        MessageConsole created = new MessageConsole(CONSOLE_NAME, null);
        manager.addConsoles(new IConsole[] {created});
        return created;
    }

    /**
     * Sleeps for the given duration in small steps, aborting early on cancellation.
     *
     * @param millis the total time to sleep, in milliseconds
     * @param monitor the progress monitor, not {@code null}
     * @return {@code true} if the full duration elapsed, {@code false} if cancelled or interrupted
     */
    private static boolean sleepCancelable(long millis, IProgressMonitor monitor)
    {
        long remaining = millis;
        while (remaining > 0)
        {
            if (monitor.isCanceled())
            {
                return false;
            }
            long chunk = Math.min(POLL_STEP_MILLIS, remaining);
            try
            {
                Thread.sleep(chunk);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return false;
            }
            remaining -= chunk;
        }
        return true;
    }

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

    private static String detail(Throwable e)
    {
        String message = e.getMessage();
        return message != null && !message.isEmpty() ? message : e.getClass().getSimpleName();
    }

    private void report(String text)
    {
        statusReporter.accept(text);
    }

    /**
     * The scanner base directory and the {@code sonar.sources} value relative to it.
     *
     * @param baseDir the directory to run the scanner in, not {@code null}
     * @param sources the {@code sonar.sources} path relative to {@code baseDir}, not {@code null}
     */
    record SourceRoot(Path baseDir, String sources)
    {
    }

    /**
     * How long the Compute Engine task produced by a scanner run is polled for, and how long to wait
     * between two polls.
     *
     * @param totalMillis the total polling budget in milliseconds, positive
     * @param intervalMillis the wait between two polls in milliseconds, positive
     */
    record PollingBudget(long totalMillis, long intervalMillis)
    {
    }
}
