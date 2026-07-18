/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Cleans the auto-generated Markdown the BSL Language Server embeds in a SARIF rule's
 * {@code fullDescription.text}, before it is rendered to HTML by {@link MarkdownHtml}.
 *
 * <p>The raw Markdown is regular: an H1 title ({@code # Name (Key)}), then an auto-generated metadata
 * pipe-table (type/languages/severity/tags, whose cells contain literal {@code <br>}), then
 * {@code <!-- -->} HTML comments, then the real body starting at the first H2 heading. None of that
 * scaffolding is useful once rendered - the pipe-table cells' {@code <br>} and the comments show up as
 * visible junk - so this class strips it, keeping only the H1 title and everything from the first H2
 * onward.
 */
public final class DiagnosticDescription
{
    private static final String EMPTY = ""; //$NON-NLS-1$

    private static final String NEWLINE = "\n"; //$NON-NLS-1$

    private static final String HEADING1_PREFIX = "# "; //$NON-NLS-1$

    private static final String HEADING2_PREFIX = "## "; //$NON-NLS-1$

    private static final String PIPE = "|"; //$NON-NLS-1$

    private static final String BR_MARKER = "<br"; //$NON-NLS-1$

    private static final Pattern HTML_COMMENT = Pattern.compile("(?s)<!--.*?-->"); //$NON-NLS-1$

    private static final Pattern BREAK_TAG = Pattern.compile("(?i)<\\s*br\\s*/?\\s*>"); //$NON-NLS-1$

    private static final Pattern BLANK_LINE_RUN = Pattern.compile("\n{3,}"); //$NON-NLS-1$

    private DiagnosticDescription()
    {
    }

    /**
     * Cleans a raw diagnostic Markdown body: normalizes line endings, removes HTML comments, drops the
     * auto-generated metadata table (keeping the H1 title and everything from the first H2 onward), turns
     * any remaining {@code <br>} variant into a space, and collapses runs of blank lines.
     *
     * @param rawMarkdown the raw Markdown, may be {@code null} or blank
     * @return the cleaned Markdown, never {@code null}; empty when {@code rawMarkdown} is {@code null} or
     *     blank
     */
    public static String cleanMarkdown(String rawMarkdown)
    {
        if (rawMarkdown == null || rawMarkdown.isBlank())
        {
            return EMPTY;
        }
        String normalized = rawMarkdown.replace("\r\n", NEWLINE).replace('\r', '\n'); //$NON-NLS-1$
        String withoutComments = HTML_COMMENT.matcher(normalized).replaceAll(EMPTY);
        String withoutMetadata = dropMetadataBlock(withoutComments);
        String withoutBreaks = BREAK_TAG.matcher(withoutMetadata).replaceAll(" "); //$NON-NLS-1$
        String collapsed = BLANK_LINE_RUN.matcher(withoutBreaks).replaceAll("\n\n"); //$NON-NLS-1$
        return collapsed.strip();
    }

    /**
     * Drops the auto-generated metadata block. When a first H2 heading is found, keeps a leading H1 title
     * line (if present) followed by everything from the H2 onward, discarding the lines in between (the
     * metadata table). Otherwise falls back to removing every line that is both a pipe-table row and
     * carries a literal {@code <br>}.
     *
     * @param markdown the comment-free Markdown, not {@code null}
     * @return the Markdown with the metadata block removed, never {@code null}
     */
    private static String dropMetadataBlock(String markdown)
    {
        String[] lines = markdown.split(NEWLINE, -1);
        int heading2Index = firstHeading2Index(lines);
        if (heading2Index < 0)
        {
            return dropBrBearingPipeRows(lines);
        }
        List<String> kept = new ArrayList<>();
        String heading1 = firstHeading1Line(lines);
        if (heading1 != null)
        {
            kept.add(heading1);
        }
        for (int i = heading2Index; i < lines.length; i++)
        {
            kept.add(lines[i]);
        }
        return String.join(NEWLINE, kept);
    }

    private static int firstHeading2Index(String[] lines)
    {
        for (int i = 0; i < lines.length; i++)
        {
            if (lines[i].trim().startsWith(HEADING2_PREFIX))
            {
                return i;
            }
        }
        return -1;
    }

    private static String firstHeading1Line(String[] lines)
    {
        for (String line : lines)
        {
            if (line.trim().startsWith(HEADING1_PREFIX))
            {
                return line;
            }
        }
        return null;
    }

    private static String dropBrBearingPipeRows(String[] lines)
    {
        List<String> kept = new ArrayList<>();
        for (String line : lines)
        {
            String trimmed = line.trim();
            boolean metadataRow =
                trimmed.startsWith(PIPE) && trimmed.toLowerCase(Locale.ROOT).contains(BR_MARKER);
            if (!metadataRow)
            {
                kept.add(line);
            }
        }
        return String.join(NEWLINE, kept);
    }
}
