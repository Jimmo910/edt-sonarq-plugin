/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.analysis;

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
     * @param process the process to stop, not {@code null}
     */
    public static void terminate(Process process)
    {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        if (!awaitExit(process))
        {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            awaitExit(process);
        }
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
