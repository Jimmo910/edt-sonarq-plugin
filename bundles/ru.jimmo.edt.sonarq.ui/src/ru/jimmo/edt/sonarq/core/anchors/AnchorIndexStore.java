/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.anchors;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import org.eclipse.core.runtime.Platform;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import ru.jimmo.edt.sonarq.core.localanalysis.SafeFileNames;
import ru.jimmo.edt.sonarq.core.suppress.LineAnchor;

/**
 * The plug-in's own, persisted memory of issue anchors, under
 * {@code <stateLocation>/issue-anchors/v1/<project>/<scope-id>.json}.
 *
 * <h2>Why the plug-in owns this at all</h2>
 *
 * <p>An anchor is only worth having if it outlives the thing that produced it. It used to live in two places
 * and outlived neither: workspace markers are declared {@code persistent="false"}, so they are gone after an
 * EDT restart and are never created at all while the user has editor markers switched off; the issues view's
 * snapshot is gone when the view closes, and the background auto-sync never goes through it. After a restart
 * the anchors were therefore recomputed from the line numbers the <em>server</em> reported - which, in server
 * mode, are the numbers of its last analysis and know nothing about a suppression written locally since. The
 * fingerprint then faithfully described the wrong line, and a safety net that only ever refuses turned back
 * into "trust the number". This file is what makes the memory outlive both. Markers became delivery.
 *
 * <h2>Fail closed, and say so</h2>
 *
 * <p>A file this class cannot read - truncated, hand-edited, written by a future version - yields <em>no
 * memory</em>: an empty index, a logged warning, and the file moved aside as {@code .corrupt} so the next
 * refresh starts from a clean one instead of re-reading the same wreckage forever. That is deliberately not
 * the silent {@code return List.of()} of {@code DiagnosticsCatalog#load}: a missing catalog only costs a
 * settings page its list, whereas anchor memory silently reported as "I have never seen this issue" is
 * indistinguishable from the truth and would be trusted as such.
 *
 * <h2>Serialization</h2>
 *
 * <p>Plain JSON, read and written a token at a time ({@link JsonReader}/{@link JsonWriter}) so neither the
 * document text nor a parsed tree is ever materialized in the IDE heap. Not compressed: bounded by
 * {@link AnchorIndex#MAX_RECORDS} at roughly a hundred bytes per record, the worst case is a couple of
 * megabytes and the ordinary case a few hundred kilobytes, which is not worth trading for a file a support
 * question cannot be answered by opening - the readable scope fields are stored precisely so it can be.
 *
 * <h2>Concurrency</h2>
 *
 * <p>Writes go to a temporary file and are moved into place, so a reader never sees half a file and a crash
 * mid-write cannot destroy the previous one. A single lock serializes the load-modify-write sequences (a
 * refresh committing its reconciliation, a suppression renumbering a file), and the caller's fence is
 * evaluated <em>inside</em> that lock - the same shape as the marker synchronization's - so a commit cannot
 * be declared current and then overtaken before it writes.
 */
public final class AnchorIndexStore
{
    /** The directory this store owns under the plug-in state location. */
    public static final String DIRECTORY = "issue-anchors"; //$NON-NLS-1$

    /** The on-disk layout version; a different one is a different directory, never a migration. */
    public static final String LAYOUT_VERSION = "v1"; //$NON-NLS-1$

    /**
     * How many records one workspace project may hold across all its scopes. Well above the working set of
     * any plausible session (a scope is capped at {@link AnchorIndex#MAX_RECORDS}); this is what stops a
     * project that is rebound often - many servers, many branches - from accumulating scopes without end.
     */
    public static final int MAX_RECORDS_PER_PROJECT = 50_000;

    /** How long a scope nothing has touched is kept, in milliseconds. */
    public static final long SCOPE_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000;

    /** How long a temporary file left behind by a killed process is tolerated, in milliseconds. */
    private static final long TEMP_TTL_MILLIS = 24L * 60 * 60 * 1000;

    /**
     * A deliberate under-estimate of the bytes one serialized record takes: its five members' names alone are
     * more than this, before any of their values. Used only to rule the project-level cap out cheaply (see
     * {@link #enforceProjectCap}), so under-estimating is the safe direction - it can cost a needless count,
     * never a missed one.
     */
    private static final int MIN_RECORD_BYTES = 60;

