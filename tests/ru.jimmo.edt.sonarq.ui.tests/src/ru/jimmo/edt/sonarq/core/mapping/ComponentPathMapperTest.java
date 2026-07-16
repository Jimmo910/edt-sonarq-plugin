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
}
