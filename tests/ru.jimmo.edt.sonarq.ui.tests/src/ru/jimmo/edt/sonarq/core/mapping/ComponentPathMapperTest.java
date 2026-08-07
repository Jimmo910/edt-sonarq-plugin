/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.mapping;

import static org.junit.Assert.assertEquals;

import java.util.Optional;

import org.junit.Test;

/** Tests for {@link ComponentPathMapper}. */
public class ComponentPathMapperTest
{
    @Test
    public void stripsProjectKey()
    {
        assertEquals(Optional.of("src/CommonModules/Common/Module.bsl"),
            ComponentPathMapper.toProjectRelativePath(
                "proj:src/CommonModules/Common/Module.bsl", "proj", null));
    }

    @Test
    public void stripsConfiguredPrefix()
    {
        assertEquals(Optional.of("src/CommonModules/Common/Module.bsl"),
            ComponentPathMapper.toProjectRelativePath(
                "proj:conf/src/CommonModules/Common/Module.bsl", "proj", "conf"));
    }

    @Test
    public void prefixWithTrailingSlashAccepted()
    {
        assertEquals(Optional.of("src/Catalogs/Items/ObjectModule.bsl"),
            ComponentPathMapper.toProjectRelativePath(
                "proj:conf/src/Catalogs/Items/ObjectModule.bsl", "proj", "conf/"));
    }

    @Test
    public void mismatchedPrefixYieldsEmpty()
    {
        assertEquals(Optional.empty(),
            ComponentPathMapper.toProjectRelativePath("proj:other/Module.bsl", "proj", "conf"));
    }

    @Test
    public void foreignProjectYieldsEmpty()
    {
        assertEquals(Optional.empty(),
            ComponentPathMapper.toProjectRelativePath("other:src/Module.bsl", "proj", null));
    }

    @Test
    public void nullAndProjectLevelComponentYieldEmpty()
    {
        assertEquals(Optional.empty(), ComponentPathMapper.toProjectRelativePath(null, "proj", null));
        assertEquals(Optional.empty(), ComponentPathMapper.toProjectRelativePath("proj", "proj", null));
    }

    /**
     * Review minor M7. What this mapper returns is resolved with {@code IProject#getFile}, whose {@code IPath}
     * argument is canonicalized - {@code /proj} + {@code ../Other/src/Module.bsl} is {@code /Other/src/Module.bsl},
     * a file in a different project. That path then receives Problems-view markers, is what "go to code" opens
     * and, through the quick suppression, is <em>written to</em>. A hostile or compromised SonarQube server
     * must not be able to reach outside the bound project by putting {@code ..} in a component key.
     */
    @Test
    public void leadingParentSegmentYieldsEmpty()
    {
        assertEquals(Optional.empty(),
            ComponentPathMapper.toProjectRelativePath("proj:../Other/src/Module.bsl", "proj", null));
    }

    @Test
    public void embeddedParentSegmentYieldsEmpty()
    {
        assertEquals(Optional.empty(),
            ComponentPathMapper.toProjectRelativePath("proj:src/../../Other/src/Module.bsl", "proj", null));
    }

    /**
     * {@code IProject#getFile(String)} parses through {@code org.eclipse.core.runtime.Path}, which on Windows
     * treats a backslash as a separator - so a backslash-separated traversal escapes just as well.
     */
    @Test
    public void backslashSeparatedParentSegmentYieldsEmpty()
    {
        assertEquals(Optional.empty(),
            ComponentPathMapper.toProjectRelativePath("proj:..\\Other\\src\\Module.bsl", "proj", null));
    }

    @Test
    public void parentSegmentBehindTheConfiguredPrefixYieldsEmpty()
    {
        assertEquals(Optional.empty(),
            ComponentPathMapper.toProjectRelativePath("proj:conf/../../Other/src/Module.bsl", "proj", "conf"));
    }

    @Test
    public void rootRelativePathYieldsEmpty()
    {
        assertEquals(Optional.empty(), ComponentPathMapper.toProjectRelativePath("proj:/etc/passwd", "proj", null));
    }

    @Test
    public void driveQualifiedPathYieldsEmpty()
    {
        assertEquals(Optional.empty(),
            ComponentPathMapper.toProjectRelativePath("proj:C:/Windows/System32/drivers/etc/hosts", "proj", null));
        assertEquals(Optional.empty(),
            ComponentPathMapper.toProjectRelativePath("proj:c:\\Windows\\System32\\config", "proj", null));
    }

    @Test
    public void uncPathYieldsEmpty()
    {
        assertEquals(Optional.empty(),
            ComponentPathMapper.toProjectRelativePath("proj:\\\\host\\share\\Module.bsl", "proj", null));
    }

    /** The rejection is per path segment: dots inside a name are not a traversal and stay mapped. */
    @Test
    public void dotsInsideNamesAreStillAccepted()
    {
        assertEquals(Optional.of("src/a..b/Module..bsl"),
            ComponentPathMapper.toProjectRelativePath("proj:src/a..b/Module..bsl", "proj", null));
        assertEquals(Optional.of("src/./Module.bsl"),
            ComponentPathMapper.toProjectRelativePath("proj:src/./Module.bsl", "proj", null));
    }
}