    private static final String FILE_SUFFIX = ".json"; //$NON-NLS-1$

    private static final String TEMP_SUFFIX = ".tmp"; //$NON-NLS-1$

    private static final String CORRUPT_SUFFIX = ".corrupt"; //$NON-NLS-1$

    /** The format version written into every file; anything else is unreadable, not upgradable. */
    private static final int FORMAT_VERSION = 1;

    private static final String MEMBER_VERSION = "version"; //$NON-NLS-1$

    private static final String MEMBER_SCOPE = "scope"; //$NON-NLS-1$

    private static final String MEMBER_TOUCHED_AT = "touchedAt"; //$NON-NLS-1$

    private static final String MEMBER_FILES = "files"; //$NON-NLS-1$

    private static final String MEMBER_MODE = "mode"; //$NON-NLS-1$

    private static final String MEMBER_SERVER_URL = "serverUrl"; //$NON-NLS-1$

    private static final String MEMBER_PROJECT_KEY = "projectKey"; //$NON-NLS-1$

    private static final String MEMBER_BRANCH = "branch"; //$NON-NLS-1$

    private static final String MEMBER_PATH_PREFIX = "pathPrefix"; //$NON-NLS-1$

    private static final String MEMBER_PROJECT = "project"; //$NON-NLS-1$

    private static final String MEMBER_ISSUE_KEY = "issueKey"; //$NON-NLS-1$

    private static final String MEMBER_RULE_KEY = "ruleKey"; //$NON-NLS-1$

    private static final String MEMBER_ANCHOR = "anchor"; //$NON-NLS-1$

    private static final String MEMBER_LINE = "line"; //$NON-NLS-1$

    private static final String MEMBER_LAST_SEEN = "lastSeen"; //$NON-NLS-1$

    /** How often a move that lost to a virus scanner or an indexer is retried before giving up. */
    private static final int MOVE_ATTEMPTS = 3;

    private static final long MOVE_RETRY_MILLIS = 50L;

    /**
     * Serializes the load-modify-write sequences of every store instance in this JVM.
     *
     * <p>Static, because the instances are cheap handles created wherever they are needed, not a shared
     * service: two of them addressing the same file would otherwise race, which is exactly the situation a
     * suppression renumbering a file while a refresh commits its reconciliation produces. Writes are small
     * and rare, so one lock costs nothing worth measuring.
     */
    private static final ReentrantLock LOCK = new ReentrantLock();

    private final Path root;

    private final BiConsumer<String, Throwable> log;

    private final int maxRecordsPerProject;

    /**
     * Creates a store under a plug-in state location, logging to the workspace log.
     *
     * @param stateLocation the plug-in state directory, not {@code null}
     */
    public AnchorIndexStore(Path stateLocation)
    {
        this(stateLocation, (message, failure) -> Platform.getLog(AnchorIndexStore.class).warn(message, failure));
    }

    /**
     * Creates a store with its own log sink.
     *
     * @param stateLocation the plug-in state directory, not {@code null}
     * @param log receives every condition a user or a support question would need to see - a corrupt file, a
     *     write that failed - not {@code null}; the seam exists because "and it was reported" is half of what
     *     failing closed means, and a test cannot assert it against the platform log
     */
    public AnchorIndexStore(Path stateLocation, BiConsumer<String, Throwable> log)
    {
        this(stateLocation, log, MAX_RECORDS_PER_PROJECT);
    }

    /**
     * Creates a store with an explicit project-level cap, so the eviction it drives can be tested without
     * writing fifty thousand records first.
     *
     * @param stateLocation the plug-in state directory, not {@code null}
     * @param log receives every condition worth reporting, not {@code null}
     * @param maxRecordsPerProject how many records one project may hold across all its scopes
     */
    AnchorIndexStore(Path stateLocation, BiConsumer<String, Throwable> log, int maxRecordsPerProject)
    {
        this.root = stateLocation.resolve(DIRECTORY).resolve(LAYOUT_VERSION);
        this.log = log;
        this.maxRecordsPerProject = maxRecordsPerProject;
    }

    /**
     * The file one scope's records live in.
     *
     * @param scope the scope, not {@code null}
     * @return the path, never {@code null}; it need not exist
     */
    public Path fileOf(AnchorScope scope)
    {
        return projectDirectory(scope.projectName()).resolve(scope.id() + FILE_SUFFIX);
    }

