/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.suppress;

import ru.jimmo.edt.sonarq.core.suppress.SuppressionOutcome;
import ru.jimmo.edt.sonarq.ui.Messages;

/**
 * Turns a refused {@link SuppressionOutcome} into the sentence shown to the user.
 *
 * <p>Shared by the two entry points of the quick-suppress - the issues view's context menu, which shows it on
 * its status line, and the Problems view's quick fix, which shows it in a dialog - so a refusal reads the
 * same wherever it happened. Without it, both would look like a menu entry that did nothing at all.
 */
public final class SuppressionMessages
{
    private SuppressionMessages()
    {
    }

    /**
     * Describes why nothing was written.
     *
     * @param outcome the refusal, not {@code null}; {@link SuppressionOutcome#INSERTED} is not a refusal and
     *     yields an empty string, so a caller cannot accidentally announce a successful edit as a failure
     * @return the localized explanation, never {@code null}
     */
    public static String describe(SuppressionOutcome outcome)
    {
        return switch (outcome)
        {
            case INSERTED -> ""; //$NON-NLS-1$
            case ALREADY_SUPPRESSED -> Messages.Suppress_Refused_AlreadySuppressed;
            case ANCHOR_NOT_FOUND -> Messages.Suppress_Refused_FileChanged;
            case UNSAVED_CHANGES -> Messages.Suppress_Refused_UnsavedChanges;
            case NO_BUFFER -> Messages.Suppress_Refused_Unavailable;
        };
    }
}
