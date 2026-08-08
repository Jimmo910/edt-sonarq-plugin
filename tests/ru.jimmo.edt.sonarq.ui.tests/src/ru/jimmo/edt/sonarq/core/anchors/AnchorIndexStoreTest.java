/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.anchors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests {@link AnchorIndexStore}: the memory that has to survive a restart, has to fail closed when it
 * cannot be read, and must never hand one analysis another's records.
 */
public class AnchorIndexStoreTest
{
    private static final String ANCHOR = "v3:0000000000000001:0000000000000002:0000000000000003";

    private static final String OTHER_ANCHOR = "v3:00000000000000ff:00000000000000ee:00000000000000dd";

    private static final String PATH = "src/Module.bsl";

    private static final long NOW = 1_700_000_000_000L;

    private Path root;

    private List<String> logged;

    private AnchorIndexStore store;

    @Before
    public void setUp() throws IOException
    {
        root = Files.createTempDirectory("anchor-store-test");
        logged = new ArrayList<>();
        store = new AnchorIndexStore(root, (message, failure) -> logged.add(message));
    }

    @After
    public void tearDown()
    {
        TempDirectories.delete(root);
    }

    /**
     * The restart. A store instance is not a session: what one wrote, a completely fresh one - built the way
     * the plug-in builds it after EDT starts again - has to read back, record for record.
     */
    @Test
    public void whatOneStoreWroteAFreshInstanceReadsBack()
    {
        AnchorIndex written = new AnchorIndex(scope("main"));
        written.put(PATH, new AnchorRecord("k1", "bsl:R1", ANCHOR, 42, NOW));
        written.put("src/Other.bsl", new AnchorRecord("k2", "bsl:R2", OTHER_ANCHOR, 7, NOW - 1));
        written.touch(NOW);
        assertTrue(store.save(written, () -> true));

        AnchorIndex reloaded = new AnchorIndexStore(root, (message, failure) -> logged.add(message))
            .load(scope("main"));

        assertEquals(2, reloaded.size());
        assertEquals(new AnchorRecord("k1", "bsl:R1", ANCHOR, 42, NOW), reloaded.find(PATH, "k1"));
        assertEquals(new AnchorRecord("k2", "bsl:R2", OTHER_ANCHOR, 7, NOW - 1),
            reloaded.find("src/Other.bsl", "k2"));
        assertEquals(scope("main"), reloaded.scope());
        assertEquals(NOW, reloaded.touchedAt());
        assertTrue("a clean round trip must report nothing", logged.isEmpty());
    }

    @Test
    public void aScopeThatWasNeverWrittenLoadsEmptyAndSilently()
    {
        AnchorIndex index = store.load(scope("main"));

        assertEquals(0, index.size());
        assertTrue("an absent file is normal, not a failure", logged.isEmpty());
    }

    /**
     * Fail closed, and say so. A file this version cannot read yields no memory - which is the safe answer,
     * since an unverifiable issue is one a suppression refuses - but it is reported and moved aside, instead
     * of silently passing for "this scope has never been anchored".
     */
    @Test
    public void acorruptFileIsReportedMovedAsideAndTreatedAsNoMemory() throws IOException
    {
        seed("main", "k1");
        Path file = store.fileOf(scope("main"));
        Files.writeString(file, "{\"version\":1,\"files\":{ this is not json", StandardCharsets.UTF_8);

        AnchorIndex index = store.load(scope("main"));

        assertEquals(0, index.size());
        assertEquals(1, logged.size());
        assertTrue(logged.get(0), logged.get(0).contains("Unreadable"));
        assertFalse("the wreckage must not be read again next time", Files.exists(file));
        assertTrue(Files.exists(file.resolveSibling(file.getFileName() + ".corrupt")));
    }

