/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtension;
import org.eclipse.core.runtime.Platform;
import org.junit.Test;

/**
 * Verifies that the plug-in's preference and property pages are registered in the extension registry.
 */
public class PluginContributionTest
{
    @Test
    public void preferencePageIsRegistered()
    {
        assertTrue(extensionIds("org.eclipse.ui.preferencePages").contains("ru.jimmo.edt.sonarq.ui.preferences.main"));
    }

    @Test
    public void propertyPageIsRegistered()
    {
        assertTrue(extensionIds("org.eclipse.ui.propertyPages").contains("ru.jimmo.edt.sonarq.ui.properties.project"));
    }

    @Test
    public void issuesViewIsRegistered()
    {
        assertTrue(extensionIds("org.eclipse.ui.views").contains("ru.jimmo.edt.sonarq.ui.views.issues"));
    }

    @Test
    public void markerTypeIsRegistered()
    {
        IExtension extension = Platform.getExtensionRegistry().getExtension(
            "org.eclipse.core.resources.markers", "ru.jimmo.edt.sonarq.ui.issue");
        assertNotNull(extension);
    }

    @Test
    public void startupIsRegistered()
    {
        boolean found = false;
        for (IConfigurationElement element : Platform.getExtensionRegistry()
            .getConfigurationElementsFor("org.eclipse.ui.startup"))
        {
            if ("ru.jimmo.edt.sonarq.ui.SonarqStartup".equals(element.getAttribute("class")))
            {
                found = true;
            }
        }
        assertTrue(found);
    }

    private static Set<String> extensionIds(String point)
    {
        Set<String> ids = new HashSet<>();
        for (IConfigurationElement element : Platform.getExtensionRegistry().getConfigurationElementsFor(point))
        {
            String id = element.getAttribute("id");
            if (id != null && id.startsWith(SonarqPlugin.PLUGIN_ID))
            {
                ids.add(id);
            }
        }
        return ids;
    }
}
