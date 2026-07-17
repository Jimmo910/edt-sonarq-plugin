/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.checks;

/**
 * How a BSL Language Server diagnostic relates to EDT's own built-in checks, used to steer which
 * diagnostics a user should consider disabling when running local analysis alongside EDT.
 */
public enum DiagnosticCategory
{
    /** Duplicates an existing EDT built-in check; the finding is already surfaced by EDT itself. */
    EDT_DUPLICATE("edt-duplicate"), //$NON-NLS-1$

    /** Useful but noisy or over-triggering by default; worth tuning before relying on it. */
    NEEDS_TUNING("needs-tuning"), //$NON-NLS-1$

    /** Not a good fit for this codebase's conventions or 1C development practice. */
    INAPPROPRIATE("inappropriate"), //$NON-NLS-1$

    /** No known overlap or concern; a plain, generally useful diagnostic. */
    GENERAL("general"); //$NON-NLS-1$

    private final String resourceId;

    DiagnosticCategory(String resourceId)
    {
        this.resourceId = resourceId;
    }

    /**
     * Tells whether this category is one the plugin recommends disabling by default in local analysis.
     *
     * @return {@code true} for {@link #EDT_DUPLICATE}, {@link #NEEDS_TUNING} and {@link #INAPPROPRIATE};
     *     {@code false} for {@link #GENERAL}
     */
    public boolean recommendedDisabled()
    {
        return this != GENERAL;
    }

    /**
     * The identifier used for this category in the bundled JSON resource.
     *
     * @return the resource identifier, not {@code null}
     */
    public String resourceId()
    {
        return resourceId;
    }

    /**
     * Resolves a category from its bundled JSON resource identifier.
     *
     * @param resourceId the stored identifier, may be {@code null}
     * @return the matching category, or {@link #GENERAL} when {@code resourceId} is {@code null} or unknown
     */
    public static DiagnosticCategory fromResourceId(String resourceId)
    {
        for (DiagnosticCategory category : values())
        {
            if (category.resourceId.equals(resourceId))
            {
                return category;
            }
        }
        return GENERAL;
    }
}
