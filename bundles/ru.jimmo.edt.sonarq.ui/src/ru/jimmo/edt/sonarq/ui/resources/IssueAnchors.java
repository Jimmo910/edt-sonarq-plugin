/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.resources;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;

import ru.jimmo.edt.sonarq.core.anchors.AnchorIndex;
import ru.jimmo.edt.sonarq.core.anchors.AnchorRecord;
import ru.jimmo.edt.sonarq.core.model.IssueSnapshot;
import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.core.suppress.LineAnchor;
import ru.jimmo.edt.sonarq.ui.markers.IssueMarkers;
import ru.jimmo.edt.sonarq.ui.views.IssueEntry;
import ru.jimmo.edt.sonarq.ui.views.IssueTreeBuilder;

/**
 * Fills in the {@link SonarIssue#lineAnchor()} of freshly loaded issues by reading the workspace files they
 * were reported on.
 *
 * <p>This is the one place that turns "line 42 of this file" into something that can be verified before the
 * quick-suppress edits the user's source. It runs where the issues are mapped to files - in the refresh job
 * and in the marker synchronization job, both background - and never on the UI thread: each referenced file
 * is read once, whatever the number of issues in it.
 *
 * <p>Three sources of anchors, in this order:
 * <ol>
 * <li>the anchor an earlier generation already recorded for the same issue, carried over from the workspace
 * markers of the file. This is what survives a server-mode refresh: SonarQube reports the line numbers of its
 * last analysis, which know nothing about a suppression written locally since, so re-fingerprinting "the line
 * the server named" would faithfully record the wrong line;</li>
 * <li>the anchor the caller already knew for the same issue key - in practice the issues view's previous
 * snapshot. Markers are not always there to carry an anchor: switching editor markers off
 * ({@code PreferenceConstants#PREF_SHOW_MARKERS}) leaves the file with none, and that is precisely the
 * configuration in which the view's own context menu is the <em>only</em> way to suppress an issue, because
 * the Problems-view quick fix needs a marker to hang off. Without this source the safety net used to
 * disappear exactly where it was the last one left;</li>
 * <li>the current text of the reported line.</li>
 * </ol>
 *
 * <p>A carried anchor - from either source - is only kept while the code it describes is still findable near
 * the reported line ({@link LineAnchor#isFindable}): otherwise that code is really gone, and holding on to
 * the anchor would make the issue permanently unsuppressable. "Findable" deliberately includes the refusals
 * that did find the anchored text but would not edit on it ({@link LineAnchor#AMBIGUOUS},
 * {@link LineAnchor#WEAK_EVIDENCE}): the code is still there, and replacing a checkable anchor with a fresh
 * fingerprint of whatever line the server happened to name would turn a safe refusal into a confident edit
 * of a line nobody verified.
 *
 * <p>Anything that cannot be read - a file missing from the resource tree, an I/O failure, a line beyond the
 * end of the file - leaves the anchor empty. That is not a silent fallback to the pre-anchor behaviour: an
 * issue with no anchor cannot be quick-suppressed at all (see {@link LineAnchor#NO_ANCHOR}), and the user is
 * told to refresh the issues, because the alternative is rewriting source at a line number nobody checked.
 *
 * <h2>The persisted index</h2>
 *
 * <p>The two carried sources above are both <em>session</em> memory: markers are transient and are not
 * created at all with editor markers switched off, and a view's snapshot dies with the view. The refresh path
 * therefore hands in an {@link AnchorIndex} loaded from
 * {@code ru.jimmo.edt.sonarq.core.anchors.AnchorIndexStore}, which outlives both and an EDT restart with
 * them, and {@link #reconcile} treats it as the authority. A stored record also carries the line the anchor
 * was last found at, so the search can start there as well as at the line the analysis reported - and, when
 * it is found, the issue is <em>moved</em> onto the resolved line, which is what puts the number on screen,
 * the marker and the next suppression back in step with the file after the server has replayed the numbers of
 * its last analysis.
 */
public final class IssueAnchors
{
    private IssueAnchors()
    {
    }

