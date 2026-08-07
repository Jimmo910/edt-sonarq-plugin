/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.mapping;

import java.util.Optional;

/**
 * Maps SonarQube component keys to EDT project relative paths.
 *
 * <p>This is the single place where a path that came from outside - a SonarQube server's component key, or
 * the SARIF report of a local analyzer run - turns into a path resolved against the bound project. Whatever
 * it returns ends up in {@code IProject#getFile}, and from there in a Problems-view marker, in the editor
 * the user is navigated to, and in the file a quick suppression <em>writes to</em>. A hostile or compromised
 * server could therefore answer with {@code projKey:../OtherProject/src/Module.bsl} and have the plug-in
 * mark - and edit - files outside the project the user bound. Traversal and absolute paths are rejected here
 * rather than at each of those three call sites, so no future consumer can be added without the check
 * (review minor M7).
 */
public final class ComponentPathMapper
{
    private ComponentPathMapper()
    {
    }

    /**
     * Extracts the project-relative file path from a component key.
     *
     * @param componentKey the component key ({@code <projectKey>:<path>}), may be {@code null}
     * @param projectKey the expected project key, not {@code null}
     * @param pathPrefix the repository sub-directory holding the EDT project, may be {@code null} or empty
     * @return the project-relative path, or empty when the key does not match or the path would leave the
     *     project (see {@link #escapesProject})
     */
    public static Optional<String> toProjectRelativePath(String componentKey, String projectKey, String pathPrefix)
    {
        String keyPrefix = projectKey + ':';
        if (componentKey == null || !componentKey.startsWith(keyPrefix))
        {
            return Optional.empty();
        }
        String path = componentKey.substring(keyPrefix.length());
        if (pathPrefix != null && !pathPrefix.isEmpty())
        {
            String normalized = pathPrefix.endsWith("/") ? pathPrefix : pathPrefix + '/'; //$NON-NLS-1$
            if (!path.startsWith(normalized))
            {
                return Optional.empty();
            }
            path = path.substring(normalized.length());
        }
        if (path.isEmpty() || escapesProject(path))
        {
            return Optional.empty();
        }
        return Optional.of(path);
    }

    /**
     * Tells whether a project-relative path could resolve outside the project it is resolved against.
     *
     * <p>Both separators are treated as such: {@code IProject#getFile(String)} parses its argument through
     * {@code org.eclipse.core.runtime.Path}, which on Windows converts backslashes to slashes, so a
     * {@code ..\\Other} segment is every bit as effective as {@code ../Other}. Rejected are a {@code ..}
     * segment anywhere, a leading separator (root-relative), and a Windows drive or UNC prefix. A {@code ..}
     * that is merely part of a name, as in {@code src/a..b/Module.bsl}, is not a segment and stays allowed.
     *
     * @param path the path extracted from the component key, not {@code null} and not empty
     * @return {@code true} when the path must not be resolved against the project
     */
    private static boolean escapesProject(String path)
    {
        String unified = path.replace('\\', '/');
        if (unified.charAt(0) == '/' || hasDriveLetter(unified))
        {
            return true;
        }
        for (String segment : unified.split("/", -1)) //$NON-NLS-1$
        {
            if ("..".equals(segment)) //$NON-NLS-1$
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether the path starts with a Windows drive specification such as {@code C:} or {@code c:/}.
     *
     * @param path the path with separators unified to {@code /}, not {@code null} and not empty
     * @return {@code true} when the path is drive-qualified and therefore absolute
     */
    private static boolean hasDriveLetter(String path)
    {
        return path.length() >= 2 && path.charAt(1) == ':' && Character.isLetter(path.charAt(0));
    }
}