    /** A file from a future layout is unreadable in exactly the same way: no memory, reported, aside. */
    @Test
    public void aFileFromAnUnsupportedVersionIsAlsoFailedClosed() throws IOException
    {
        seed("main", "k1");
        Path file = store.fileOf(scope("main"));
        Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8)
            .replace("\"version\":1", "\"version\":99"), StandardCharsets.UTF_8);

        assertEquals(0, store.load(scope("main")).size());
        assertEquals(1, logged.size());
    }

    /** A fence that says "superseded" writes nothing at all - the file on disk is left as it was. */
    @Test
    public void asupersededCommitWritesNothing()
    {
        seed("main", "k1");
        AnchorIndex stale = new AnchorIndex(scope("main"));
        stale.put(PATH, new AnchorRecord("stale", "bsl:R", OTHER_ANCHOR, 1, NOW));

        assertFalse(store.save(stale, () -> false));

        AnchorIndex onDisk = store.load(scope("main"));
        assertEquals(1, onDisk.size());
        assertNotNull(onDisk.find(PATH, "k1"));
        assertNull(onDisk.find(PATH, "stale"));
    }

    /** Different analyses, different files, and never each other's records. */
    @Test
    public void scopesDoNotSeeEachOthersRecords()
    {
        seed("main", "on-main");

        assertNull(store.load(scope("release/1")).find(PATH, "on-main"));
        assertNull(store.load(scope(null)).find(PATH, "on-main"));
        assertNotNull(store.load(scope("main")).find(PATH, "on-main"));
    }

    /**
     * The suppression bookkeeping, which is the one write that runs with no scope in hand: the quick fix has
     * a marker and a file, so every scope of the project is brought in step with the edit.
     */
    @Test
    public void asuppressionForgetsItsIssueAndRenumbersTheRestOfTheFileInEveryScope()
    {
        AnchorIndex main = new AnchorIndex(scope("main"));
        main.put(PATH, new AnchorRecord("k1", "bsl:R1", ANCHOR, 10, NOW));
        main.put(PATH, new AnchorRecord("k2", "bsl:R2", OTHER_ANCHOR, 20, NOW));
        main.put(PATH, new AnchorRecord("k0", "bsl:R0", OTHER_ANCHOR, 3, NOW));
        main.put("src/Untouched.bsl", new AnchorRecord("k3", "bsl:R3", ANCHOR, 20, NOW));
        store.save(main, () -> true);
        AnchorIndex other = new AnchorIndex(scope("release/1"));
        other.put(PATH, new AnchorRecord("k9", "bsl:R9", ANCHOR, 20, NOW));
        store.save(other, () -> true);

        assertEquals(2, store.suppressionApplied("Proj", PATH, "k1", 10));

        AnchorIndex reloaded = store.load(scope("main"));
        assertNull("the suppressed issue is gone", reloaded.find(PATH, "k1"));
        assertEquals("below the edit: two lines", 22, reloaded.find(PATH, "k2").lastKnownLine());
        assertEquals("above the edit: unmoved", 3, reloaded.find(PATH, "k0").lastKnownLine());
        assertEquals("another file: unmoved", 20,
            reloaded.find("src/Untouched.bsl", "k3").lastKnownLine());
        assertEquals("the other scope's record of the same file moved too", 22,
            store.load(scope("release/1")).find(PATH, "k9").lastKnownLine());
    }

    /** Nothing to change is not a change: a suppression in a file no scope remembers rewrites no file. */
    @Test
    public void asuppressionInAnUnknownFileRewritesNothing()
    {
        seed("main", "k1");

        assertEquals(0, store.suppressionApplied("Proj", "src/Never/Seen.bsl", "k9", 4));
    }

    /** A scope nothing has touched for a month is dropped, opportunistically, on the next write. */
    @Test
    public void anExpiredScopeIsDroppedOnTheNextWrite() throws IOException
    {
        seed("release/1", "old");
        Path expired = store.fileOf(scope("release/1"));
        Files.setLastModifiedTime(expired,
            FileTime.fromMillis(System.currentTimeMillis() - AnchorIndexStore.SCOPE_TTL_MILLIS - 1000));

        seed("main", "fresh");

        assertFalse("an untouched scope must not be kept for ever", Files.exists(expired));
        assertNotNull(store.load(scope("main")).find(PATH, "fresh"));
    }

    /** A temporary file a killed process left behind is cleaned up, but only once it is old. */
    @Test
    public void staleTemporaryFilesAreCleanedUpLazily() throws IOException
    {
        seed("main", "k1");
        Path directory = store.fileOf(scope("main")).getParent();
        Path old = directory.resolve("leftover.json.tmp");
        Path recent = directory.resolve("in-flight.json.tmp");
        Files.writeString(old, "junk", StandardCharsets.UTF_8);
        Files.writeString(recent, "junk", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(old,
            FileTime.fromMillis(System.currentTimeMillis() - 48L * 60 * 60 * 1000));

        seed("main", "k2");

        assertFalse(Files.exists(old));
        assertTrue("a temporary file of a write happening right now must survive", Files.exists(recent));
    }

    /**
     * The project-level backstop: a project that has accumulated more scopes than it needs loses whole ones,
     * least recently written first, rather than growing without end.
     */
    @Test
    public void scopesAreDroppedOldestFirstWhenTheProjectHoldsTooManyRecords() throws IOException
    {
        AnchorIndexStore capped = new AnchorIndexStore(root, (message, failure) -> logged.add(message), 2);
        write(capped, scope("release/1"), "old-1", "old-2");
        // Recent enough to be nowhere near the scope TTL - otherwise this would pass on the strength of the
        // expiry sweep and say nothing at all about the cap.
        Files.setLastModifiedTime(capped.fileOf(scope("release/1")),
            FileTime.fromMillis(System.currentTimeMillis() - 60_000));
        write(capped, scope("main"), "fresh");

        assertFalse("the least recently written scope must have gone",
            Files.exists(capped.fileOf(scope("release/1"))));
        assertNotNull("the scope just written must stay",
            capped.load(scope("main")).find(PATH, "fresh"));
    }

    /** The ordinary case must not pay for that backstop, nor trip over it. */
    @Test
    public void aprojectUnderTheCapKeepsEveryScope()
    {
        AnchorIndexStore capped = new AnchorIndexStore(root, (message, failure) -> logged.add(message), 2);
        write(capped, scope("release/1"), "one");
        write(capped, scope("main"), "two");

        assertNotNull(capped.load(scope("release/1")).find(PATH, "one"));
        assertNotNull(capped.load(scope("main")).find(PATH, "two"));
    }

    @Test
    public void forgettingAProjectDeletesEveryScopeOfIt()
    {
        seed("main", "k1");
        seed("release/1", "k2");

        assertEquals(2, store.forget("Proj"));

        assertEquals(0, store.load(scope("main")).size());
        assertEquals(0, store.load(scope("release/1")).size());
    }

    /** A project name that is not a legal file name still gets its own directory. */
    @Test
    public void aProjectNameIsSanitizedIntoASinglePathSegment()
    {
        AnchorScope hostile = new AnchorScope(AnchorScope.MODE_SERVER, "", "key", "main", "",
            "../../etc/Proj Name");
        AnchorIndex index = new AnchorIndex(hostile);
        index.put(PATH, new AnchorRecord("k1", "bsl:R", ANCHOR, 1, NOW));

        assertTrue(store.save(index, () -> true));

        Path file = store.fileOf(hostile);
        assertTrue(file.toString(), file.normalize().startsWith(root.normalize()));
        assertNotNull(store.load(hostile).find(PATH, "k1"));
    }

    /** A record with no anchor is nothing to remember, and does not come back as one. */
    @Test
    public void recordsWithoutAnAnchorAreNotRestored() throws IOException
    {
        seed("main", "k1");
        Path file = store.fileOf(scope("main"));
        Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8)
            .replace(ANCHOR, ""), StandardCharsets.UTF_8);

        assertEquals(0, store.load(scope("main")).size());
        assertTrue("an empty anchor is not corruption", logged.isEmpty());
    }

    /** Everything a human needs to work out whose memory a file is stays readable inside it. */
    @Test
    public void theScopeIsWrittenIntoTheFileInReadableForm() throws IOException
    {
        seed("main", "k1");

        String json = Files.readString(store.fileOf(scope("main")), StandardCharsets.UTF_8);

        assertTrue(json, json.contains("\"projectKey\":\"key\""));
        assertTrue(json, json.contains("\"branch\":\"main\""));
        assertTrue(json, json.contains("\"project\":\"Proj\""));
        assertFalse("no token may ever reach this file", json.contains("token"));
    }

    /** The default branch is a JSON null, not the label - a branch really called that is a different scope. */
    @Test
    public void theDefaultBranchIsStoredAsNull() throws IOException
    {
        seed(null, "k1");

        String json = Files.readString(store.fileOf(scope(null)), StandardCharsets.UTF_8);

        assertTrue(json, json.contains("\"branch\":null"));
        assertNull(store.load(scope(null)).scope().branch());
    }

    /** Sweeping is the index's job, but the caps have to hold across a save/load round trip too. */
    @Test
    public void recordsBeyondTheScopeCapAreEvictedOldestFirst()
    {
        AnchorIndex index = new AnchorIndex(scope("main"));
        for (int number = 0; number < 5; number++)
        {
            index.put(PATH, new AnchorRecord("k" + number, "bsl:R", ANCHOR, number + 1, NOW + number));
        }

        index.evictDownTo(2);
        store.save(index, () -> true);

        AnchorIndex reloaded = store.load(scope("main"));
        assertEquals(2, reloaded.size());
        assertNotNull("the newest confirmations survive", reloaded.find(PATH, "k4"));
        assertNotNull(reloaded.find(PATH, "k3"));
        assertNull("the oldest go first", reloaded.find(PATH, "k0"));
    }

    /** A complete snapshot proves an absent issue is gone; a truncated one proves nothing. */
    @Test
    public void acompleteSnapshotSweepsAbsentRecordsWhileATruncatedOneKeepsThem()
    {
        AnchorIndex complete = new AnchorIndex(scope("main"));
        complete.put(PATH, new AnchorRecord("seen", "bsl:R", ANCHOR, 1, NOW));
        complete.put(PATH, new AnchorRecord("absent", "bsl:R", ANCHOR, 2, NOW));
        AnchorIndex truncated = new AnchorIndex(scope("release/1"));
        truncated.put(PATH, new AnchorRecord("seen", "bsl:R", ANCHOR, 1, NOW));
        truncated.put(PATH, new AnchorRecord("absent", "bsl:R", ANCHOR, 2, NOW));

        complete.sweep(Map.of(PATH, Set.of("seen")), true, NOW);
        truncated.sweep(Map.of(PATH, Set.of("seen")), false, NOW);

        assertNull("a complete snapshot listed everything, so this issue is gone",
            complete.find(PATH, "absent"));
        assertNotNull("a truncated snapshot said nothing about this issue",
            truncated.find(PATH, "absent"));
    }

    /** Kept, but not for ever: an unconfirmed record expires even on a permanently truncated scope. */
    @Test
    public void anUnseenRecordOfATruncatedSnapshotExpiresEventually()
    {
        AnchorIndex index = new AnchorIndex(scope("main"));
        index.put(PATH, new AnchorRecord("stale", "bsl:R", ANCHOR, 2, NOW));
        index.put(PATH, new AnchorRecord("recent", "bsl:R", ANCHOR, 3, NOW));

        index.sweep(Map.of(), false, NOW + AnchorIndex.UNSEEN_TTL_MILLIS + 1);

        assertEquals(0, index.size());
    }

    /**
     * Seeds one record into a scope.
     *
     * @param branch the scope's branch, or {@code null} for the default branch
     * @param issueKey the issue key to store
     */
    private void seed(String branch, String issueKey)
    {
        AnchorIndex index = store.load(scope(branch));
        index.put(PATH, new AnchorRecord(issueKey, "bsl:R", ANCHOR, 5, NOW));
        index.touch(System.currentTimeMillis());
        assertTrue(store.save(index, () -> true));
    }

    /**
     * Writes one scope's records through a given store.
     *
     * @param target the store to write through
     * @param scope the scope to write
     * @param issueKeys the issue keys to record
     */
    private static void write(AnchorIndexStore target, AnchorScope scope, String... issueKeys)
    {
        AnchorIndex index = new AnchorIndex(scope);
        for (String issueKey : issueKeys)
        {
            index.put(PATH, new AnchorRecord(issueKey, "bsl:R", ANCHOR, 5, NOW));
        }
        index.touch(System.currentTimeMillis());
        assertTrue(target.save(index, () -> true));
    }

    private static AnchorScope scope(String branch)
    {
        return new AnchorScope(AnchorScope.MODE_SERVER, "https://sonar.example", "key", branch, "", "Proj");
    }
}