    /**
     * Loads one scope's memory.
     *
     * @param scope the scope to load, not {@code null}
     * @return the stored records, or an empty index when the scope has none yet <em>and</em> when the stored
     *     file could not be read - the two are the same thing to a caller ("no memory"), but only the second
     *     is logged and moves the unreadable file aside, never {@code null}
     */
    public AnchorIndex load(AnchorScope scope)
    {
        Path file = fileOf(scope);
        LOCK.lock();
        try
        {
            AnchorIndex index = read(file, scope);
            return index != null ? index : new AnchorIndex(scope);
        }
        finally
        {
            LOCK.unlock();
        }
    }

    /**
     * Writes one scope's memory, unless the caller's fence says it has been superseded.
     *
     * <p>The fence is evaluated under the lock, immediately before the write, for the same reason the marker
     * synchronization evaluates its own under the project's rule: between the check and the write nothing
     * else may commit, so a refresh that started before a quick-suppress cannot be waved through by a check
     * that ran before the suppression published its new state.
     *
     * @param index the memory to write, not {@code null}
     * @param stillCurrent tells whether this memory still describes the project's current issue state, not
     *     {@code null}
     * @return {@code true} when the file was written, {@code false} when the fence refused it or the write
     *     failed (which is logged)
     */
    public boolean save(AnchorIndex index, BooleanSupplier stillCurrent)
    {
        LOCK.lock();
        try
        {
            if (!stillCurrent.getAsBoolean())
            {
                return false;
            }
            boolean written = write(index);
            if (written)
            {
                collectGarbage(index.scope().projectName(), index.touchedAt());
            }
            return written;
        }
        finally
        {
            LOCK.unlock();
        }
    }

    /**
     * Brings every scope of one project in step with a quick-suppress that has just edited a file: the
     * suppressed issue is forgotten, and the remaining records of that file are renumbered around the line
     * the comment pair really went in at.
     *
     * <p>Every scope, and not only the one the suppression was driven from, because the edit is a fact about
     * the <em>file</em>: two more lines are in it now, whatever analysis a given scope's records came from.
     * It also spares the caller a scope it cannot reconstruct - the Problems-view quick fix has a marker and
     * a file, not a server URL and an effective branch, and resolving the latter would mean a server round
     * trip in the middle of an editor action.
     *
     * @param projectName the workspace project the edited file belongs to, not {@code null}
     * @param path the project-relative path of the edited file, not {@code null}
     * @param issueKey the key of the suppressed issue, not {@code null}; empty to only renumber
     * @param codeLine the 1-based line the {@code -off}/{@code -on} pair was wrapped around, in the numbering
     *     the file had before the insertion
     * @return how many scope files were changed
     */
    public int suppressionApplied(String projectName, String path, String issueKey, int codeLine)
    {
        LOCK.lock();
        try
        {
            int changed = 0;
            for (Path file : scopeFilesOf(projectName))
            {
                AnchorIndex index = read(file, null);
                if (index == null)
                {
                    continue;
                }
                boolean removed = !issueKey.isEmpty() && index.remove(path, issueKey);
                boolean shifted = index.shiftFile(path, codeLine) > 0;
                if ((removed || shifted) && write(index))
                {
                    changed++;
                }
            }
            return changed;
        }
        finally
        {
            LOCK.unlock();
        }
    }

    /**
     * Deletes every scope of one project, used when its memory must not survive - a binding the user cleared,
     * a test cleaning up after itself.
     *
     * @param projectName the workspace project, not {@code null}
     * @return how many scope files were deleted
     */
    public int forget(String projectName)
    {
        LOCK.lock();
        try
        {
            int deleted = 0;
            for (Path file : scopeFilesOf(projectName))
            {
                try
                {
                    Files.delete(file);
                    deleted++;
                }
                catch (IOException e)
                {
                    log.accept("Could not delete anchor memory " + file, e); //$NON-NLS-1$
                }
            }
            return deleted;
        }
        finally
        {
            LOCK.unlock();
        }
    }

