/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import ru.jimmo.edt.sonarq.core.analysis.DownloadFunction;

/** Tests for {@link BslReleaseResolver}. */
public class BslReleaseResolverTest
{
    private static final String LATEST_URL =
        "https://api.github.com/repos/1c-syntax/bsl-language-server/releases/latest";

    private static final String RELEASES_URL =
        "https://api.github.com/repos/1c-syntax/bsl-language-server/releases?per_page=15";

    private static final String LATEST_JSON = """
        {
          "tag_name": "v1.2.3",
          "draft": false,
          "prerelease": false,
          "assets": [
            {
              "name": "bsl-language-server_win.zip",
              "browser_download_url": "https://example.org/download/v1.2.3/bsl-language-server_win.zip"
            },
            {
              "name": "bsl-language-server_nix.zip",
              "browser_download_url": "https://example.org/download/v1.2.3/bsl-language-server_nix.zip"
            }
          ]
        }""";

    private static final String LATEST_JSON_MISSING_MAC_ASSET = """
        {
          "tag_name": "v1.2.3",
          "draft": false,
          "prerelease": false,
          "assets": [
            {
              "name": "bsl-language-server_win.zip",
              "browser_download_url": "https://example.org/download/v1.2.3/bsl-language-server_win.zip"
            },
            {
              "name": "bsl-language-server_nix.zip",
              "browser_download_url": "https://example.org/download/v1.2.3/bsl-language-server_nix.zip"
            }
          ]
        }""";

    private static final String RELEASES_JSON_WITH_LEADING_DRAFT = """
        [
          {
            "tag_name": "v2.0.0-rc1",
            "draft": true,
            "prerelease": true,
            "assets": []
          },
          {
            "tag_name": "v1.9.0",
            "draft": false,
            "prerelease": true,
            "assets": [
              {
                "name": "bsl-language-server_win.zip",
                "browser_download_url": "https://example.org/download/v1.9.0/bsl-language-server_win.zip"
              },
              {
                "name": "bsl-language-server_nix.zip",
                "browser_download_url": "https://example.org/download/v1.9.0/bsl-language-server_nix.zip"
              }
            ]
          },
          {
            "tag_name": "v1.8.0",
            "draft": false,
            "prerelease": false,
            "assets": []
          }
        ]""";

    private static DownloadFunction respondingWith(String url, String json)
    {
        return requestedUrl ->
        {
            if (url.equals(requestedUrl))
            {
                return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
            }
            throw new IOException("unexpected URL requested: " + requestedUrl);
        };
    }

    @Test
    public void fixedChannelBuildsThePinnedUrlWithoutQueryingTheNetwork() throws IOException
    {
        DownloadFunction http = url ->
        {
            fail("FIXED channel must not query the network, but opened: " + url);
            return null;
        };

        BslReleaseResolver.ResolvedRelease resolved = BslReleaseResolver.resolve(BslUpdateChannel.FIXED, http, "win");

        assertEquals(BslServerInstaller.VERSION, resolved.version());
        assertEquals(
            "https://github.com/1c-syntax/bsl-language-server/releases/download/v" + BslServerInstaller.VERSION
                + "/bsl-language-server_win.zip",
            resolved.assetUrl());
    }

    @Test
    public void stableChannelResolvesLatestReleaseAssetForWindows() throws IOException
    {
        DownloadFunction http = respondingWith(LATEST_URL, LATEST_JSON);

        BslReleaseResolver.ResolvedRelease resolved =
            BslReleaseResolver.resolve(BslUpdateChannel.STABLE, http, "win");

        assertEquals("1.2.3", resolved.version());
        assertEquals("https://example.org/download/v1.2.3/bsl-language-server_win.zip", resolved.assetUrl());
    }

    @Test
    public void stableChannelResolvesLatestReleaseAssetForNix() throws IOException
    {
        DownloadFunction http = respondingWith(LATEST_URL, LATEST_JSON);

        BslReleaseResolver.ResolvedRelease resolved =
            BslReleaseResolver.resolve(BslUpdateChannel.STABLE, http, "nix");

        assertEquals("1.2.3", resolved.version());
        assertEquals("https://example.org/download/v1.2.3/bsl-language-server_nix.zip", resolved.assetUrl());
    }

    @Test
    public void prereleaseChannelSkipsALeadingDraftEntry() throws IOException
    {
        DownloadFunction http = respondingWith(RELEASES_URL, RELEASES_JSON_WITH_LEADING_DRAFT);

        BslReleaseResolver.ResolvedRelease resolved =
            BslReleaseResolver.resolve(BslUpdateChannel.PRERELEASE, http, "win");

        // The first array entry is a draft (v2.0.0-rc1); it must be skipped in favor of the next
        // non-draft entry, even though that one is itself a pre-release (v1.9.0).
        assertEquals("1.9.0", resolved.version());
        assertEquals("https://example.org/download/v1.9.0/bsl-language-server_win.zip", resolved.assetUrl());
    }

    @Test
    public void missingOsAssetThrowsIOException() throws IOException
    {
        DownloadFunction http = respondingWith(LATEST_URL, LATEST_JSON_MISSING_MAC_ASSET);

        try
        {
            BslReleaseResolver.resolve(BslUpdateChannel.STABLE, http, "mac");
            fail("expected IOException for a missing bsl-language-server_mac.zip asset");
        }
        catch (IOException e)
        {
            assertTrue("expected the message to name the missing asset, got: " + e.getMessage(),
                e.getMessage().contains("bsl-language-server_mac.zip"));
        }
    }
}
