/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The generation counter that decides whether an asynchronous result may still be applied to the SonarQube
 * Issues view.
 *
 * <p>Every refresh takes a generation of its own with {@link #start()} and carries it to its completion
 * callback, which applies the result only while {@link #isCurrent} holds - so a superseded refresh (a second
 * Refresh click, a project switch) cannot overwrite a newer one's tree or status.
 *
 * <p>A refresh is not the only thing that can make an in-flight result wrong. A quick-suppress edits the
 * file underneath a running analysis, and in local-analysis mode that analysis can take minutes on a large
 * configuration: its results describe the sources as they were <em>before</em> the two comment lines were
 * inserted, so applying them would put every line number in that file two lines off and re-open exactly the
 * desynchronization the suppression bookkeeping exists to prevent. {@link #invalidate()} retires the current
 * generation for that case, without pretending a new refresh has started.
 *
 * <p>The same counter fences the marker writes, one instance per project (see
 * {@link ru.jimmo.edt.sonarq.ui.markers.MarkerStateVersion}), which is why it is safe to use from any thread:
 * a marker synchronization job takes its version on the producer's thread and verifies it in its own, under
 * the project's scheduling rule. The view's own instance is still only ever touched on the UI thread.
 */
public final class RefreshGeneration
{
    private final AtomicLong current = new AtomicLong();

    /**
     * Opens a new generation for a refresh, or any other state, that is about to be published.
     *
     * @return the generation the work must carry to its completion callback
     */
    public long start()
    {
        return current.incrementAndGet();
    }

    /**
     * Retires the current generation, so results already in flight are dropped when they arrive, while work
     * scheduled after this call (which reads {@link #current()}) is still applied.
     */
    public void invalidate()
    {
        current.incrementAndGet();
    }

    /**
     * The generation an operation started right now belongs to.
     *
     * @return the current generation
     */
    public long current()
    {
        return current.get();
    }

    /**
     * Tells whether a result carrying {@code generation} may still be applied.
     *
     * @param generation the generation the result was started for
     * @return {@code true} when nothing has superseded or invalidated it since
     */
    public boolean isCurrent(long generation)
    {
        return generation == current.get();
    }
}