    /**
     * Reads one file.
     *
     * @param file the file to read, not {@code null}
     * @param expected the scope the file must describe, or {@code null} to accept whatever it describes
     *     (which is how the maintenance paths walk a project's scopes)
     * @return the parsed index, or {@code null} when the file is absent, unreadable or does not describe
     *     {@code expected} - the last two are logged and move the file aside
     */
    private AnchorIndex read(Path file, AnchorScope expected)
    {
        if (!Files.isRegularFile(file))
        {
            return null;
        }
        try (BufferedReader source = Files.newBufferedReader(file, StandardCharsets.UTF_8);
            JsonReader reader = new JsonReader(source))
        {
            AnchorIndex index = readIndex(reader);
            if (expected != null && !expected.equals(index.scope()))
            {
                throw new IOException("the file describes a different scope than its name claims"); //$NON-NLS-1$
            }
            return index;
        }
        catch (IOException | RuntimeException e)
        {
            quarantine(file, e);
            return null;
        }
    }

    /**
     * Reads the whole document.
     *
     * @param reader the reader positioned at the start of the document, not {@code null}
     * @return the parsed index, never {@code null}
     * @throws IOException when the document is malformed, of an unreadable version, or missing its scope
     */
    private static AnchorIndex readIndex(JsonReader reader) throws IOException
    {
        Integer version = null;
        AnchorScope scope = null;
        long touchedAt = 0;
        List<PathRecords> files = List.of();
        reader.beginObject();
        while (reader.hasNext())
        {
            switch (reader.nextName())
            {
                case MEMBER_VERSION -> version = reader.nextInt();
                case MEMBER_SCOPE -> scope = readScope(reader);
                case MEMBER_TOUCHED_AT -> touchedAt = reader.nextLong();
                case MEMBER_FILES -> files = readFiles(reader);
                default -> reader.skipValue();
            }
        }
        reader.endObject();
        if (version == null || version != FORMAT_VERSION)
        {
            throw new IOException("unsupported anchor memory version: " + version); //$NON-NLS-1$
        }
        if (scope == null)
        {
            throw new IOException("anchor memory without a scope"); //$NON-NLS-1$
        }
        AnchorIndex index = new AnchorIndex(scope);
        index.touch(touchedAt);
        for (PathRecords file : files)
        {
            for (AnchorRecord record : file.records())
            {
                index.put(file.path(), record);
            }
        }
        return index;
    }

    /**
     * Reads the scope object.
     *
     * @param reader the reader positioned at the scope value, not {@code null}
     * @return the scope, never {@code null}
     * @throws IOException when the object is malformed or incomplete
     */
    private static AnchorScope readScope(JsonReader reader) throws IOException
    {
        String mode = null;
        String serverUrl = ""; //$NON-NLS-1$
        String projectKey = null;
        String branch = null;
        String pathPrefix = ""; //$NON-NLS-1$
        String project = null;
        reader.beginObject();
        while (reader.hasNext())
        {
            switch (reader.nextName())
            {
                case MEMBER_MODE -> mode = reader.nextString();
                case MEMBER_SERVER_URL -> serverUrl = reader.nextString();
                case MEMBER_PROJECT_KEY -> projectKey = reader.nextString();
                case MEMBER_BRANCH -> branch = nullableString(reader);
                case MEMBER_PATH_PREFIX -> pathPrefix = reader.nextString();
                case MEMBER_PROJECT -> project = reader.nextString();
                default -> reader.skipValue();
            }
        }
        reader.endObject();
        if (mode == null || projectKey == null || project == null)
        {
            throw new IOException("incomplete anchor memory scope"); //$NON-NLS-1$
        }
        return new AnchorScope(mode, serverUrl, projectKey, branch, pathPrefix, project);
    }

    /**
     * Reads the per-file record lists.
     *
     * @param reader the reader positioned at the files value, not {@code null}
     * @return the records per path, never {@code null}
     * @throws IOException when the object is malformed
     */
    private static List<PathRecords> readFiles(JsonReader reader) throws IOException
    {
        List<PathRecords> files = new ArrayList<>();
        reader.beginObject();
        while (reader.hasNext())
        {
            String path = reader.nextName();
            List<AnchorRecord> records = new ArrayList<>();
            reader.beginArray();
            while (reader.hasNext())
            {
                AnchorRecord record = readRecord(reader);
                if (record != null)
                {
                    records.add(record);
                }
            }
            reader.endArray();
            files.add(new PathRecords(path, records));
        }
        reader.endObject();
        return files;
    }

