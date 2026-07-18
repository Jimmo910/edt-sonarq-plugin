/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.markers;

/**
 * The outcome of an {@link IssueMarkerSynchronizer#sync} call.
 *
 * @param created the number of markers created, one per entry whose resolved path is an existing file
 *     in the project
 * @param missingFile the number of entries with a non-{@code null} relative path (see
 *     {@link ru.jimmo.edt.sonarq.ui.views.IssueEntry#relativePath()}) whose file does not exist in the
 *     project even after the pre-sync {@link org.eclipse.core.resources.IProject#refreshLocal} - i.e.
 *     issues that were mapped to a project path but still cannot show as a Problems-view marker. This is
 *     distinct from an entry whose component never mapped to a path at all, which
 *     {@link ru.jimmo.edt.sonarq.ui.views.IssueTreeBuilder#countUnmapped} already tracks and which this
 *     count does not include
 */
public record MarkerSyncResult(int created, int missingFile)
{
}
