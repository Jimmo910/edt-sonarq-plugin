/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.mapping;

import java.util.Optional;

/** Maps SonarQube component keys to EDT project relative paths. */
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
     * @return the project-relative path, or empty when the key does not match
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
        return path.isEmpty() ? Optional.empty() : Optional.of(path);
    }
}