    /**
     * Reads one record.
     *
     * @param reader the reader positioned at a record object, not {@code null}
     * @return the record, or {@code null} when it carries nothing worth remembering (no key, or no anchor -
     *     an issue that was never fingerprinted has no memory to restore)
     * @throws IOException when the object is malformed
     */
    private static AnchorRecord readRecord(JsonReader reader) throws IOException
    {
        String issueKey = ""; //$NON-NLS-1$
        String ruleKey = ""; //$NON-NLS-1$
        String anchor = LineAnchor.NONE;
        int line = AnchorRecord.NO_LINE;
        long lastSeen = 0;
        reader.beginObject();
        while (reader.hasNext())
        {
            switch (reader.nextName())
            {
                case MEMBER_ISSUE_KEY -> issueKey = reader.nextString();
                case MEMBER_RULE_KEY -> ruleKey = reader.nextString();
                case MEMBER_ANCHOR -> anchor = reader.nextString();
                case MEMBER_LINE -> line = reader.nextInt();
                case MEMBER_LAST_SEEN -> lastSeen = reader.nextLong();
                default -> reader.skipValue();
            }
        }
        reader.endObject();
        if (issueKey.isEmpty() || anchor.isEmpty())
        {
            return null;
        }
        return new AnchorRecord(issueKey, ruleKey, anchor, line, lastSeen);
    }

    /**
     * Reads a string that may be JSON {@code null}.
     *
     * @param reader the reader positioned at the value, not {@code null}
     * @return the string, or {@code null} for a JSON null
     * @throws IOException when the value is neither
     */
    private static String nullableString(JsonReader reader) throws IOException
    {
        if (reader.peek() == JsonToken.NULL)
        {
            reader.nextNull();
            return null;
        }
        return reader.nextString();
    }

