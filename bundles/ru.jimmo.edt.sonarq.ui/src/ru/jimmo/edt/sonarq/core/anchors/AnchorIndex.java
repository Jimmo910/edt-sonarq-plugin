/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.anchors;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One scope's anchor memory, held in memory: project-relative file path to the records of the issues
 * reported in that file.
 *
 * <p>Not synchronized. One instance belongs to one refresh (loaded before the analysis, handed to the
 * reconciliation, written back afterwards) or to one maintenance operation; {@link AnchorIndexStore}
 * serializes the load-modify-write sequences that share a file.
 *
 * <h2>Bounds</h2>
 *
 * <p>This is a cache with a job to do, not a database. Both issue providers already cap one snapshot at
 * 10 000 issues, so an index that tracked every issue ever seen would only grow through issues that are
 * gone - which is what {@link #sweep} exists to remove. {@link #MAX_RECORDS} is the backstop for the case
 * sweeping cannot cover: a truncated snapshot never proves an issue is gone, so unseen records have to be
 * kept for a while, and "a while" needs a ceiling.
 */
public final class AnchorIndex
{
    /**
     * How many records one scope may hold. Twice the 10 000-issue snapshot cap, so a complete snapshot and
     * the tail of the previous one both fit without evicting anything a running session still needs.
     */
    public static final int MAX_RECORDS = 20_000;

    /**
     * How long a record that no analysis has confirmed is kept, in milliseconds.
     *
     * <p>Only reachable through a truncated snapshot: a complete one proves the issue is gone and
     * {@link #sweep} drops it immediately. A truncated one proves nothing - the issue may simply have fallen
     * past the cap - so its record has to survive, and thirty days is long enough to cover a branch somebody
     * comes back to and short enough that a renamed file's memory does not outlive the file.
     */
    public static final long UNSEEN_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000;

    private final AnchorScope scope;

    /** Path to issue key to record. Insertion-ordered so a written file is stable between saves. */
    private final Map<String, Map<String, AnchorRecord>> byPath = new LinkedHashMap<>();

    private long touchedAt;

    /**
     * Creates an empty index for a scope.
     *
     * @param scope the scope this memory belongs to, not {@code null}
     */
    public AnchorIndex(AnchorScope scope)
    {
        this.scope = scope;
    }

    /**
     * @return the scope this memory belongs to, never {@code null}
     */
    public AnchorScope scope()
    {
        return scope;
    }

    /**
     * @return when this index was last written or confirmed, in epoch milliseconds; {@code 0} when never
     */
    public long touchedAt()
    {
        return touchedAt;
    }

    /**
     * Records when this index was last used, which is what the scope-level expiry is measured from.
     *
     * @param nowMillis the current time in epoch milliseconds
     */
    public void touch(long nowMillis)
    {
        touchedAt = nowMillis;
    }

    /**
     * Looks one issue's memory up.
     *
     * @param path the project-relative file path, not {@code null}
     * @param issueKey the issue key, not {@code null}
     * @return the record, or {@code null} when this scope has never anchored that issue in that file
     */
    public AnchorRecord find(String path, String issueKey)
    {
        Map<String, AnchorRecord> file = byPath.get(path);
        return file != null ? file.get(issueKey) : null;
    }

    /**
     * Stores or replaces one issue's memory.
     *
     * @param path the project-relative file path, not {@code null}
     * @param record the record to store, not {@code null}
     */
    public void put(String path, AnchorRecord record)
    {
        byPath.computeIfAbsent(path, key -> new LinkedHashMap<>()).put(record.issueKey(), record);
    }

    /**
     * Forgets one issue.
     *
     * @param path the project-relative file path, not {@code null}
     * @param issueKey the issue key, not {@code null}
     * @return {@code true} when a record was removed
     */
    public boolean remove(String path, String issueKey)
    {
        Map<String, AnchorRecord> file = byPath.get(path);
        if (file == null || file.remove(issueKey) == null)
        {
            return false;
        }
        if (file.isEmpty())
        {
            byPath.remove(path);
        }
        return true;
    }

    /**
     * Renumbers every record of one file for the two comment lines a quick-suppress just inserted.
     *
     * @param path the project-relative path of the edited file, not {@code null}
     * @param codeLine the 1-based line the {@code -off}/{@code -on} pair was wrapped around, in the numbering
     *     the file had before the insertion
     * @return how many records were renumbered
     */
    public int shiftFile(String path, int codeLine)
    {
        Map<String, AnchorRecord> file = byPath.get(path);
        if (file == null)
        {
            return 0;
        }
        int shifted = 0;
        for (Map.Entry<String, AnchorRecord> entry : file.entrySet())
        {
            AnchorRecord moved = entry.getValue().shiftedFor(codeLine);
            if (moved != entry.getValue())
            {
                entry.setValue(moved);
                shifted++;
            }
        }
        return shifted;
    }

    /**
     * Drops the records this analysis did not confirm, and then whatever is left over the cap.
     *
     * <p>The two cases are not the same question:
     * <ul>
     * <li>a <em>complete</em> snapshot listed every open issue of the scope, so a record it did not mention
     * describes an issue that is fixed, suppressed or no longer reported - it is dead memory and goes at
     * once;</li>
     * <li>a <em>truncated</em> snapshot listed the first 10 000 of more, so its silence about a record means
     * nothing at all. Dropping those would quietly delete the anchors of everything past the cap, and the
     * next suppression down there would be back to trusting a line number. They are kept until
     * {@link #UNSEEN_TTL_MILLIS} passes without a confirmation.</li>
     * </ul>
     *
     * @param seen the issue keys this analysis reported, by project-relative path, not {@code null}
     * @param complete whether the snapshot listed every issue of the scope (see
     *     {@code IssueSnapshot#truncated()})
     * @param nowMillis the current time in epoch milliseconds
     */
    public void sweep(Map<String, Set<String>> seen, boolean complete, long nowMillis)
    {
        Iterator<Map.Entry<String, Map<String, AnchorRecord>>> files = byPath.entrySet().iterator();
        while (files.hasNext())
        {
            Map.Entry<String, Map<String, AnchorRecord>> file = files.next();
            Set<String> seenHere = seen.getOrDefault(file.getKey(), Set.of());
            file.getValue().entrySet()
                .removeIf(record -> !seenHere.contains(record.getKey())
                    && (complete || nowMillis - record.getValue().lastSeen() > UNSEEN_TTL_MILLIS));
            if (file.getValue().isEmpty())
            {
                files.remove();
            }
        }
        evictDownTo(MAX_RECORDS);
    }

    /**
     * Drops the least recently confirmed records until at most {@code limit} are left.
     *
     * @param limit how many records may remain, must not be negative
     */
    public void evictDownTo(int limit)
    {
        int excess = size() - limit;
        if (excess <= 0)
        {
            return;
        }
        List<PathRecord> all = new ArrayList<>();
        for (Map.Entry<String, Map<String, AnchorRecord>> file : byPath.entrySet())
        {
            for (AnchorRecord record : file.getValue().values())
            {
                all.add(new PathRecord(file.getKey(), record));
            }
        }
        // Oldest confirmation first, then by key so an eviction is reproducible when timestamps tie - a test
        // that cannot predict which record went is a test that cannot pin this behaviour at all.
        all.sort(Comparator.comparingLong((PathRecord entry) -> entry.record().lastSeen())
            .thenComparing(PathRecord::path)
            .thenComparing(entry -> entry.record().issueKey()));
        for (int index = 0; index < excess; index++)
        {
            remove(all.get(index).path(), all.get(index).record().issueKey());
        }
    }

    /**
     * @return how many records this index holds
     */
    public int size()
    {
        int total = 0;
        for (Map<String, AnchorRecord> file : byPath.values())
        {
            total += file.size();
        }
        return total;
    }

    /**
     * The records, for serialization and for tests.
     *
     * @return path to that file's records, in insertion order; the returned map is live and must not be
     *     mutated by callers, never {@code null}
     */
    public Map<String, Map<String, AnchorRecord>> byPath()
    {
        return byPath;
    }

    /** One record together with the file it belongs to, for ordering the eviction. */
    private record PathRecord(String path, AnchorRecord record)
    {
    }
}
