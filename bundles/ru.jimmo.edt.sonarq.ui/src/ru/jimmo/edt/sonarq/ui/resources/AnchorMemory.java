/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.resources;

import java.nio.file.Path;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import ru.jimmo.edt.sonarq.core.anchors.AnchorIndexStore;
import ru.jimmo.edt.sonarq.ui.Messages;
import ru.jimmo.edt.sonarq.ui.SonarqPlugin;

/**
 * The plug-in's persisted anchor memory, as the rest of the UI reaches it: where it lives, and how a
 * quick-suppress tells it what it just did to a file.
 *
 * <p>Two things are centralized here rather than repeated at the call sites. The first is the state location,
 * which only the activator knows. The second is that every write goes through a background job: both
 * suppression entry points - the issues view's context menu and the Problems-view quick fix - run on the UI
 * thread, and this project does no file I/O there.
 */
public final class AnchorMemory
{
    private AnchorMemory()
    {
    }

    /**
     * The store under the plug-in state location.
     *
     * @return the store, or {@code null} when the plug-in is not activated (which is only the case outside a
     *     running workbench; callers then simply keep no memory)
     */
    public static AnchorIndexStore store()
    {
        SonarqPlugin plugin = SonarqPlugin.getInstance();
        if (plugin == null)
        {
            return null;
        }
        return new AnchorIndexStore(Path.of(plugin.getStateLocation().toOSString()));
    }

    /**
     * Records that a quick-suppress has wrapped a line of one file: the suppressed issue is forgotten and the
     * memory of that file's other issues is renumbered for the two comment lines.
     *
     * <p>Runs whatever the state of editor markers, and whatever view is open: the memory this updates is the
     * plug-in's own, and the file grew by two lines regardless of who is watching. Scheduled rather than
     * executed, because both callers are on the UI thread; the work is a small read-modify-write of one JSON
     * file per scope, serialized against every other writer by the store's own lock.
     *
     * @param project the project the edited file belongs to, may be {@code null}, in which case nothing
     *     happens
     * @param path the project-relative path of the edited file, may be {@code null}, in which case nothing
     *     happens
     * @param issueKey the key of the suppressed issue, not {@code null}; empty renumbers without forgetting
     *     anything
     * @param codeLine the 1-based line the {@code -off}/{@code -on} pair was wrapped around, in the numbering
     *     the file had before the insertion
     */
    public static void suppressionApplied(IProject project, String path, String issueKey, int codeLine)
    {
        AnchorIndexStore store = store();
        if (project == null || path == null || store == null)
        {
            return;
        }
        String projectName = project.getName();
        Job job = new Job(Messages.AnchorMemoryJob_Name)
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    store.suppressionApplied(projectName, path, issueKey, codeLine);
                }
                catch (RuntimeException e)
                {
                    Platform.getLog(AnchorMemory.class).warn(e.getMessage(), e);
                }
                return Status.OK_STATUS;
            }
        };
        // No scheduling rule: this touches the plug-in state directory, not workspace resources, so it must
        // not queue behind - or block - the marker synchronization running on the project's resource rule.
        job.setSystem(true);
        job.schedule();
    }
}
