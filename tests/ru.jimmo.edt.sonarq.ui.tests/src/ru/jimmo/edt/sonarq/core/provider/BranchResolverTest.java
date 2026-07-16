/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import ru.jimmo.edt.sonarq.core.model.BranchInfo;

/** Tests for {@link BranchResolver}. */
public class BranchResolverTest
{
    private static final List<BranchInfo> BRANCHES =
        List.of(new BranchInfo("main", true), new BranchInfo("feature/x", false));

    @Test
    public void requestedBranchExistsOnServer()
    {
        BranchState state = BranchResolver.resolve("feature/x", BRANCHES);
        assertEquals("feature/x", state.effectiveBranch());
        assertFalse(state.missingOnServer());
        assertTrue(state.branchesSupported());
    }

    @Test
    public void missingBranchFallsBackToMain()
    {
        BranchState state = BranchResolver.resolve("feature/new", BRANCHES);
        assertEquals("main", state.effectiveBranch());
        assertTrue(state.missingOnServer());
    }

    @Test
    public void nullRequestUsesMainWithoutMissingFlag()
    {
        BranchState state = BranchResolver.resolve(null, BRANCHES);
        assertEquals("main", state.effectiveBranch());
        assertFalse(state.missingOnServer());
    }

    @Test
    public void noBranchesMeansUnsupported()
    {
        BranchState state = BranchResolver.resolve("feature/x", List.of());
        assertNull(state.effectiveBranch());
        assertFalse(state.branchesSupported());
        assertFalse(state.missingOnServer());
    }
}
