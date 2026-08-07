/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.resources;

import java.util.ArrayDeque;
import java.util.Deque;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;

/**
 * Reconciles a single workspace file handle with the file system.
 *
 * <p>A file written behind the workspace's back - a git checkout run outside EDT, a generator, an export -
 * exists on disk but not in the resource tree, so {@link IFile#exists()} answers {@code false} for it until
 * something refreshes it. Every feature of this plug-in that starts from an issue's path (marker creation,
 * opening the editor at the issue line, inserting a suppression comment) hits exactly that case, so the
 * reconciliation lives here rather than in one of them.
 */
public final class WorkspaceFiles
{
    private WorkspaceFiles()
    {
    }

    /**
     * Tells whether the file exists, refreshing it into the resource tree first when the workspace does not
     * know it yet.
     *
     * <p>Walks up from {@code file} to the deepest ancestor the workspace already knows, then refreshes the
     * missing handles back down, each with {@link IResource#DEPTH_ZERO}: creating a resource requires its
     * parent to already be in the tree, and a file written behind the workspace's back may sit in a folder
     * the workspace does not know either. Each step only stats one path, so nothing here scales with the size
     * of the project - unlike a full-depth project refresh, which on the large 1C configurations this plug-in
     * targets is seconds of I/O under the project scheduling rule.
     *
     * <p>Stops as soon as a refreshed handle still does not exist: that path is genuinely absent from disk
     * too, so the answer is a definite {@code false} rather than a stale resource tree.
     *
     * @param file the file handle to check, not {@code null}
     * @return {@code true} when the file exists in the resource tree, after refreshing it if needed
     * @throws CoreException if a refresh fails
     */
    public static boolean existsAfterRefresh(IFile file) throws CoreException
    {
        if (file.exists())
        {
            return true;
        }
        Deque<IResource> missing = new ArrayDeque<>();
        IResource resource = file;
        while (resource != null && resource.getType() != IResource.PROJECT && !resource.exists())
        {
            missing.addFirst(resource);
            resource = resource.getParent();
        }
        for (IResource handle : missing)
        {
            // No monitor: each of these refreshes is a single stat of one path, and a caller that runs inside
            // a workspace operation already has a monitor of its own for the operation as a whole.
            handle.refreshLocal(IResource.DEPTH_ZERO, null);
            if (!handle.exists())
            {
                return false;
            }
        }
        return file.exists();
    }
}
