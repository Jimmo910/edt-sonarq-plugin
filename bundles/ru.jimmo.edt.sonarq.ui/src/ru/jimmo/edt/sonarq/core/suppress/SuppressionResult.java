/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.suppress;

/**
 * What one quick-suppress attempt did: the outcome, and - when it wrote something - <em>which line</em> it
 * wrapped.
 *
 * <p>The line is not a detail for the caller's benefit; it is what keeps the models honest. A suppression
 * does not necessarily edit the line it was asked about: {@link LineAnchor#resolveLine} follows the anchor to
 * the line that still carries it, which after a server-mode refresh (or any edit above the issue) is
 * regularly a few lines away from the recorded number. Every caller that renumbers its own line numbers
 * afterwards - the issues view's snapshot, the file's other issue markers - has to renumber around the line
 * the two comments really went in at. Renumbering around the number the analysis reported instead moves the
 * wrong siblings by two lines, which is how a model drifts out of step with the file it describes.
 *
 * @param outcome what happened, not {@code null}
 * @param line the 1-based line the {@code -off}/{@code -on} pair was wrapped around, in the numbering the
 *     file had <em>before</em> the insertion (which is what {@link SuppressionLineShift#shiftedLine} expects);
 *     {@link #NO_LINE} whenever nothing was written
 */
public record SuppressionResult(SuppressionOutcome outcome, int line)
{
    /** The line of a result that wrote nothing: there is no line to renumber around. */
    public static final int NO_LINE = 0;

    /**
     * Rejects the two combinations that would mislead a caller: a refusal claiming a line, and an insertion
     * without one. Callers act on {@link #line()} only after {@link #inserted()}, and a wrong line there is
     * silent - it corrupts a model rather than failing.
     */
    public SuppressionResult
    {
        if (outcome.inserted() != (line > 0))
        {
            throw new IllegalArgumentException(outcome + " with line " + line); //$NON-NLS-1$
        }
    }

    /**
     * The result of a suppression that wrote the comment pair.
     *
     * @param line the 1-based line that was wrapped, must be {@code > 0}
     * @return the result, never {@code null}
     */
    public static SuppressionResult inserted(int line)
    {
        return new SuppressionResult(SuppressionOutcome.INSERTED, line);
    }

    /**
     * The result of a suppression that wrote nothing.
     *
     * @param outcome why nothing was written, not {@code null} and not
     *     {@link SuppressionOutcome#INSERTED}
     * @return the result, never {@code null}
     */
    public static SuppressionResult refused(SuppressionOutcome outcome)
    {
        return new SuppressionResult(outcome, NO_LINE);
    }

    /**
     * Tells whether the file was really changed.
     *
     * @return {@code true} only when the comment pair was written
     */
    public boolean inserted()
    {
        return outcome.inserted();
    }
}