    /**
     * Returns the anchors of a snapshot's issues, ready to be handed to the next refresh's
     * {@link #anchor(IProject, String, String, IssueSnapshot, Map)} as what was known before it.
     *
     * <p>Cheap and free of any file access, so a caller on the UI thread (the issues view, reading its own
     * snapshot field before scheduling a refresh) can build it without leaving that thread.
     *
     * @param snapshot the snapshot to read, may be {@code null} - a view that has not loaded anything yet
     *     knows no anchors
     * @return issue key to anchor, for the issues that carry one; never {@code null}
     */
    public static Map<String, String> anchorsOf(IssueSnapshot snapshot)
    {
        if (snapshot == null)
        {
            return Map.of();
        }
        Map<String, String> anchors = new HashMap<>();
        for (SonarIssue issue : snapshot.issues())
        {
            if (!issue.lineAnchor().isEmpty())
            {
                anchors.put(issue.key(), issue.lineAnchor());
            }
        }
        return anchors;
    }

    /**
     * Returns a copy of {@code snapshot} whose issues carry line anchors.
     *
     * @param project the EDT project the issues belong to, not {@code null}
     * @param projectKey the SonarQube project key used to map component keys to paths, not {@code null}
     * @param pathPrefix the repository path prefix, may be {@code null}
     * @param snapshot the freshly loaded snapshot, not {@code null}
     * @return an anchored copy, or {@code snapshot} itself when there was nothing to anchor
     */
    public static IssueSnapshot anchor(IProject project, String projectKey, String pathPrefix,
        IssueSnapshot snapshot)
    {
        return anchor(project, projectKey, pathPrefix, snapshot, Map.of());
    }

    /**
     * Returns a copy of {@code snapshot} whose issues carry line anchors, preferring the anchors already
     * known for those issues over a fresh fingerprint of the reported line.
     *
     * @param project the EDT project the issues belong to, not {@code null}
     * @param projectKey the SonarQube project key used to map component keys to paths, not {@code null}
     * @param pathPrefix the repository path prefix, may be {@code null}
     * @param snapshot the freshly loaded snapshot, not {@code null}
     * @param knownAnchors issue key to the anchor a previous generation recorded (see {@link #anchorsOf}),
     *     not {@code null}; may be empty
     * @return an anchored copy, or {@code snapshot} itself when there was nothing to anchor
     */
    public static IssueSnapshot anchor(IProject project, String projectKey, String pathPrefix,
        IssueSnapshot snapshot, Map<String, String> knownAnchors)
    {
        return rebuild(snapshot, anchor(project,
            IssueTreeBuilder.toEntries(snapshot.issues(), projectKey, pathPrefix), knownAnchors));
    }

    /**
     * Anchors a freshly loaded snapshot against the plug-in's persisted anchor memory, and brings that memory
     * up to date with what this analysis reported.
     *
     * <p>The one place a refresh's issues are anchored, so that the issues view and the marker
     * synchronization consume an already-anchored snapshot rather than each anchoring it again from whatever
     * happens to be in reach. Runs in the refresh job's thread: it reads every file the issues point at, and
     * so must never be on the UI thread.
     *
     * <p>What it does to {@code index}: every issue it saw is recorded (or re-confirmed) there, and
     * {@link AnchorIndex#sweep} then drops what this analysis did not confirm - immediately for a complete
     * snapshot, after a grace period for a truncated one, which proves nothing about what it left out. The
     * caller persists the result.
     *
     * @param project the EDT project the issues belong to, not {@code null}
     * @param projectKey the SonarQube project key used to map component keys to paths, not {@code null}
     * @param pathPrefix the repository path prefix, may be {@code null}
     * @param snapshot the freshly loaded snapshot, not {@code null}
     * @param knownAnchors issue key to the anchor a previous generation recorded (see {@link #anchorsOf}),
     *     not {@code null}; consulted only for issues the index has never heard of
     * @param index the persisted anchor memory of this scope, mutated in place, not {@code null}
     * @param nowMillis the current time in epoch milliseconds, recorded on every confirmed record
     * @return an anchored copy whose issues sit on the lines their anchors resolved to, or {@code snapshot}
     *     itself when nothing changed
     */
    public static IssueSnapshot reconcile(IProject project, String projectKey, String pathPrefix,
        IssueSnapshot snapshot, Map<String, String> knownAnchors, AnchorIndex index, long nowMillis)
    {
        Map<String, Set<String>> seen = new HashMap<>();
        List<IssueEntry> anchored = reconcile(project,
            IssueTreeBuilder.toEntries(snapshot.issues(), projectKey, pathPrefix), knownAnchors, index, seen,
            nowMillis);
        index.sweep(seen, !snapshot.truncated(), nowMillis);
        index.touch(nowMillis);
        return rebuild(snapshot, anchored);
    }

