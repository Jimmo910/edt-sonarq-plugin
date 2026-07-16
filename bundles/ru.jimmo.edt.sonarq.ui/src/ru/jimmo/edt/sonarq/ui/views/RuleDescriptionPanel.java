/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;

import ru.jimmo.edt.sonarq.core.model.SonarRule;

/**
 * Shows a SonarQube rule's description, preferring an embedded {@link Browser} for HTML rendering
 * and falling back to a read-only {@link StyledText} when no browser implementation is available
 * on the platform.
 */
public class RuleDescriptionPanel extends Composite
{
    private Browser browser;
    private StyledText styledText;

    /**
     * Creates the panel and tries to embed a {@link Browser}, falling back to a plain
     * {@link StyledText} if the platform has no browser implementation.
     *
     * @param parent the parent composite, not {@code null}
     */
    public RuleDescriptionPanel(Composite parent)
    {
        super(parent, SWT.NONE);
        setLayout(new FillLayout());
        try
        {
            browser = new Browser(this, SWT.NONE);
        }
        catch (SWTError e)
        {
            styledText = new StyledText(this, SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        }
    }

    /**
     * Shows a rule's name, key and HTML description.
     *
     * @param rule the rule to display, not {@code null}
     */
    public void showRule(SonarRule rule)
    {
        String title = "<h3>" + escapeHtml(rule.name()) //$NON-NLS-1$
            + " (" + rule.key() + ")</h3>"; //$NON-NLS-1$ //$NON-NLS-2$
        if (browser != null)
        {
            browser.setText(RuleHtml.wrap(title + rule.htmlDescription()));
        }
        else
        {
            styledText.setText(rule.name() + "\n\n" + RuleHtml.toPlainText(rule.htmlDescription())); //$NON-NLS-1$
        }
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
