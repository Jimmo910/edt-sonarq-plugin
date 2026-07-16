# SonarQ in EDT

SonarQ in EDT shows SonarQube issues for 1C:Enterprise (BSL) code right inside
1C:Enterprise Development Tools. Its default mode reads analysis results from a
SonarQube server over the Web API, and is branch-aware where the server edition supports
it (commercial editions), while also working against SonarQube Community Edition
(single-branch). A serverless local analysis mode is also available, running the BSL
Language Server directly against the project's sources with no server involved.

## Features

- **SonarQube Issues view** — a tree of issues for the active project, grouped by file or
  by rule, with toolbar filters for severity, type and a free-text search.
- **Jump to code** — double-click an issue to open the corresponding module at the
  reported line.
- **Rule description pane** — selecting an issue loads and shows the full rule
  description alongside the tree.
- **Automatic git branch detection** — the view detects the current git branch of the
  project and queries that branch on the server, falling back to the server's main
  branch (with an on-screen notice) when the local branch was never analyzed.
- **Per-project binding** — each project is bound to a SonarQube project key (with a
  "Find Key on Server" lookup), an optional fixed branch override, and an optional path
  prefix for repositories where the project is not analyzed from its root.
- **Token authentication** — a user token is used as a Bearer token, falling back to
  HTTP Basic auth for older server versions; the token is stored in the Eclipse Secure
  Storage, never in plain preferences.
- **Run branch analysis right from the view** — a toolbar action (and a link on the
  "branch not analyzed" banner) launches analysis using an auto-downloaded sonar-scanner
  CLI, a custom scanner path, or a CI webhook trigger, whichever is configured; scanner
  output streams to the SonarQube Analysis console, and the view refreshes automatically
  once the server finishes processing the report.
- **Issues appear right in the code** — transient problem markers show up in the
  Problems view and as editor underlines/gutter icons (BLOCKER/CRITICAL as errors, MAJOR
  as warnings, the rest as infos); markers are rebuilt on every refresh and never go
  stale across restarts.
- **Serverless local analysis mode** — switch the plugin to BSL Language Server
  (auto-downloaded native build, no Java or SonarQube server required) and get the same
  issues view, filters and editor markers from a fully local analysis.

## Requirements

- 1C:Enterprise Development Tools 2025.2 or later (built and tested against the 2026.1
  target platform).
- Java 17 runtime (the one bundled with / used by EDT itself).
- A SonarQube server with an analyzed BSL project (for example, analyzed on CI with
  [sonar-bsl-plugin-community](https://github.com/1c-syntax/sonar-bsl-plugin-community))
  — for the server mode; not needed in local analysis mode.
- Internet access once, on the first refresh in local analysis mode, to auto-download
  the BSL Language Server native build (unless a local executable is configured).

## Installation

**From a p2 update-site archive** (recommended):

1. Download the `ru.jimmo.edt.sonarq.repository` update-site zip from the project's
   Releases page (or build it yourself, see [Building](#building) below).
2. In EDT: `Help` > `Install New Software...` > `Add...` > `Archive...`, point it at the
   downloaded zip, select the **SonarQ in EDT** feature, and finish the wizard.
3. Restart EDT when prompted.

**From the command line** (p2 director), for scripted / unattended installs:

```
<edt-install>/1cedtc.exe -nosplash -application org.eclipse.equinox.p2.director ^
  -repository file:/<path-to-extracted-repository> ^
  -installIU ru.jimmo.edt.sonarq.feature.feature.group ^
  -vm <path-to-jdk17>/bin/javaw.exe
```

## Configuration

1. **Preferences > SonarQube** — set the server URL and an authentication token, then use
   **Test Connection** to verify them against the server's version endpoint.
2. **Project Properties > SonarQube** (per project) — set the SonarQube project key, or
   use **Find Key on Server** to look it up by project name; optionally set a fixed
   branch (overrides automatic git detection) and a path prefix (when the project's
   sources are analyzed from a subdirectory of the repository).
3. Open the **SonarQube Issues** view (`Window` > `Show View` > `Other...` > `SonarQube`
   category) and use its **Refresh** toolbar action to load issues for the selected
   project.
4. **Preferences > SonarQube > Analysis launch** (optional) — choose how the **Run Branch
   Analysis** toolbar action starts an analysis: local scanner with automatic download,
   a local scanner at a specified path, or a CI trigger URL (supports a `{branch}`
   placeholder, plus an optional secret sent as an `Authorization` header); extra scanner
   arguments can be added for either local-scanner mode. The token used for the server
   connection needs the **Execute Analysis** permission for the local-scanner modes. On
   SonarQube Community Edition, which has no branch support, analysis always lands in the
   single default branch — a confirmation dialog warns before overwriting an
   already-analyzed branch. For GitLab pipeline triggers the token travels inside the URL
   template and is therefore stored in plain (non-encrypted) preferences — prefer a
   low-privilege trigger token.
5. **Preferences > SonarQube > Editor markers** — **Show issues in editor** (on by
   default) puts SonarQube issues into the Problems view and into the editor as
   underlines/gutter icons; unchecking it clears all existing markers. **Refresh
   automatically in background** (off by default), together with a refresh interval in
   minutes, periodically re-fetches issues and rebuilds markers for all bound projects
   even while the SonarQube Issues view is closed; the first automatic run happens one
   interval after EDT startup.

### Local analysis mode

**Preferences > SonarQube > Mode** — switch from **SonarQube server** to **Local
analysis (BSL Language Server)** to get issues without a server at all. In this mode:

- An optional path to your own `bsl-language-server` executable can be set; leave it
  empty to have the plugin auto-download the native build (about 170 MB) into its state
  area the first time the view is refreshed.
- Every **Refresh** of the SonarQube Issues view runs a fresh local analysis of the
  project's sources and rebuilds the view, filters and editor markers from its result —
  there is nothing to keep in sync in the background.
- Branches, the **Run Branch Analysis** action and the **Analysis launch**/CI-trigger
  settings do not apply in this mode; the project is analyzed as a whole, with no branch
  concept.
- Rule descriptions in the rule pane come from the BSL Language Server's own analyzer
  metadata (bundled in its report), not from a SonarQube server.

## Building

```powershell
$env:JAVA_HOME = '<path-to-jdk-17>'
mvn clean verify
```

The p2 repository is produced at
`repositories/ru.jimmo.edt.sonarq.repository/target/repository/` (plus a zip archive of
the same content).

## Screenshot

![SonarQube Issues view](docs/images/issues-view.png)
<!-- TODO: add a screenshot of the SonarQube Issues view once the plugin has a stable UI to capture. -->

## Roadmap

All roadmap items shipped (v1 viewer, v1.1 analysis launch, v2 editor markers, v3
local analysis).

## License

Licensed under the [Eclipse Public License 2.0](LICENSE).