    /**
     * Writes one index through a temporary file.
     *
     * @param index the memory to write, not {@code null}
     * @return {@code true} when the file is in place
     */
    private boolean write(AnchorIndex index)
    {
        Path file = fileOf(index.scope());
        Path temporary = file.resolveSibling(file.getFileName() + TEMP_SUFFIX);
        try
        {
            Files.createDirectories(file.getParent());
            try (BufferedWriter sink = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8);
                JsonWriter writer = new JsonWriter(sink))
            {
                writeIndex(writer, index);
            }
            moveIntoPlace(temporary, file);
            return true;
        }
        catch (IOException | RuntimeException e)
        {
            log.accept("Could not write anchor memory " + file, e); //$NON-NLS-1$
            deleteQuietly(temporary);
            return false;
        }
    }

    /**
     * Serializes one index.
     *
     * @param writer the writer, not {@code null}
     * @param index the memory to write, not {@code null}
     * @throws IOException when writing fails
     */
    private static void writeIndex(JsonWriter writer, AnchorIndex index) throws IOException
    {
        writer.beginObject();
        writer.name(MEMBER_VERSION).value(FORMAT_VERSION);
        writer.name(MEMBER_SCOPE);
        writeScope(writer, index.scope());
        writer.name(MEMBER_TOUCHED_AT).value(index.touchedAt());
        writer.name(MEMBER_FILES);
        writer.beginObject();
        for (Map.Entry<String, Map<String, AnchorRecord>> file : index.byPath().entrySet())
        {
            writer.name(file.getKey());
            writer.beginArray();
            for (AnchorRecord record : file.getValue().values())
            {
                writer.beginObject();
                writer.name(MEMBER_ISSUE_KEY).value(record.issueKey());
                writer.name(MEMBER_RULE_KEY).value(record.ruleKey());
                writer.name(MEMBER_ANCHOR).value(record.anchor());
                writer.name(MEMBER_LINE).value(record.lastKnownLine());
                writer.name(MEMBER_LAST_SEEN).value(record.lastSeen());
                writer.endObject();
            }
            writer.endArray();
        }
        writer.endObject();
        writer.endObject();
    }

    /**
     * Serializes the scope, readable fields and all, so a stale anchor can be diagnosed by opening the file.
     *
     * @param writer the writer, not {@code null}
     * @param scope the scope, not {@code null}
     * @throws IOException when writing fails
     */
    private static void writeScope(JsonWriter writer, AnchorScope scope) throws IOException
    {
        writer.beginObject();
        writer.name(MEMBER_MODE).value(scope.mode());
        writer.name(MEMBER_SERVER_URL).value(scope.serverUrl());
        writer.name(MEMBER_PROJECT_KEY).value(scope.projectKey());
        // Written as JSON null rather than as the readable "<default>" label: a branch really called that
        // must stay a different scope, and a null cannot be mistaken for a branch name.
        writer.name(MEMBER_BRANCH).value(scope.branch());
        writer.name(MEMBER_PATH_PREFIX).value(scope.pathPrefix());
        writer.name(MEMBER_PROJECT).value(scope.projectName());
        writer.endObject();
    }

    /**
     * Moves the temporary file onto the real one, retrying a move another process momentarily denied.
     *
     * <p>On Windows an anti-virus scanner or the search indexer regularly holds a just-written file open for
     * a few milliseconds, which surfaces as an {@code AccessDeniedException} on the move - the same flake
     * this project already retries around when it stages a downloaded engine.
     *
     * @param temporary the written temporary file, not {@code null}
     * @param file the destination, not {@code null}
     * @throws IOException when the move keeps failing
     */
    private static void moveIntoPlace(Path temporary, Path file) throws IOException
    {
        IOException last = null;
        for (int attempt = 1; attempt <= MOVE_ATTEMPTS; attempt++)
        {
            try
            {
                move(temporary, file);
                return;
            }
            catch (IOException e)
            {
                last = e;
                sleep();
            }
        }
        throw last;
    }

    /**
     * One move attempt, atomic where the file system offers it.
     *
     * @param temporary the written temporary file, not {@code null}
     * @param file the destination, not {@code null}
     * @throws IOException when the move fails
     */
    private static void move(Path temporary, Path file) throws IOException
    {
        try
        {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (AtomicMoveNotSupportedException e)
        {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void sleep()
    {
        try
        {
            Thread.sleep(MOVE_RETRY_MILLIS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Moves an unreadable file aside and reports it.
     *
     * <p>Both halves matter. Moving it aside stops the next refresh from re-reading the same wreckage - and
     * lets it write a fresh file - while keeping the original for whoever has to explain how it got that way.
     * Reporting it is what distinguishes "this scope has no memory yet", which is normal and silent, from
     * "this scope's memory was lost", which is not.
     *
     * @param file the unreadable file, not {@code null}
     * @param cause why it could not be read, not {@code null}
     */
    private void quarantine(Path file, Exception cause)
    {
        Path aside = file.resolveSibling(file.getFileName() + CORRUPT_SUFFIX);
        try
        {
            Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException e)
        {
            deleteQuietly(file);
        }
        log.accept("Unreadable SonarQube anchor memory " + file //$NON-NLS-1$
            + " was moved aside; this scope has to be re-anchored by the next refresh", cause); //$NON-NLS-1$
    }

    /**
     * Opportunistic housekeeping, run after a successful write rather than on a timer of its own: this is a
     * cache beside a background job, and a scheduler for it would be more machinery than the thing it tends.
     *
     * @param projectName the project just written, not {@code null}
     * @param nowMillis the current time in epoch milliseconds
     */
    private void collectGarbage(String projectName, long nowMillis)
    {
        Path directory = projectDirectory(projectName);
        List<Path> scopes = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory))
        {
            for (Path entry : entries)
            {
                collect(entry, nowMillis, scopes);
            }
        }
        catch (IOException | RuntimeException e)
        {
            log.accept("Could not tidy anchor memory in " + directory, e); //$NON-NLS-1$
            return;
        }
        enforceProjectCap(scopes);
    }

    /**
     * Classifies one directory entry: an expired temporary file and an expired scope are deleted, a live
     * scope is collected for the project-level cap.
     *
     * @param entry the directory entry, not {@code null}
     * @param nowMillis the current time in epoch milliseconds
     * @param scopes collects the scope files that survive, not {@code null}
     * @throws IOException when the entry cannot be inspected
     */
    private void collect(Path entry, long nowMillis, List<Path> scopes) throws IOException
    {
        String name = entry.getFileName().toString();
        if (name.endsWith(TEMP_SUFFIX))
        {
            if (age(entry, nowMillis) > TEMP_TTL_MILLIS)
            {
                deleteQuietly(entry);
            }
            return;
        }
        if (!name.endsWith(FILE_SUFFIX))
        {
            return;
        }
        if (age(entry, nowMillis) > SCOPE_TTL_MILLIS)
        {
            deleteQuietly(entry);
            return;
        }
        scopes.add(entry);
    }

    /**
     * Drops whole scopes, least recently modified first, while the project holds more records than
     * {@link #maxRecordsPerProject}.
     *
     * <p>Whole scopes rather than individual records: a project only gets here by accumulating scopes it no
     * longer refreshes - other servers, other branches - and half a scope's memory is worth no more than
     * none of it, while being considerably more work to arrive at.
     *
     * <p>Counting the records means parsing every scope of the project, which this must not do on each of the
     * writes that a refresh timer produces all day. The bytes on disk settle it first: a record cannot be
     * serialized in fewer than {@link #MIN_RECORD_BYTES}, so a project whose files are smaller than the cap
     * implies cannot be over it, whatever they contain - and that is the answer in every ordinary session,
     * for the price of one {@code stat} per file.
     *
     * @param scopes the project's live scope files, not {@code null}
     */
    private void enforceProjectCap(List<Path> scopes)
    {
        long bytes = 0;
        for (Path scope : scopes)
        {
            bytes += sizeOf(scope);
        }
        if (bytes < (long)maxRecordsPerProject * MIN_RECORD_BYTES)
        {
            return;
        }
        int total = 0;
        List<Path> ordered = new ArrayList<>(scopes);
        for (Path scope : ordered)
        {
            AnchorIndex index = read(scope, null);
            total += index != null ? index.size() : 0;
        }
        if (total <= maxRecordsPerProject)
        {
            return;
        }
        ordered.sort(Comparator.comparingLong(AnchorIndexStore::modifiedMillis));
        for (Path scope : ordered)
        {
            if (total <= maxRecordsPerProject)
            {
                return;
            }
            AnchorIndex index = read(scope, null);
            total -= index != null ? index.size() : 0;
            deleteQuietly(scope);
        }
    }

    /**
     * The size of a file, for the cheap pre-check.
     *
     * @param file the file, not {@code null}
     * @return the size in bytes, or {@code 0} when it cannot be read
     */
    private static long sizeOf(Path file)
    {
        try
        {
            return Files.size(file);
        }
        catch (IOException | UncheckedIOException e)
        {
            return 0;
        }
    }

    /**
     * The scope files of one project.
     *
     * @param projectName the workspace project, not {@code null}
     * @return the files, never {@code null}; empty when the project has no memory
     */
    private List<Path> scopeFilesOf(String projectName)
    {
        Path directory = projectDirectory(projectName);
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*" + FILE_SUFFIX)) //$NON-NLS-1$
        {
            for (Path entry : entries)
            {
                files.add(entry);
            }
        }
        catch (IOException | RuntimeException e)
        {
            // A project with no memory yet has no directory; nothing to report and nothing to do.
            return List.of();
        }
        return files;
    }

    /**
     * The directory one project's scopes live in.
     *
     * @param projectName the workspace project, not {@code null}
     * @return the directory, never {@code null}
     */
    private Path projectDirectory(String projectName)
    {
        return root.resolve(SafeFileNames.segmentFor(projectName));
    }

    /**
     * How long ago a file was last modified.
     *
     * @param file the file, not {@code null}
     * @param nowMillis the current time in epoch milliseconds
     * @return the age in milliseconds, never negative
     * @throws IOException when the file cannot be inspected
     */
    private static long age(Path file, long nowMillis) throws IOException
    {
        FileTime modified = Files.getLastModifiedTime(file);
        return Math.max(0, nowMillis - modified.toMillis());
    }

    /**
     * The last-modified time of a file, for ordering.
     *
     * @param file the file, not {@code null}
     * @return the time in epoch milliseconds, or {@code 0} when it cannot be read - which sorts it first, so
     *     a file that cannot even be stat'ed is the first candidate for deletion
     */
    private static long modifiedMillis(Path file)
    {
        try
        {
            return Files.getLastModifiedTime(file).toMillis();
        }
        catch (IOException | UncheckedIOException e)
        {
            return 0;
        }
    }

    private void deleteQuietly(Path file)
    {
        try
        {
            Files.deleteIfExists(file);
        }
        catch (IOException e)
        {
            log.accept("Could not delete " + file, e); //$NON-NLS-1$
        }
    }

    /**
     * One file's records as they were read, before they go into an index.
     *
     * @param path the project-relative path, not {@code null}
     * @param records the records, not {@code null}
     */
    private record PathRecords(String path, List<AnchorRecord> records)
    {
    }
}
