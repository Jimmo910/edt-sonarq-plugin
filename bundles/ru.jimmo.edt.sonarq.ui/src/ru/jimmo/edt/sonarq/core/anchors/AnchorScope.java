/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.anchors;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * What one file of persisted anchor memory is <em>about</em>: the analysis whose issue keys and line numbers
 * its records describe.
 *
 * <p>An anchor is memory of "issue {@code X} of this analysis sits on the line that looks like this". Reusing
 * that memory under a different analysis would be a category error with real consequences: two SonarQube
 * servers hand out unrelated issue keys, the same server hands out different ones per project key and per
 * branch, and the same key on another branch names a different revision of the file. A record found under the
 * wrong scope would answer "yes, I remember this issue" about an issue nobody ever anchored, and a
 * quick-suppress would then edit the line that memory names. So every field that can change what an issue key
 * means is part of the scope, and the scope decides the file the records live in.
 *
 * <p>The token is deliberately <em>not</em> among them: it authenticates the user, it does not identify the
 * analysis, and this record is written to disk in clear.
 *
 * <p>{@link #id()} is a hash rather than the fields themselves because a scope contains a URL, a project key
 * and a branch name - none of which is a legal file name. The readable fields are written into the file as
 * well, so a support question about a stale anchor can be answered by opening it.
 *
 * @param mode {@link #MODE_SERVER} or {@link #MODE_LOCAL}: a local BSL Language Server run invents its own
 *     issue keys and shares none of them with a server, not {@code null}
 * @param serverUrl the normalized server URL (see {@link #normalizeUrl}), empty in local mode, not
 *     {@code null}
 * @param projectKey the SonarQube project key the component keys are built with, not {@code null}
 * @param branch the effective branch the issues were fetched for, or {@code null} for the server's default
 *     branch - which is a scope of its own and never mixes with a branch that happens to carry the default's
 *     name (see {@link #describeBranch()})
 * @param pathPrefix the repository path prefix stripped when mapping component keys to files, not
 *     {@code null}
 * @param projectName the workspace project the files belong to, not {@code null}
 */
public record AnchorScope(String mode, String serverUrl, String projectKey, String branch, String pathPrefix,
    String projectName)
{
    /** The {@link #mode()} of issues fetched from a SonarQube server. */
    public static final String MODE_SERVER = "server"; //$NON-NLS-1$

    /** The {@link #mode()} of issues produced by a local BSL Language Server run. */
    public static final String MODE_LOCAL = "local"; //$NON-NLS-1$

    /** How {@link #describeBranch()} renders "the server's default branch". */
    public static final String DEFAULT_BRANCH = "<default>"; //$NON-NLS-1$

    /** How many hexadecimal characters of the digest {@link #id()} keeps. */
    private static final int ID_LENGTH = 32;

    private static final String DIGEST_ALGORITHM = "SHA-256"; //$NON-NLS-1$

    /** Marks a component whose value is present, so an absent one can never be spelled the same way. */
    private static final char PRESENT = '+';

    /** Marks an absent component - today only "no branch", i.e. the server's default. */
    private static final char ABSENT = '-';

    /**
     * Rejects the {@code null}s that would otherwise only show up as a {@link NullPointerException} inside
     * the digest, far from the caller that produced them. {@link #branch} is exempt: {@code null} is its
     * documented "default branch" value.
     */
    public AnchorScope
    {
        if (mode == null || serverUrl == null || projectKey == null || pathPrefix == null
            || projectName == null)
        {
            throw new IllegalArgumentException("every scope component except the branch is required"); //$NON-NLS-1$
        }
    }

    /**
     * Normalizes a server URL for scoping: the trailing slash is dropped and the surrounding whitespace with
     * it, so {@code https://sonar/} and {@code https://sonar} are one scope rather than two.
     *
     * <p>Deliberately stops there. Case-folding the host would be correct for the host alone, but this string
     * also carries a context path, which <em>is</em> case-sensitive; treating two spellings as one scope is
     * only ever a convenience, while treating two different servers as one would hand one server's memory to
     * another.
     *
     * @param url the configured server URL, may be {@code null}
     * @return the normalized URL, never {@code null}; empty for a {@code null} or blank input
     */
    public static String normalizeUrl(String url)
    {
        if (url == null)
        {
            return ""; //$NON-NLS-1$
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) //$NON-NLS-1$
        {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * The file-name-safe identity of this scope: the digest of every component, in a form that cannot be
     * spelled two ways.
     *
     * <p>Each component is written length-prefixed, so no run of characters in one field can imitate a field
     * boundary and let two different scopes hash alike - the reason a plain {@code a + ":" + b} join is not
     * used for something that decides whose memory a suppression trusts.
     *
     * @return {@value #ID_LENGTH} lower-case hexadecimal characters, never {@code null}
     */
    public String id()
    {
        StringBuilder material = new StringBuilder();
        append(material, mode);
        append(material, serverUrl);
        append(material, projectKey);
        appendBranch(material);
        append(material, pathPrefix);
        append(material, projectName);
        return digestOf(material.toString());
    }

    /**
     * Renders the branch for a human reading the stored file or a log line.
     *
     * @return the branch name, or {@link #DEFAULT_BRANCH} when this scope is the server's default branch
     */
    public String describeBranch()
    {
        return branch != null ? branch : DEFAULT_BRANCH;
    }

    /**
     * Appends one component to the digest material, length-prefixed.
     *
     * @param material the material being built, not {@code null}
     * @param value the component, not {@code null}
     */
    private static void append(StringBuilder material, String value)
    {
        material.append(value.length()).append(':').append(value).append('\n');
    }

    /**
     * Appends the branch, discriminated by presence.
     *
     * <p>The discriminator is what makes "the default branch" a scope of its own: a branch literally called
     * {@link #DEFAULT_BRANCH} would otherwise hash exactly like "no branch at all", and would inherit the
     * default branch's anchors.
     *
     * @param material the material being built, not {@code null}
     */
    private void appendBranch(StringBuilder material)
    {
        if (branch == null)
        {
            material.append(ABSENT).append('\n');
            return;
        }
        material.append(PRESENT);
        append(material, branch);
    }

    /**
     * The truncated hexadecimal digest of a string.
     *
     * @param material the material to digest, not {@code null}
     * @return {@value #ID_LENGTH} lower-case hexadecimal characters, never {@code null}
     */
    private static String digestOf(String material)
    {
        MessageDigest digest;
        try
        {
            digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
        }
        catch (NoSuchAlgorithmException e)
        {
            // SHA-256 is required of every Java platform; this cannot happen on a running JVM.
            throw new IllegalStateException(e);
        }
        byte[] bytes = digest.digest(material.getBytes(StandardCharsets.UTF_8));
        StringBuilder hexadecimal = new StringBuilder(bytes.length * 2);
        for (byte value : bytes)
        {
            hexadecimal.append(Character.forDigit((value >> 4) & 0xf, 16));
            hexadecimal.append(Character.forDigit(value & 0xf, 16));
        }
        return hexadecimal.substring(0, ID_LENGTH);
    }
}
