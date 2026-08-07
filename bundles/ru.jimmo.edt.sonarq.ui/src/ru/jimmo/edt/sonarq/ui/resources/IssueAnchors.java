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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;

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
 * <p>Two sources of anchors, in this order:
 * <ol>
 * <li>the anchor an earlier generation already recorded for the same issue, carried over from the workspace
 * markers of the file. This is what survives a server-mode refresh: SonarQube reports the line numbers of its
 * last analysis, which know nothing about a suppression written locally since, so re-fingerprinting "the line
 * the server named" would faithfully record the wrong line. The carried anchor is only kept while it is still
 * findable near the reported line ({@link LineAnchor#resolveLine}) - otherwise the code it described is
 * really gone, and holding on to it would make the issue permanently unsuppressable;</li>
 * <li>the current text of the reported line.</li>
 * </ol>
 *
 * <p>Anything that cannot be read - a file missing from the resource tree, an I/O failure, a line beyond the
 * end of the file - leaves the anchor empty, which means "unverifiable" and makes the quick-suppress fall
 * back to its pre-anchor behaviour rather than refuse.
 */
public final class IssueAnchors
{
    private IssueAnchors()
    {
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
        List<IssueEntry> anchored =
            anchor(project, IssueTreeBuilder.toEntries(snapshot.issues(), projectKey, pathPrefix));
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
        Map<String, List<Integer>> byPath = groupAnchorable(entries);
        if (byPath.isEmpty())
        {
            return entries;
        }
        List<IssueEntry> result = new ArrayList<>(entries);
        for (Map.Entry<String, List<Integer>> file : byPath.entrySet())
        {
            anchorOneFile(project.getFile(file.getKey()), result, file.getValue());
        }
        return result;
    }

    /**
     * Groups the positions of the entries that still need an anchor by their project-relative path.
     *
     * @param entries the mapped entries, not {@code null}
     * @return path to positions in {@code entries}, never {@code null}; empty when nothing needs anchoring
     */
    private static Map<String, List<Integer>> groupAnchorable(List<IssueEntry> entries)
    {
        Map<String, List<Integer>> byPath = new LinkedHashMap<>();
        for (int index = 0; index < entries.size(); index++)
        {
            IssueEntry entry = entries.get(index);
            SonarIssue issue = entry.issue();
            if (entry.relativePath() == null || issue.line() <= 0 || !issue.lineAnchor().isEmpty())
            {
                // Unmapped, file-level, or already anchored: nothing a file read could add.
                continue;
            }
            byPath.computeIfAbsent(entry.relativePath(), path -> new ArrayList<>()).add(index);
        }
        return byPath;
    }

    /**
     * Anchors every listed entry of one file, reading that file exactly once.
     *
     * @param file the file the entries were reported on, not {@code null}
     * @param entries the full entry list, not {@code null}, mutated in place at {@code positions}
     * @param positions the positions in {@code entries} to anchor, not {@code null}
     */
    private static void anchorOneFile(IFile file, List<IssueEntry> entries, List<Integer> positions)
    {
        IDocument document = read(file);
        if (document == null)
        {
            return;
        }
        Map<String, String> carried = anchorsOfExistingMarkers(file);
        for (int position : positions)
        {
            IssueEntry entry = entries.get(position);
            SonarIssue issue = entry.issue();
            String anchor = anchorFor(document, issue, carried.get(issue.key()));
            if (!anchor.isEmpty())
            {
                entries.set(position, new IssueEntry(issue.withAnchor(anchor), entry.relativePath()));
            }
        }
    }

    /**
     * Picks the anchor to record for one issue: the one an earlier generation recorded, while it still
     * describes a line near the reported one, and the current text of the reported line otherwise.
     *
     * @param document the current file content, not {@code null}
     * @param issue the issue to anchor, not {@code null}
     * @param carried the anchor an existing marker of the same issue carries, or {@code null}
     * @return the anchor to record, or {@link LineAnchor#NONE} when the reported line is beyond the file
     */
    private static String anchorFor(IDocument document, SonarIssue issue, String carried)
    {
        if (carried != null && !carried.isEmpty()
            && LineAnchor.resolveLine(document, issue.line(), carried) != LineAnchor.NOT_FOUND)
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
