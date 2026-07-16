/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

/** Small helpers to render rule descriptions. */
public final class RuleHtml
{
    private RuleHtml()
    {
    }

    /**
     * Wraps an HTML fragment into a minimal complete document.
     *
     * @param htmlFragment the fragment, not {@code null}
     * @return the document, never {@code null}
     */
    public static String wrap(String htmlFragment)
    {
        return "<html><head><meta charset=\"utf-8\"></head>" //$NON-NLS-1$
            + "<body style=\"font-family:sans-serif;font-size:13px\">" //$NON-NLS-1$
            + htmlFragment + "</body></html>"; //$NON-NLS-1$
    }

    /**
     * Converts an HTML fragment to plain text.
     *
     * @param htmlFragment the fragment, not {@code null}
     * @return the plain text, never {@code null}
     */
    public static String toPlainText(String htmlFragment)
    {
        return htmlFragment.replaceAll("<[^>]+>", "") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("&lt;", "<").replace("&gt;", ">") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .replace("&quot;", "\"").replace("&#39;", "'") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .replace("&amp;", "&") //$NON-NLS-1$ //$NON-NLS-2$
            .trim();
    }
}
