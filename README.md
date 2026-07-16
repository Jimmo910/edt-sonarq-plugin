# SonarQ in EDT

SonarQ in EDT shows SonarQube issues for 1C:Enterprise (BSL) code right inside
1C:Enterprise Development Tools. It reads analysis results from a SonarQube server over
the Web API — there is no local/on-the-fly analysis — and is branch-aware where the server
edition supports it (commercial editions), while also working against SonarQube Community
Edition (single-branch).

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

## Requirements

- 1C:Enterprise Development Tools 2025.2 or later (built and tested against the 2026.1
  target platform).
- Java 17 runtime (the one bundled with / used by EDT itself).
- A SonarQube server with an analyzed BSL project (for example, analyzed on CI with
  [sonar-bsl-plugin-community](https://github.com/1c-syntax/sonar-bsl-plugin-community)).

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

- **v2** — editor markers/underlines for issues, and a way to trigger a first analysis
  run from EDT.
- **v3** — an optional local analysis mode (running the Sonar scanner without a
  pre-existing server-side analysis).

## License

Licensed under the [Eclipse Public License 2.0](LICENSE).
