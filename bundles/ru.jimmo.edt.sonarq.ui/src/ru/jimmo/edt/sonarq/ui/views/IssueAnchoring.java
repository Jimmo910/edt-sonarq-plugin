/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import ru.jimmo.edt.sonarq.core.model.IssueSnapshot;
import ru.jimmo.edt.sonarq.core.provider.BranchState;

/**
 * Turns a freshly fetched snapshot into an anchored one, inside the refresh job that fetched it.
 *
 * <p>The seam exists so that {@link RefreshIssuesJob} - which knows about providers, branches and
 * cancellation - does not also have to know about workspace files, the plug-in state location and the
 * persisted anchor memory, and so that a test can drive either half without the other. The production
 * implementation is {@code ru.jimmo.edt.sonarq.ui.resources.RefreshAnchoring}.
 *
 * <p>Called in the job's own thread, never on the UI thread: anchoring reads every file the issues point at.
 */
@FunctionalInterface
public interface IssueAnchoring
{
    /**
     * Anchors one refresh's issues and commits the memory of them.
     *
     * @param snapshot the freshly fetched issues, not {@code null}
     * @param branches the branch state the fetch resolved, not {@code null}; part of what decides whose
     *     memory these issues belong to
     * @param markerStateVersion the issue state version this refresh reserved <em>before</em> it fetched
     *     anything; the commit must check it, so that a refresh overtaken by a quick-suppress writes no
     *     memory of the pre-edit file (see {@code ru.jimmo.edt.sonarq.ui.markers.MarkerStateVersion})
     * @return the anchored snapshot, never {@code null}; {@code snapshot} itself when there was nothing to
     *     anchor
     */
    IssueSnapshot anchor(IssueSnapshot snapshot, BranchState branches, long markerStateVersion);
}
