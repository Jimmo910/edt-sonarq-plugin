/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui;

import org.eclipse.osgi.util.NLS;

/** Localized UI messages. */
public final class Messages extends NLS
{
    private static final String BUNDLE_NAME = "ru.jimmo.edt.sonarq.ui.messages"; //$NON-NLS-1$

    public static String IssuesView_BranchMissing;
    public static String IssuesView_Column_Location;
    public static String IssuesView_Column_Message;
    public static String IssuesView_Column_Rule;
    public static String IssuesView_Column_Severity;
    public static String IssuesView_FileMissing_Tooltip;
    public static String IssuesView_FilterText_Hint;
    public static String IssuesView_GroupByFile;
    public static String IssuesView_GroupByRule;
    public static String IssuesView_ProjectMenu;
    public static String IssuesView_RefreshAction;
    public static String IssuesView_SeverityMenu;
    public static String IssuesView_ShowMainBranch;
    public static String IssuesView_Status_AuthError;
    public static String IssuesView_Status_Error;
    public static String IssuesView_Status_Loaded;
    public static String IssuesView_Status_LoadedNoBranch;
    public static String IssuesView_Status_NotConfigured;
    public static String IssuesView_Status_Truncated;
    public static String IssuesView_TypeMenu;
    public static String IssuesView_UnmappedGroup;
    public static String PreferencePage_Description;
    public static String PreferencePage_ServerUrl;
    public static String PreferencePage_TestConnection;
    public static String PreferencePage_TestFailure;
    public static String PreferencePage_TestSuccess;
    public static String PreferencePage_TimeoutSeconds;
    public static String PreferencePage_Token;
    public static String PropertyPage_Branch;
    public static String PropertyPage_BranchHint;
    public static String PropertyPage_FindKey;
    public static String PropertyPage_FindKeyNoMatch;
    public static String PropertyPage_PathPrefix;
    public static String PropertyPage_ProjectKey;
    public static String RefreshJob_Name;
    public static String RuleJob_Name;
    public static String RulePanel_LoadFailed;
    public static String RulePanel_Loading;

    static
    {
        NLS.initializeMessages(BUNDLE_NAME, Messages.class);
    }

    private Messages()
    {
    }
}
