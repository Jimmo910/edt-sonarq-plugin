/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui;

import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.core.runtime.IConfigurationElement;
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
        assertTrue(extensionIds("org.eclipse.ui.preferencePages") //$NON-NLS-1$
            .contains("ru.jimmo.edt.sonarq.ui.preferences.main")); //$NON-NLS-1$
    }

    @Test
    public void propertyPageIsRegistered()
    {
        assertTrue(extensionIds("org.eclipse.ui.propertyPages") //$NON-NLS-1$
            .contains("ru.jimmo.edt.sonarq.ui.properties.project")); //$NON-NLS-1$
    }

    private static Set<String> extensionIds(String point)
    {
        Set<String> ids = new HashSet<>();
        for (IConfigurationElement element : Platform.getExtensionRegistry().getConfigurationElementsFor(point))
        {
            String id = element.getAttribute("id"); //$NON-NLS-1$
            if (id != null && id.startsWith(SonarqPlugin.PLUGIN_ID))
            {
                ids.add(id);
            }
        }
        return ids;
    }
}
