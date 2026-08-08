/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.resources;

import java.util.Map;

import ru.jimmo.edt.sonarq.core.anchors.AnchorIndex;
import ru.jimmo.edt.sonarq.core.anchors.AnchorIndexStore;
import ru.jimmo.edt.sonarq.core.anchors.AnchorScope;
import ru.jimmo.edt.sonarq.core.client.SonarConnection;
import ru.jimmo.edt.sonarq.core.localanalysis.LocalIssueProvider;
import ru.jimmo.edt.sonarq.core.model.IssueSnapshot;
import ru.jimmo.edt.sonarq.core.provider.BranchState;
import ru.jimmo.edt.sonarq.ui.markers.MarkerStateVersion;
import ru.jimmo.edt.sonarq.ui.sync.ProjectRefreshInputs;
import ru.jimmo.edt.sonarq.ui.views.IssueAnchoring;

/**
 * Anchors one refresh's issues against the persisted anchor memory of the analysis that produced them, and
 * writes that memory back.
 *
 * <p>This is where the scope of the memory is decided (see {@link AnchorScope}), which is the only reason
 * this class exists as something other than a lambda: the mode, the server URL, the project key, the path
 * prefix and the workspace project all come from the inputs the refresh was scheduled with, but the effective
 * branch is only known once the refresh has resolved it against the server - so the scope cannot be built
 * before the fetch, and the store cannot be opened before the scope.
 */
public final class RefreshAnchoring implements IssueAnchoring
{
    private final ProjectRefreshInputs inputs;

    private final Map<String, String> knownAnchors;

    private final AnchorIndexStore store;

    /**
     * Creates the anchoring for one refresh.
     *
     * @param inputs the inputs the refresh was scheduled with, not {@code null}
     * @param knownAnchors the anchors the caller already held for these issues, keyed by issue key, not
     *     {@code null}; a fallback for issues the persisted memory has never heard of - in practice the
     *     issues view's previous snapshot
     */
    public RefreshAnchoring(ProjectRefreshInputs inputs, Map<String, String> knownAnchors)
    {
        this(inputs, knownAnchors, AnchorMemory.store());
    }

    /**
     * Creates the anchoring with an explicit store, for tests that must not write into the running plug-in's
     * state location.
     *
     * @param inputs the inputs the refresh was scheduled with, not {@code null}
     * @param knownAnchors the anchors the caller already held, not {@code null}
     * @param store the anchor memory to read and write, or {@code null} to anchor with session memory only
     */
    public RefreshAnchoring(ProjectRefreshInputs inputs, Map<String, String> knownAnchors,
        AnchorIndexStore store)
    {
        this.inputs = inputs;
        this.knownAnchors = knownAnchors;
        this.store = store;
    }

    @Override
    public IssueSnapshot anchor(IssueSnapshot snapshot, BranchState branches, long markerStateVersion)
    {
        if (store == null)
        {
            return IssueAnchors.anchor(inputs.project(), inputs.mappingProjectKey(),
                inputs.mappingPathPrefix(), snapshot, knownAnchors);
        }
        AnchorScope scope = scopeOf(branches);
        AnchorIndex index = store.load(scope);
        long now = System.currentTimeMillis();
        IssueSnapshot anchored = IssueAnchors.reconcile(inputs.project(), inputs.mappingProjectKey(),
            inputs.mappingPathPrefix(), snapshot, knownAnchors, index, now);
        // The fence the marker synchronization uses, for the same reason and against the same event: a
        // quick-suppress that landed while this refresh was in flight has published a newer state, and this
        // memory describes the file as it was before that edit. The store re-checks it under its own lock.
        store.save(index, () -> MarkerStateVersion.isCurrent(inputs.project(), markerStateVersion));
        return anchored;
    }

    /**
     * Builds the scope these issues belong to.
     *
     * @param branches the branch state the refresh resolved, not {@code null}
     * @return the scope, never {@code null}
     */
    private AnchorScope scopeOf(BranchState branches)
    {
        boolean local = inputs.provider() instanceof LocalIssueProvider;
        SonarConnection connection = inputs.connection();
        return new AnchorScope(local ? AnchorScope.MODE_LOCAL : AnchorScope.MODE_SERVER,
            AnchorScope.normalizeUrl(connection != null ? connection.baseUrl() : null),
            inputs.mappingProjectKey(),
            branches.branchesSupported() ? branches.effectiveBranch() : null,
            inputs.mappingPathPrefix(),
            inputs.project().getName());
    }
}
