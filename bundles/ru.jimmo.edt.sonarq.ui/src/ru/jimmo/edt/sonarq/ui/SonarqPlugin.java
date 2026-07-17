/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui;

import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/**
 * The plug-in activator: holds the shared instance and the plug-in id.
 */
public class SonarqPlugin extends AbstractUIPlugin
{
    /** The plug-in id (equals the bundle symbolic name). */
    public static final String PLUGIN_ID = "ru.jimmo.edt.sonarq.ui"; //$NON-NLS-1$

    private static SonarqPlugin instance;

    /**
     * Returns the shared plug-in instance.
     *
     * @return the shared instance, or {@code null} before activation
     */
    public static SonarqPlugin getInstance()
    {
        return instance;
    }

    @Override
    public void start(BundleContext context) throws Exception
    {
        super.start(context);
        instance = this;
    }

    @Override
    public void stop(BundleContext context) throws Exception
    {
        SonarqStartup.shutdown();
        instance = null;
        super.stop(context);
    }
}
