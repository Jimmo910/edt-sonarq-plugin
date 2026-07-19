/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.jimmo.edt.sonarq.core.analysis.DownloadFunction;

/**
 * Resolves the target BSL Language Server release - a version and the download URL of the asset matching
 * the current operating system - for a given {@link BslUpdateChannel}, reading the GitHub releases API for
 * {@code STABLE} and {@code PRERELEASE}.
 *
 * <p>Ported from the approach nixel2007 described in issue #8, rather than depending on the upstream
 * {@code 1c-syntax/utils} library, which pulls in {@code org.kohsuke:github-api}, Jackson, commons-compress
 * and semver4j and is not published to Maven Central - impractical for an OSGi/Tycho plugin. This class
 * only uses the JDK, Gson (already a target-platform dependency) and {@link DownloadFunction}.
 */
public final class BslReleaseResolver
{
    private static final String DOWNLOAD_BASE_URL =
        "https://github.com/1c-syntax/bsl-language-server/releases/download/"; //$NON-NLS-1$

    private static final String LATEST_RELEASE_API_URL =
        "https://api.github.com/repos/1c-syntax/bsl-language-server/releases/latest"; //$NON-NLS-1$

    private static final String RELEASES_LIST_API_URL =
        "https://api.github.com/repos/1c-syntax/bsl-language-server/releases?per_page=15"; //$NON-NLS-1$

    private static final String TAG_PREFIX = "v"; //$NON-NLS-1$

    private static final String ASSET_PREFIX = "bsl-language-server_"; //$NON-NLS-1$

    private static final String ZIP_SUFFIX = ".zip"; //$NON-NLS-1$

    private static final String TAG_NAME_MEMBER = "tag_name"; //$NON-NLS-1$

    private static final String DRAFT_MEMBER = "draft"; //$NON-NLS-1$

    private static final String ASSETS_MEMBER = "assets"; //$NON-NLS-1$

    private static final String ASSET_NAME_MEMBER = "name"; //$NON-NLS-1$

    private static final String ASSET_URL_MEMBER = "browser_download_url"; //$NON-NLS-1$

    private BslReleaseResolver()
    {
    }

    /**
     * A resolved BSL Language Server release.
     *
     * @param version the release version without a leading {@code v}, never {@code null}
     * @param assetUrl the download URL of the asset matching the requested operating system, never
     *     {@code null}
     */
    public record ResolvedRelease(String version, String assetUrl)
    {
    }

    /**
     * Resolves the release target for {@code channel}.
     *
     * <p>{@code FIXED} builds the same URL {@code BslServerInstaller.downloadUrl()} does today, without
     * making any network call. {@code STABLE} and {@code PRERELEASE} query the GitHub releases API through
     * {@code http}.
     *
     * @param channel the update channel, not {@code null}
     * @param http the byte-source used to query the GitHub API, not {@code null}; never invoked for
     *     {@code FIXED}
     * @param osClassifier the operating-system asset classifier ({@code win}/{@code nix}/{@code mac}), not
     *     {@code null}
     * @return the resolved release, never {@code null}
     * @throws IOException if the API cannot be reached, or the response has no suitable release or no
     *     asset for {@code osClassifier}
     */
    public static ResolvedRelease resolve(BslUpdateChannel channel, DownloadFunction http, String osClassifier)
        throws IOException
    {
        if (channel == BslUpdateChannel.FIXED)
        {
            return fixedRelease(osClassifier);
        }
        if (channel == BslUpdateChannel.PRERELEASE)
        {
            return resolvePrerelease(http, osClassifier);
        }
        return resolveStable(http, osClassifier);
    }

    /**
     * Builds the pinned release without any network access.
     *
     * @param osClassifier the operating-system asset classifier, not {@code null}
     * @return the pinned release, never {@code null}
     */
    private static ResolvedRelease fixedRelease(String osClassifier)
    {
        return new ResolvedRelease(BslServerInstaller.VERSION, assetUrlFor(BslServerInstaller.VERSION, osClassifier));
    }

    /**
     * Resolves the latest release object from {@code GET .../releases/latest}.
     *
     * @param http the byte-source used to query the GitHub API, not {@code null}
     * @param osClassifier the operating-system asset classifier, not {@code null}
     * @return the resolved release, never {@code null}
     * @throws IOException if the API cannot be reached or has no asset for {@code osClassifier}
     */
    private static ResolvedRelease resolveStable(DownloadFunction http, String osClassifier) throws IOException
    {
        JsonObject release = readJsonObject(http, LATEST_RELEASE_API_URL);
        return releaseFrom(release, osClassifier);
    }

