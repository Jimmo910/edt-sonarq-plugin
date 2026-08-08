/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.anchors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Tests {@link AnchorScope}: what makes two analyses the same memory, and - far more importantly - what makes
 * them different ones.
 */
public class AnchorScopeTest
{
    private static final String URL = "https://sonar.example/sq";

    @Test
    public void theSameAnalysisAlwaysHasTheSameIdentity()
    {
        assertEquals(scope(URL, "key", "main", "", "Proj").id(), scope(URL, "key", "main", "", "Proj").id());
    }

    @Test
    public void theIdentityIsAUsableFileNameSegment()
    {
        String id = scope(URL, "org.acme:my/key", "feature/x", "sub/dir", "My Project").id();

        assertEquals(32, id.length());
        assertTrue(id, id.matches("[0-9a-f]{32}"));
    }

    /**
     * The whole point of the scope. Every one of these fields changes what an issue key means, so a record
     * stored under one must never be found under another - a suppression that trusts the wrong memory edits
     * the line that memory names.
     */
    @Test
    public void everyComponentSeparatesTheMemory()
    {
        Set<String> ids = new HashSet<>();
        ids.add(scope(URL, "key", "main", "", "Proj").id());
        ids.add(scope("https://other.example", "key", "main", "", "Proj").id());
        ids.add(scope(URL, "other-key", "main", "", "Proj").id());
        ids.add(scope(URL, "key", "release/1", "", "Proj").id());
        ids.add(scope(URL, "key", "main", "prefix", "Proj").id());
        ids.add(scope(URL, "key", "main", "", "OtherProj").id());
        ids.add(new AnchorScope(AnchorScope.MODE_LOCAL, "", "key", "main", "", "Proj").id());

        assertEquals("every varied component must produce its own scope", 7, ids.size());
    }

    /**
     * A local run invents its own issue keys and shares none with a server, so it may not inherit a server
     * scope's memory even when everything else about the two matches.
     */
    @Test
    public void localAndServerModeNeverShareMemory()
    {
        assertNotEquals(new AnchorScope(AnchorScope.MODE_LOCAL, "", "key", null, "", "Proj").id(),
            new AnchorScope(AnchorScope.MODE_SERVER, "", "key", null, "", "Proj").id());
    }

    /**
     * The default-branch sentinel. "No branch" is a scope of its own, and a branch that happens to be
     * <em>called</em> what the sentinel is rendered as must not inherit its anchors - which a scope built by
     * joining its fields into one string would have allowed.
     */
    @Test
    public void theDefaultBranchIsItsOwnScopeAndCannotBeImpersonated()
    {
        String defaultBranch = scope(URL, "key", null, "", "Proj").id();

        assertNotEquals(defaultBranch, scope(URL, "key", AnchorScope.DEFAULT_BRANCH, "", "Proj").id());
        assertNotEquals(defaultBranch, scope(URL, "key", "", "", "Proj").id());
        assertEquals(AnchorScope.DEFAULT_BRANCH, scope(URL, "key", null, "", "Proj").describeBranch());
        assertEquals("main", scope(URL, "key", "main", "", "Proj").describeBranch());
    }

    /**
     * Field boundaries cannot be imitated: two scopes whose components run together into the same characters
     * still hash differently, because each component is written with its own length.
     */
    @Test
    public void componentsCannotBleedIntoEachOther()
    {
        assertNotEquals(scope(URL, "ab", "c", "", "Proj").id(), scope(URL, "a", "bc", "", "Proj").id());
    }

    @Test
    public void trailingSlashesAndWhitespaceInTheUrlAreNormalizedAway()
    {
        assertEquals(URL, AnchorScope.normalizeUrl(URL + "/"));
        assertEquals(URL, AnchorScope.normalizeUrl("  " + URL + "//  "));
        assertEquals("", AnchorScope.normalizeUrl(null));
        assertEquals(scope(URL, "key", "main", "", "Proj").id(),
            new AnchorScope(AnchorScope.MODE_SERVER, AnchorScope.normalizeUrl(URL + "/"), "key", "main", "",
                "Proj").id());
    }

    /** The token is not a component of the scope, and this class never sees one - it is not stored. */
    @Test
    public void theScopeHasNoTokenComponentAtAll()
    {
        for (java.lang.reflect.RecordComponent component : AnchorScope.class.getRecordComponents())
        {
            assertNotEquals("a token must never be part of a scope written to disk", "token",
                component.getName());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void aMissingComponentIsRejectedWhereItHappens()
    {
        new AnchorScope(AnchorScope.MODE_SERVER, URL, null, "main", "", "Proj");
    }

    private static AnchorScope scope(String url, String projectKey, String branch, String prefix, String name)
    {
        return new AnchorScope(AnchorScope.MODE_SERVER, url, projectKey, branch, prefix, name);
    }
}
