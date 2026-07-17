/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.analysis;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Helpers for stopping external analyzer processes cleanly. */
public final class Processes
{
    private static final long TERMINATE_WAIT_MILLIS = 3000L;

    private Processes()
    {
    }

    /**
     * Terminates a process and its descendants, escalating to a forcible kill if a graceful
     * {@code destroy()} does not stop the tree within {@value #TERMINATE_WAIT_MILLIS} ms. Never throws and
     * never blocks unbounded, so a scanner or analyzer that ignores {@code SIGTERM} cannot hang a cancelled
     * or interrupted job, and no child is left running (and, for the scanner, still holding the token) once
     * the waiting thread gives up.
     *
     * <p>The descendant handles are captured <em>before</em> the parent is destroyed, and any survivor is
     * force-killed independently of the parent's state: once the parent exits it may no longer report its
     * (now re-parented) children, so a descendant that ignores {@code SIGTERM} would otherwise be missed.
     *
     * @param process the process to stop, not {@code null}
     */
    public static void terminate(Process process)
    {
        List<ProcessHandle> descendants = process.descendants().toList();
        process.destroy();
        descendants.forEach(ProcessHandle::destroy);
        if (!awaitExit(process))
        {
            process.destroyForcibly();
        }
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        awaitExit(process);
    }

    private static boolean awaitExit(Process process)
    {
        try
        {
            return process.waitFor(TERMINATE_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return !process.isAlive();
        }
    }
}