    /**
     * Resolves the newest non-draft entry from {@code GET .../releases?per_page=15} (newest-first order),
     * including pre-releases.
     *
     * @param http the byte-source used to query the GitHub API, not {@code null}
     * @param osClassifier the operating-system asset classifier, not {@code null}
     * @return the resolved release, never {@code null}
     * @throws IOException if the API cannot be reached, no non-draft release is found, or the chosen
     *     release has no asset for {@code osClassifier}
     */
    private static ResolvedRelease resolvePrerelease(DownloadFunction http, String osClassifier) throws IOException
    {
        JsonArray releases = readJsonArray(http, RELEASES_LIST_API_URL);
        for (JsonElement element : releases)
        {
            JsonObject release = element.getAsJsonObject();
            if (!asBoolean(release, DRAFT_MEMBER))
            {
                return releaseFrom(release, osClassifier);
            }
        }
        throw new IOException("No non-draft release found in the GitHub releases list"); //$NON-NLS-1$
    }

    /**
     * Extracts the version and this-OS asset URL from a single GitHub release JSON object.
     *
     * @param release the release object, not {@code null}
     * @param osClassifier the operating-system asset classifier, not {@code null}
     * @return the resolved release, never {@code null}
     * @throws IOException if {@code release} has no asset named {@code bsl-language-server_<osClassifier>.zip}
     */
    private static ResolvedRelease releaseFrom(JsonObject release, String osClassifier) throws IOException
    {
        String tag = asString(release, TAG_NAME_MEMBER);
        String version = tag.startsWith(TAG_PREFIX) ? tag.substring(TAG_PREFIX.length()) : tag;
        String assetName = assetNameFor(osClassifier);
        String assetUrl = findAssetUrl(release, assetName);
        if (assetUrl == null)
        {
            throw new IOException("Release " + tag + " has no asset named " + assetName); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return new ResolvedRelease(version, assetUrl);
    }

    /**
     * Finds the {@code browser_download_url} of the asset named {@code assetName} in a release's
     * {@code assets} array.
     *
     * @param release the release object, not {@code null}
     * @param assetName the exact asset file name to look for, not {@code null}
     * @return the asset download URL, or {@code null} if no asset with that name exists
     */
    private static String findAssetUrl(JsonObject release, String assetName)
    {
        JsonArray assets = release.getAsJsonArray(ASSETS_MEMBER);
        if (assets == null)
        {
            return null;
        }
        for (JsonElement element : assets)
        {
            JsonObject asset = element.getAsJsonObject();
            if (assetName.equals(asString(asset, ASSET_NAME_MEMBER)))
            {
                return asString(asset, ASSET_URL_MEMBER);
            }
        }
        return null;
    }

    /**
     * Builds the fixed-channel download URL exactly like {@code BslServerInstaller.downloadUrl()} does,
     * for an arbitrary version and OS classifier.
     *
     * @param version the release version without a leading {@code v}, not {@code null}
     * @param osClassifier the operating-system asset classifier, not {@code null}
     * @return the GitHub release asset URL, never {@code null}
     */
    private static String assetUrlFor(String version, String osClassifier)
    {
        return DOWNLOAD_BASE_URL + TAG_PREFIX + version + '/' + assetNameFor(osClassifier);
    }

    /**
     * Builds the expected native asset file name for an operating-system classifier.
     *
     * @param osClassifier the operating-system asset classifier, not {@code null}
     * @return the asset file name, never {@code null}
     */
    private static String assetNameFor(String osClassifier)
    {
        return ASSET_PREFIX + osClassifier + ZIP_SUFFIX;
    }

    /**
     * Reads {@code url} fully and parses it as a JSON object.
     *
     * @param http the byte-source used to query the GitHub API, not {@code null}
     * @param url the URL to open, not {@code null}
     * @return the parsed object, never {@code null}
     * @throws IOException if the stream cannot be read
     */
    private static JsonObject readJsonObject(DownloadFunction http, String url) throws IOException
    {
        return JsonParser.parseString(readFully(http, url)).getAsJsonObject();
    }

    /**
     * Reads {@code url} fully and parses it as a JSON array.
     *
     * @param http the byte-source used to query the GitHub API, not {@code null}
     * @param url the URL to open, not {@code null}
     * @return the parsed array, never {@code null}
     * @throws IOException if the stream cannot be read
     */
    private static JsonArray readJsonArray(DownloadFunction http, String url) throws IOException
    {
        return JsonParser.parseString(readFully(http, url)).getAsJsonArray();
    }

    /**
     * Opens {@code url} and reads the whole response body as UTF-8 text.
     *
     * @param http the byte-source used to query the GitHub API, not {@code null}
     * @param url the URL to open, not {@code null}
     * @return the decoded response body, never {@code null}
     * @throws IOException if the stream cannot be opened or read
     */
    private static String readFully(DownloadFunction http, String url) throws IOException
    {
        try (InputStream stream = http.open(url))
        {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String asString(JsonObject object, String member)
    {
        JsonElement value = object.get(member);
        return value != null && !value.isJsonNull() ? value.getAsString() : ""; //$NON-NLS-1$
    }

    private static boolean asBoolean(JsonObject object, String member)
    {
        JsonElement value = object.get(member);
        return value != null && !value.isJsonNull() && value.getAsBoolean();
    }
}