    /**
     * Rebuilds a snapshot around reconciled entries, keeping everything else it knows.
     *
     * @param snapshot the snapshot the entries came from, not {@code null}
     * @param anchored the reconciled entries, in the snapshot's own order, not {@code null}
     * @return the rebuilt snapshot, or {@code snapshot} itself when no issue changed
     */
    private static IssueSnapshot rebuild(IssueSnapshot snapshot, List<IssueEntry> anchored)
    {
        List<SonarIssue> issues = new ArrayList<>(anchored.size());
        for (IssueEntry entry : anchored)
        {
            issues.add(entry.issue());
        }
        if (issues.equals(snapshot.issues()))
        {
            return snapshot;
        }
        return new IssueSnapshot(snapshot.query(), issues, snapshot.serverTotal(), snapshot.loadedAt());
    }

    /**
     * Returns a copy of {@code entries} whose issues carry line anchors.
     *
     * <p>Idempotent and cheap to repeat: an entry whose issue already has an anchor is left alone, and a file
     * none of whose entries need anchoring is never opened. That is what lets both the refresh path and the
     * marker synchronization path call this without reading every module twice.
     *
     * @param project the EDT project the entries belong to, not {@code null}
     * @param entries the mapped entries, not {@code null}
     * @return the anchored entries, in the same order, never {@code null}
     */
    public static List<IssueEntry> anchor(IProject project, List<IssueEntry> entries)
    {
        return anchor(project, entries, Map.of());
    }

    /**
     * Returns a copy of {@code entries} whose issues carry line anchors, preferring the anchors already known
     * for those issues over a fresh fingerprint of the reported line.
     *
     * <p>Idempotent and cheap to repeat: an entry whose issue already has an anchor is left alone, and a file
     * none of whose entries need anchoring is never opened. That is what lets both the refresh path and the
     * marker synchronization path call this without reading every module twice.
     *
     * @param project the EDT project the entries belong to, not {@code null}
     * @param entries the mapped entries, not {@code null}
     * @param knownAnchors issue key to the anchor a previous generation recorded (see {@link #anchorsOf}),
     *     not {@code null}; may be empty
     * @return the anchored entries, in the same order, never {@code null}
     */
    public static List<IssueEntry> anchor(IProject project, List<IssueEntry> entries,
        Map<String, String> knownAnchors)
    {
        return reconcile(project, entries, knownAnchors, null, new HashMap<>(), 0);
    }

    /**
     * The one pass that turns mapped entries into anchored ones, against the persisted memory when there is
     * any.
     *
     * @param project the EDT project the entries belong to, not {@code null}
     * @param entries the mapped entries, not {@code null}
     * @param knownAnchors issue key to the anchor a previous generation recorded, not {@code null}
     * @param index the persisted anchor memory, mutated in place, or {@code null} for a pass with no memory
     *     behind it (the session-only overloads above)
     * @param seen collects the issue keys reconciled per path, so the caller can sweep what was not, not
     *     {@code null}
     * @param nowMillis the current time in epoch milliseconds
     * @return the reconciled entries, in the same order, never {@code null}
     */
    private static List<IssueEntry> reconcile(IProject project, List<IssueEntry> entries,
        Map<String, String> knownAnchors, AnchorIndex index, Map<String, Set<String>> seen, long nowMillis)
    {
        Map<String, List<Integer>> byPath = groupAnchorable(entries, index != null);
        if (byPath.isEmpty())
        {
            return entries;
        }
        List<IssueEntry> result = new ArrayList<>(entries);
        for (Map.Entry<String, List<Integer>> file : byPath.entrySet())
        {
            anchorOneFile(project.getFile(file.getKey()), file.getKey(), result, file.getValue(), knownAnchors,
                index, seen, nowMillis);
        }
        return result;
    }

    /**
     * Groups the positions of the entries to reconcile by their project-relative path.
     *
     * @param entries the mapped entries, not {@code null}
     * @param withIndex whether a persisted index is being maintained, in which case an entry that already
     *     carries an anchor is still visited - not to re-fingerprint it, but so that the memory records it as
     *     confirmed by this analysis instead of sweeping it away as gone
     * @return path to positions in {@code entries}, never {@code null}; empty when there is nothing to do
     */
    private static Map<String, List<Integer>> groupAnchorable(List<IssueEntry> entries, boolean withIndex)
    {
        Map<String, List<Integer>> byPath = new LinkedHashMap<>();
        for (int index = 0; index < entries.size(); index++)
        {
            IssueEntry entry = entries.get(index);
            SonarIssue issue = entry.issue();
            if (entry.relativePath() == null || issue.line() <= 0
                || (!withIndex && !issue.lineAnchor().isEmpty()))
            {
                // Unmapped, file-level, or - with no memory to maintain - already anchored.
                continue;
            }
            byPath.computeIfAbsent(entry.relativePath(), path -> new ArrayList<>()).add(index);
        }
        return byPath;
    }

