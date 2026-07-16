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
import ru.jimmo.edt.sonarq.core.mapping.GitBranchDetector;
import ru.jimmo.edt.sonarq.core.model.BranchInfo;
import ru.jimmo.edt.sonarq.core.model.IssueQuery;
import ru.jimmo.edt.sonarq.core.model.IssueSnapshot;
import ru.jimmo.edt.sonarq.core.provider.BranchResolver;
import ru.jimmo.edt.sonarq.core.provider.BranchState;
import ru.jimmo.edt.sonarq.core.provider.IIssueProvider;
import ru.jimmo.edt.sonarq.core.settings.ProjectBinding;
import ru.jimmo.edt.sonarq.ui.Messages;

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
        super(Messages.RefreshJob_Name);
        this.provider = provider;
        this.project = project;
        this.binding = binding;
        this.sessionBranch = sessionBranch;
        this.callback = callback;
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
