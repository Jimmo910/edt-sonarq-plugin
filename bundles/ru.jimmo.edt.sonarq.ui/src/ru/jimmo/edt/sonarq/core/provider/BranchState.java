/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.provider;

/**
 * The outcome of resolving a requested branch against the branches known to the server.
 *
 * @param requestedBranch the locally detected or configured branch, may be {@code null}
 * @param effectiveBranch the branch to actually query, {@code null} when branches are unsupported
 * @param missingOnServer whether the requested branch was not found among the server branches
 * @param branchesSupported whether the server edition reported any branches at all
 */
public record BranchState(String requestedBranch, String effectiveBranch, boolean missingOnServer,
    boolean branchesSupported)
{
}