    /**
     * Reconciles every listed entry of one file, reading that file exactly once.
     *
     * @param file the file the entries were reported on, not {@code null}
     * @param path the project-relative path of {@code file}, not {@code null}
     * @param entries the full entry list, not {@code null}, mutated in place at {@code positions}
     * @param positions the positions in {@code entries} to reconcile, not {@code null}
     * @param knownAnchors issue key to the anchor a previous generation recorded, not {@code null}
     * @param index the persisted anchor memory, or {@code null}
     * @param seen collects the reconciled issue keys per path, not {@code null}
     * @param nowMillis the current time in epoch milliseconds
     */
    private static void anchorOneFile(IFile file, String path, List<IssueEntry> entries,
        List<Integer> positions, Map<String, String> knownAnchors, AnchorIndex index,
        Map<String, Set<String>> seen, long nowMillis)
    {
        IDocument document = read(file);
        if (document == null)
        {
            return;
        }
        // The markers of the file first, the caller's own memory second: both describe the same previous
        // generation and normally agree, but a marker is written by the synchronization that has actually
        // reached the file, so it is the more authoritative of the two. Both rank below the persisted index,
        // which is the only one of the three that survives a restart.
        Map<String, String> carried = new HashMap<>(knownAnchors);
        carried.putAll(anchorsOfExistingMarkers(file));
        for (int position : positions)
        {
            IssueEntry entry = entries.get(position);
            SonarIssue reconciled = reconcileOne(document, entry.issue(), carried, index, path, nowMillis);
            if (reconciled != entry.issue())
            {
                entries.set(position, new IssueEntry(reconciled, entry.relativePath()));
            }
            if (index != null && !reconciled.lineAnchor().isEmpty())
            {
                seen.computeIfAbsent(path, key -> new HashSet<>()).add(reconciled.key());
            }
        }
    }

    /**
     * Reconciles one issue against the memory of it, and records what came of that.
     *
     * <p>The remembered case is the one that matters:
     * <ul>
     * <li>the stored anchor is looked for around <em>both</em> the line it was last found at and the line
     * this analysis reported. Found, and found in one place: the anchor is kept, the issue moves onto the
     * line it was found at, and the hint follows;</li>
     * <li>not found, or found ambiguously: the stored anchor is kept anyway and the issue stays where the
     * analysis put it. The suppression will then refuse, which is the correct outcome - the code the issue
     * was about cannot be identified in the file any more. Re-fingerprinting "whatever is on the reported
     * line" here is precisely how a safe refusal becomes a confident edit of a line nobody verified, so it is
     * not done, and the hint is left alone because the reported line is not evidence about where the code
     * went.</li>
     * </ul>
     *
     * @param document the current file content, not {@code null}
     * @param issue the issue to reconcile, not {@code null}
     * @param carried the session anchors of this file's issues by key, not {@code null}
     * @param index the persisted anchor memory, or {@code null}
     * @param path the project-relative path of the file, not {@code null}
     * @param nowMillis the current time in epoch milliseconds
     * @return the reconciled issue, or {@code issue} itself when nothing about it changed
     */
    private static SonarIssue reconcileOne(IDocument document, SonarIssue issue,
        Map<String, String> carried, AnchorIndex index, String path, long nowMillis)
    {
        AnchorRecord remembered = index != null ? index.find(path, issue.key()) : null;
        if (remembered != null)
        {
            int resolved = locate(document, remembered, issue.line());
            index.put(path, remembered.seenAt(resolved, nowMillis));
            SonarIssue anchored = issue.withAnchor(remembered.anchor());
            return LineAnchor.isResolved(resolved) ? anchored.withLine(resolved) : anchored;
        }
        String anchor = issue.lineAnchor().isEmpty()
            ? anchorFor(document, issue, carried.get(issue.key()))
            : issue.lineAnchor();
        if (anchor.isEmpty())
        {
            // Nothing to remember and nothing to verify against: the issue stays unsuppressable until a
            // refresh finds its line again.
            return issue;
        }
        if (index != null)
        {
            index.put(path, new AnchorRecord(issue.key(), issue.ruleKey(), anchor, issue.line(), nowMillis));
        }
        return issue.lineAnchor().isEmpty() ? issue.withAnchor(anchor) : issue;
    }

