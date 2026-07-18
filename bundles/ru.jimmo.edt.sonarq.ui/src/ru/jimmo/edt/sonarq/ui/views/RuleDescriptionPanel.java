/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.LocationListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;

import ru.jimmo.edt.sonarq.core.model.SonarRule;

/**
 * Shows a SonarQube rule's description, preferring an embedded {@link Browser} for HTML rendering
 * and falling back to a read-only {@link StyledText} when no browser implementation is available
 * on the platform, or when the platform is GTK (Linux), where a {@link Browser} commonly renders
 * blank instead of failing outright (see {@link #preferBrowser(String)}).
 */
public class RuleDescriptionPanel extends Composite
{
    private static final String PAIRED_TAGS =
        "(?is)<\\s*(script|style|iframe|object)\\b.*?<\\s*/\\s*\\1\\s*>"; //$NON-NLS-1$
    private static final String VOID_TAGS =
        "(?is)<\\s*(img|iframe|object|embed|link|meta|form|input)\\b[^>]*>"; //$NON-NLS-1$
    private static final String EVENT_ATTRS =
        "(?is)\\son\\w+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)"; //$NON-NLS-1$
    private static final String EMPTY = ""; //$NON-NLS-1$

    private static final String PLATFORM_GTK = "gtk"; //$NON-NLS-1$

    private Browser browser;
    private StyledText styledText;

    /**
     * Creates the panel and, on a platform where a {@link Browser} is preferred (see
     * {@link #preferBrowser(String)}), tries to embed one, falling back to a plain {@link StyledText} when
     * the platform has no browser implementation at all. On GTK the {@link Browser} is never even
     * attempted and {@link StyledText} is used directly.
     *
     * @param parent the parent composite, not {@code null}
     */
    public RuleDescriptionPanel(Composite parent)
    {
        super(parent, SWT.NONE);
        setLayout(new FillLayout());
        if (preferBrowser(SWT.getPlatform()))
        {
            try
            {
                browser = new Browser(this, SWT.NONE);
                // Rule descriptions are HTML from the server (or the local analyzer); never let them run
                // scripts in the embedded browser - the pane only ever renders static documentation.
                browser.setJavascriptEnabled(false);
                // Also veto navigation, so a meta refresh, a link or a form in the rule HTML cannot make the
                // pane load or post to a remote location. The static content shown via setText loads as
                // about:blank, which stays allowed.
                browser.addLocationListener(LocationListener.changingAdapter(event ->
                {
                    if (!event.location.startsWith("about:")) //$NON-NLS-1$
                    {
                        event.doit = false;
                    }
                }));
            }
            catch (SWTError e)
            {
                styledText = new StyledText(this, SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
            }
        }
        else
        {
            styledText = new StyledText(this, SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        }
    }

    /**
     * Tells whether an SWT {@link Browser} should even be attempted on the given platform, rather than
     * going straight to the {@link StyledText} plain-text fallback.
     *
     * <p>On GTK (Linux), a {@link Browser} very often constructs successfully - no {@link SWTError} is
     * thrown, so the existing catch below never triggers - but then renders a completely blank pane,
     * because the underlying WebKit2GTK library is missing or broken. This is a frequent state on
     * EDT-on-Linux installations and was reported against v0.5.0 (AndreiRch, issue #4): the rule
     * description pane showed nothing, with no error anywhere. A blank pane that looks like a hang or a
     * missing feature is strictly worse than a readable plain-text rendering of the same content, so on
     * GTK the {@link Browser} is never attempted at all and {@link StyledText} (backed by
     * {@link RuleHtml#toPlainText(String)}) is used unconditionally. On win32 and Cocoa the {@link Browser}
     * renders correctly, so it stays the preferred widget there, with the existing {@link SWTError} catch
     * kept as a defense for the rare case it is unavailable.
     *
     * @param platform the SWT platform identifier, as returned by {@link SWT#getPlatform()}, not
     *     {@code null}
     * @return {@code true} when a {@link Browser} should be attempted, {@code false} to go straight to the
     *     {@link StyledText} fallback
     */
    static boolean preferBrowser(String platform)
    {
        return !PLATFORM_GTK.equals(platform);
    }

    /**
     * Shows a rule's name, key and HTML description.
     *
     * @param rule the rule to display, not {@code null}
     */
    public void showRule(SonarRule rule)
    {
        String title = "<h3>" + escapeHtml(rule.name()) //$NON-NLS-1$
            + " (" + escapeHtml(rule.key()) + ")</h3>"; //$NON-NLS-1$ //$NON-NLS-2$
        if (browser != null)
        {
            browser.setText(RuleHtml.wrap(title + sanitize(rule.htmlDescription())));
        }
        else
        {
            styledText.setText(rule.name() + "\n\n" + RuleHtml.toPlainText(rule.htmlDescription())); //$NON-NLS-1$
        }
    }

    /**
     * Strips active-content and resource-loading elements from rule HTML as defense in depth. JavaScript
     * is already disabled and navigation is vetoed, but a server-provided description could still carry an
     * {@code <img>}, {@code <iframe>} or {@code <meta http-equiv=refresh>} that issues a network request
     * from the EDT host; removing those elements closes that vector. Local-analysis descriptions are
     * rendered from Markdown and contain none of these, so this is a no-op for them.
     *
     * @param html the rule HTML, not {@code null}
     * @return the sanitized HTML, never {@code null}
     */
    private static String sanitize(String html)
    {
        return html.replaceAll(PAIRED_TAGS, EMPTY).replaceAll(VOID_TAGS, EMPTY).replaceAll(EVENT_ATTRS, EMPTY);
    }

    /**
     * Shows a plain status or error message instead of a rule description.
     *
     * @param text the message to display, not {@code null}
     */
    public void showMessage(String text)
    {
        if (browser != null)
        {
            browser.setText(RuleHtml.wrap(escapeHtml(text)));
        }
        else
        {
            styledText.setText(text);
        }
    }

    private static String escapeHtml(String text)
    {
        return text.replace("&", "&amp;") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("<", "&lt;") //$NON-NLS-1$ //$NON-NLS-2$
            .replace(">", "&gt;"); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
