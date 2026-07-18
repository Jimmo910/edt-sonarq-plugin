/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import java.util.List;
import java.util.function.Consumer;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.osgi.util.NLS;

import ru.jimmo.edt.sonarq.core.client.SonarServerException;
import ru.jimmo.edt.sonarq.core.localanalysis.LocalIssueProvider;
import ru.jimmo.edt.sonarq.core.mapping.GitBranchDetector;
import ru.jimmo.edt.sonarq.core.model.BranchInfo;
import ru.jimmo.edt.sonarq.core.model.IssueQuery;
import ru.jimmo.edt.sonarq.core.model.IssueSnapshot;
import ru.jimmo.edt.sonarq.core.provider.BranchResolver;
import ru.jimmo.edt.sonarq.core.provider.BranchState;
import ru.jimmo.edt.sonarq.core.provider.IIssueProvider;
import ru.jimmo.edt.sonarq.core.settings.ProjectBinding;
import ru.jimmo.edt.sonarq.ui.Messages;
import ru.jimmo.edt.sonarq.ui.sync.ProjectAnalysisRule;

/** Loads issues for a project in the background and reports the result to a callback. */
public class RefreshIssuesJob extends Job
{
    private final IIssueProvider provider;
    private final IProject project;
    private final ProjectBinding binding;
    private final String sessionBranch;
    private final Consumer<RefreshResult> callback;

    /**
     * Creates the job.
     *
     * @param provider the issue provider, not {@code null}
     * @param project the workspace project, not {@code null}
     * @param binding the project binding, must be configured
     * @param sessionBranch a transient branch override, may be {@code null}
     * @param callback receives the result in the job thread, not {@code null}
     */
    public RefreshIssuesJob(IIssueProvider provider, IProject project, ProjectBinding binding,
        String sessionBranch, Consumer<RefreshResult> callback)
    {
        super(jobName(provider));
        this.provider = provider;
        this.project = project;
        this.binding = binding;
        this.sessionBranch = sessionBranch;
        this.callback = callback;
        // Local analysis is a heavyweight, user-triggered run (BSL Language Server install/analysis), so
        // showing it as a foreground job surfaces the indeterminate progress LocalIssueProvider#fetchIssues
        // reports.
        // The much faster server-mode refresh - which AutoSyncScheduler also runs unattended in the
        // background - must never pop up a progress dialog, so this stays scoped to the local branch only
        // (see the CLAUDE.md unattended-safety rule).
        setUser(isLocalProvider(provider));
        // Serialize with the analysis job of the same project so replacement runs never race on the shared
        // analyzer install and output directories; this rule does not conflict with resource operations.
        setRule(new ProjectAnalysisRule(project.getName()));
    }

    /**
     * Tells whether {@code provider} runs local BSL Language Server analysis rather than querying a
     * SonarQube server.
     *
     * @param provider the issue provider this job is (or would be) built with, not {@code null}
     * @return {@code true} for a {@link LocalIssueProvider}
     */
    static boolean isLocalProvider(IIssueProvider provider)
    {
        return provider instanceof LocalIssueProvider;
    }

    /**
     * Picks the job name shown in the Progress view: a local-analysis-specific title for a
     * {@link LocalIssueProvider} (which analyzes, rather than downloads, issues), the generic
     * server-refresh title otherwise.
     *
     * @param provider the issue provider this job is (or would be) built with, not {@code null}
     * @return the job name, never {@code null}
     */
    static String jobName(IIssueProvider provider)
    {
        return isLocalProvider(provider) ? Messages.RefreshJob_LocalName : Messages.RefreshJob_Name;
    }

    @Override
    protected IStatus run(IProgressMonitor monitor)
    {
        try
        {
            String requested = resolveRequestedBranch();
            List<BranchInfo> branches = provider.branchAnalysisSupported()
                ? provider.listBranches(binding.projectKey())
                : List.<BranchInfo>of();
            BranchState state = BranchResolver.resolve(requested, branches);
            IssueQuery query = new IssueQuery(binding.projectKey(),
                state.branchesSupported() ? state.effectiveBranch() : null);
            IssueSnapshot result = provider.fetchIssues(query, monitor);
            callback.accept(new RefreshResult(result, state, null));
            return Status.OK_STATUS;
        }
        catch (OperationCanceledException e)
        {
            return Status.CANCEL_STATUS;
        }
        catch (SonarServerException e)
        {
            Platform.getLog(getClass()).warn(e.getMessage(), e);
            callback.accept(RefreshResult.error(toUserMessage(e)));
            return Status.OK_STATUS;
        }
        catch (RuntimeException e)
        {
            Platform.getLog(getClass()).error(e.getMessage(), e);
            callback.accept(RefreshResult.error(String.valueOf(e)));
            return Status.OK_STATUS;
        }
    }

    private static String toUserMessage(SonarServerException e)
    {
        if (e.getStatusCode() == 401 || e.getStatusCode() == 403)
        {
            return NLS.bind(Messages.IssuesView_Status_AuthError, String.valueOf(e.getStatusCode()));
        }
        return e.getMessage();
    }

    private String resolveRequestedBranch()
    {
        if (sessionBranch != null && !sessionBranch.isEmpty())
        {
            return sessionBranch;
        }
        if (!binding.branchOverride().isEmpty())
        {
            return binding.branchOverride();
        }
        IPath location = project.getLocation();
        return location != null
            ? GitBranchDetector.detectBranch(location.toFile()).orElse(null)
            : null;
    }
}