    /**
     * Finds the line a remembered anchor names, searching around the hint as well as around the line the
     * analysis reported.
     *
     * <p>Two windows because the two numbers answer different questions and either may be the stale one: the
     * hint is where the plug-in last saw the code, the reported line is where the analysis last saw it, and
     * after a local suppression they differ by exactly the lines that suppression inserted. When both resolve
     * to the same line there is one answer; when they resolve to two different lines the file holds two
     * indistinguishable copies of the anchored code, which is not an answer at all.
     *
     * @param document the current file content, not {@code null}
     * @param remembered the stored record, not {@code null}
     * @param reportedLine the 1-based line this analysis reported
     * @return the 1-based line to use, or one of {@link LineAnchor}'s refusals
     */
    private static int locate(IDocument document, AnchorRecord remembered, int reportedLine)
    {
        int fromReported = LineAnchor.resolveLine(document, reportedLine, remembered.anchor());
        if (remembered.lastKnownLine() <= 0 || remembered.lastKnownLine() == reportedLine)
        {
            return fromReported;
        }
        int fromHint = LineAnchor.resolveLine(document, remembered.lastKnownLine(), remembered.anchor());
        if (LineAnchor.isResolved(fromHint) && LineAnchor.isResolved(fromReported))
        {
            return fromHint == fromReported ? fromHint : LineAnchor.AMBIGUOUS;
        }
        return LineAnchor.isResolved(fromHint) ? fromHint : fromReported;
    }

    /**
     * Picks the anchor to record for one issue: the one an earlier generation recorded, while it still
     * describes a line near the reported one, and the current text of the reported line otherwise.
     *
     * @param document the current file content, not {@code null}
     * @param issue the issue to anchor, not {@code null}
     * @param carried the anchor a previous generation recorded for the same issue - on a marker of this file
     *     or in the caller's own snapshot - or {@code null}
     * @return the anchor to record, or {@link LineAnchor#NONE} when the reported line is beyond the file
     */
    private static String anchorFor(IDocument document, SonarIssue issue, String carried)
    {
        if (carried != null && !carried.isEmpty()
            && LineAnchor.isFindable(LineAnchor.resolveLine(document, issue.line(), carried)))
        {
            return carried;
        }
        return LineAnchor.of(document, issue.line());
    }

    /**
     * Reads the issue markers already on a file, mapping each issue key to the anchor it carries.
     *
     * <p>The markers of the previous generation are still in place while this runs: the marker
     * synchronization that replaces them is a later step of the same refresh.
     *
     * @param file the file to read the markers of, not {@code null}
     * @return issue key to anchor, never {@code null}; empty when the file has no issue markers (which is
     *     also the case whenever the user switched editor markers off)
     */
    private static Map<String, String> anchorsOfExistingMarkers(IFile file)
    {
        Map<String, String> anchors = new HashMap<>();
        try
        {
            for (IMarker marker : file.findMarkers(IssueMarkers.MARKER_TYPE, true, IResource.DEPTH_ZERO))
            {
                String key = marker.getAttribute(IssueMarkers.ATTR_ISSUE_KEY, ""); //$NON-NLS-1$
                String anchor = marker.getAttribute(IssueMarkers.ATTR_LINE_ANCHOR, ""); //$NON-NLS-1$
                if (!key.isEmpty() && !anchor.isEmpty())
                {
                    anchors.put(key, anchor);
                }
            }
        }
        catch (CoreException e)
        {
            Platform.getLog(IssueAnchors.class).warn(e.getMessage(), e);
        }
        return anchors;
    }

    /**
     * Reads a workspace file into a document, in its own charset.
     *
     * <p>Deliberately does not refresh the resource tree the way
     * {@link WorkspaceFiles#existsAfterRefresh} does: this runs inside the refresh job, whose scheduling rule
     * contains no resource rule, and a refresh from there is rejected outright by the job manager. A file the
     * workspace does not know yet simply stays unanchored - the marker synchronization refreshes it a moment
     * later, and the next refresh anchors it.
     *
     * @param file the file to read, not {@code null}
     * @return the file content, or {@code null} when it could not be read
     */
    private static IDocument read(IFile file)
    {
        if (!file.exists())
        {
            return null;
        }
        try (InputStream stream = file.getContents(true))
        {
            return new Document(new String(stream.readAllBytes(), Charset.forName(file.getCharset())));
        }
        catch (CoreException | IOException | RuntimeException e)
        {
            Platform.getLog(IssueAnchors.class).warn(e.getMessage(), e);
            return null;
        }
    }
}
