/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.scope;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.util.io.NullOutputStream;

/**
 * JGit-backed {@link ChangedLines}: diffs a base revision's tree against the working tree and reports the
 * new-side (working-copy) changed line ranges per work-tree-relative path.
 */
public final class GitChangedLines implements ChangedLines
{
    private final File workTreeRoot;

    private final Map<String, List<int[]>> ranges;

    /**
     * @param workTreeRoot the git work-tree root, not {@code null}
     * @param ranges 0-based {@code [begin, end)} new-side ranges keyed by {@code /}-separated path
     */
    private GitChangedLines(File workTreeRoot, Map<String, List<int[]>> ranges)
    {
        this.workTreeRoot = workTreeRoot;
        this.ranges = ranges;
    }

    /**
     * Finds the repository containing {@code anyDirInsideRepo}, resolves {@code baseRef} and diffs its tree
     * against the working tree, collecting the changed new-side line ranges. Any failure (not a repository,
     * unresolvable {@code baseRef} or IO error) yields an unavailable result so callers show all issues.
     *
     * @param anyDirInsideRepo a directory inside the repository work tree, not {@code null}
     * @param baseRef a branch, tag or commit to diff against, not {@code null}
     * @return the changed-lines lookup, never {@code null}
     */
    public static ChangedLines compute(File anyDirInsideRepo, String baseRef)
    {
        try (Repository repository = new FileRepositoryBuilder().findGitDir(anyDirInsideRepo).build())
        {
            ObjectId baseId = repository.resolve(baseRef);
            if (baseId == null)
            {
                return unavailable();
            }
            File workTree = repository.getWorkTree();
            return new GitChangedLines(workTree, collectRanges(repository, baseId));
        }
        // Broad catch: JGit signals "not a usable repo / bad ref" via unchecked exceptions
        // (RevisionSyntaxException, IllegalArgumentException from build(), NoWorkTreeException); all of
        // them must degrade to an unavailable result rather than propagate.
        catch (Exception e)
        {
            return unavailable();
        }
    }

    @Override
    public boolean available()
    {
        return true;
    }

    @Override
    public boolean isChanged(String repoRelativePath, int line)
    {
        List<int[]> list = ranges.get(repoRelativePath.replace('\\', '/'));
        if (list == null || list.isEmpty())
        {
            return false;
        }
        if (line <= 0)
        {
            return true;
        }
        int index = line - 1;
        for (int[] range : list)
        {
            if (index >= range[0] && index < range[1])
            {
                return true;
            }
        }
        return false;
    }

    @Override
    public File workTreeRoot()
    {
        return workTreeRoot;
    }

    /**
     * Diffs the {@code baseId} tree against the working tree and collects the changed new-side ranges.
     *
     * @param repository the open repository, not {@code null}
     * @param baseId the resolved base revision, not {@code null}
     * @return 0-based {@code [begin, end)} ranges keyed by {@code /}-separated new-side path
     * @throws IOException when the diff cannot be computed
     */
    private static Map<String, List<int[]>> collectRanges(Repository repository, ObjectId baseId)
        throws IOException
    {
        Map<String, List<int[]>> ranges = new HashMap<>();
        try (RevWalk revWalk = new RevWalk(repository);
            ObjectReader reader = repository.newObjectReader();
            DiffFormatter formatter = new DiffFormatter(NullOutputStream.INSTANCE))
        {
            RevTree baseTree = revWalk.parseCommit(baseId).getTree();
            CanonicalTreeParser oldSide = new CanonicalTreeParser();
            oldSide.reset(reader, baseTree);
            FileTreeIterator newSide = new FileTreeIterator(repository);
            formatter.setRepository(repository);
            formatter.setDetectRenames(true);
            for (DiffEntry entry : formatter.scan(oldSide, newSide))
            {
                collectEntry(ranges, formatter, entry);
            }
        }
        return ranges;
    }

    /**
     * Adds one diff entry's new-side ranges to {@code ranges}, skipping pure deletes.
     *
     * @param ranges the accumulating map, not {@code null}
     * @param formatter the diff formatter, not {@code null}
     * @param entry the diff entry, not {@code null}
     * @throws IOException when the file header cannot be read
     */
    private static void collectEntry(Map<String, List<int[]>> ranges, DiffFormatter formatter,
        DiffEntry entry) throws IOException
    {
        if (entry.getNewPath().equals(DiffEntry.DEV_NULL))
        {
            return;
        }
        List<int[]> list = new ArrayList<>();
        for (Edit edit : formatter.toFileHeader(entry).toEditList())
        {
            list.add(new int[] {edit.getBeginB(), edit.getEndB()});
        }
        if (!list.isEmpty())
        {
            ranges.put(entry.getNewPath(), list);
        }
    }

    /**
     * @return a {@link ChangedLines} whose {@link ChangedLines#available()} is {@code false}
     */
    private static ChangedLines unavailable()
    {
        return new ChangedLines()
        {
            @Override
            public boolean available()
            {
                return false;
            }

            @Override
            public boolean isChanged(String repoRelativePath, int line)
            {
                return false;
            }

            @Override
            public File workTreeRoot()
            {
                return null;
            }
        };
    }
}
