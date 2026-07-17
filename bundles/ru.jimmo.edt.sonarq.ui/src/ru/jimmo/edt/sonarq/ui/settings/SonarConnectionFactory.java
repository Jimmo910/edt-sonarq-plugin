/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.settings;

import java.util.Optional;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IPreferencesService;

import ru.jimmo.edt.sonarq.core.client.SonarConnection;
import ru.jimmo.edt.sonarq.ui.SonarqPlugin;

/** Builds {@link SonarConnection} from workspace preferences and the secure token store. */
public final class SonarConnectionFactory
{
    /**
     * Creates a connection from the current workspace settings.
     *
     * @return the connection, or empty when the server URL is not configured
     */
    public Optional<SonarConnection> create()
    {
        IPreferencesService service = Platform.getPreferencesService();
        String url = service.getString(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_SERVER_URL,
            "", null); //$NON-NLS-1$
        if (url.isBlank())
        {
            return Optional.empty();
        }
        int timeout = service.getInt(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_TIMEOUT_SECONDS,
            PreferenceConstants.DEFAULT_TIMEOUT_SECONDS, null);
        String trimmedUrl = url.trim();
        return Optional.of(
            SonarConnection.of(trimmedUrl, new SecureTokenStore().loadToken(trimmedUrl), timeout));
    }
}
