/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.provider;

import java.util.List;

import ru.jimmo.edt.sonarq.core.model.BranchInfo;

/** Resolves the effective analysis branch against the branches known to the server. */
public final class BranchResolver
{
    private BranchResolver()
    {
    }

    /**
     * Resolves the branch to query.
     *
     * @param requestedBranch the locally detected or configured branch, may be {@code null}
     * @param serverBranches the branches known to the server, not {@code null}; empty when unsupported
     * @return the resolved state, never {@code null}
     */
    public static BranchState resolve(String requestedBranch, List<BranchInfo> serverBranches)
    {
        if (serverBranches.isEmpty())
        {
            return new BranchState(requestedBranch, null, false, false);
        }
        boolean exists = requestedBranch != null
            && serverBranches.stream().anyMatch(branch -> branch.name().equals(requestedBranch));
        if (exists)
        {
            return new BranchState(requestedBranch, requestedBranch, false, true);
        }
        String main = serverBranches.stream().filter(BranchInfo::main).map(BranchInfo::name).findFirst()
            .orElse(serverBranches.get(0).name());
        return new BranchState(requestedBranch, main, requestedBranch != null, true);
    }
}
