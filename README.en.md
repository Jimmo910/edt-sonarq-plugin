# SonarQ in EDT

[![CI](https://github.com/Jimmo910/edt-sonarq-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/Jimmo910/edt-sonarq-plugin/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/Jimmo910/edt-sonarq-plugin?sort=semver)](https://github.com/Jimmo910/edt-sonarq-plugin/releases)
[![License: EPL 2.0](https://img.shields.io/badge/License-EPL_2.0-blue.svg)](LICENSE)
[![Java 17](https://img.shields.io/badge/Java-17-orange.svg)](#requirements)
[![1C:EDT 2026.1](https://img.shields.io/badge/1C%3AEDT-2026.1-brightgreen.svg)](#requirements)

**English** | [Русский](README.md)

A plugin for **1C:Enterprise Development Tools (EDT)** that shows **SonarQube** issues for
1C:Enterprise (BSL) code right inside the IDE. Its default mode reads analysis results from a
SonarQube server over the Web API and is branch-aware where the server edition supports it
(commercial editions); it also works against SonarQube Community Edition (single-branch).
A serverless local mode is available too — it runs the **BSL Language Server** directly
against the project's sources, with no server involved.

## Features

- **SonarQube Issues view** — a tree of issues for the active project, grouped by file or by
  rule, with toolbar filters for severity and type and a free-text search.
- **Jump to code** — double-click an issue to open the module at the reported line; a single
  click shows the rule description in the bottom pane.
- **Automatic git branch detection** — the view detects the project's current branch and
  queries issues for it (on commercial server editions).
- **Per-project binding** — each project is bound to a SonarQube project key, with an
  optional fixed branch and an optional repository path prefix.
- **Issues right in the code** — transient markers in the standard Problems view and
  underlines in the editor.
- **Run analysis from the view** — a toolbar action (local scanner or CI trigger).
- **Serverless local mode** — analysis by the BSL Language Server straight from the sources,
  with no SonarQube server and no Java install required.

## Requirements

- 1C:Enterprise Development Tools **2026.1** (the build target platform).
- Java 17 — the one EDT already runs on (no separate install needed).
- For server mode — a SonarQube server with an already-analyzed BSL project (for example,
  analyzed on CI with
  [sonar-bsl-plugin-community](https://github.com/1c-syntax/sonar-bsl-plugin-community)).
- For local mode — internet access once, on the first analysis, to auto-download the BSL
  Language Server native build (unless a local executable is configured).

## Installation

**From a p2 update-site archive** (recommended):

1. Download the `ru.jimmo.edt.sonarq.repository` update-site zip from the project's
   **Releases** page (or build it yourself — see [Building from source](#building-from-source)).
2. In EDT: `Help` → `Install New Software…` → `Add…` → `Archive…`, point it at the
   downloaded zip, select the **SonarQ in EDT** feature, and finish the wizard.
3. Restart EDT when prompted.

**From the command line** (p2 director) — for scripted / unattended installs:

```
<edt-install>/1cedtc.exe -nosplash -application org.eclipse.equinox.p2.director ^
  -repository file:/<path-to-extracted-repository> ^
  -installIU ru.jimmo.edt.sonarq.feature.feature.group ^
  -vm <path-to-jdk17>/bin/javaw.exe
```

## Opening the view

**Window** → **Show View** → **Other…** → **SonarQube** category → **SonarQube Issues**.

![SonarQube Issues view: the grouped issue tree, the toolbar filters and the rule description pane](docs/images/issues-view.png)
<!-- TODO: owner — add a screenshot of the SonarQube Issues view (tree + toolbar + rule pane). -->

## The Issues view

The toolbar sits at the top of the view, the issue tree below it, the rule description pane
under the tree, and a status line at the very bottom.

| Control | What it does |
|---|---|
| **Refresh** | Reload issues. In server mode — fetch them from the server; in local mode — re-run the local analysis. |
| **Run Branch Analysis** | In server mode — analyze the current state of the working copy the configured way (local scanner or CI trigger). Overwriting an already-analyzed branch pops up a confirmation dialog. In local mode this is the same as **Refresh**. |
| **Project** | Pick a workspace project when there is more than one. |
| **Severity** | Multi-select of severities: BLOCKER, CRITICAL, MAJOR, MINOR, INFO. |
| **Type** | Filter by type: BUG, VULNERABILITY, CODE_SMELL. |
| **Filter by rule or message** field | Free-text filter over the rule key and the message text. |
| **Group by File** / **Group by Rule** | Toggle the tree structure. |

Also in the view:

- The **status line** at the bottom shows the issue count, the branch, the last-refresh time,
  and, on failures, the error text (for example an authentication or network error).
- **Double-click** an issue to open the corresponding module at the reported line; a **single
  click** loads the rule description into the bottom pane.
- Issues whose **file is not found locally** are shown greyed out; a tooltip explains that
  navigation is unavailable.
- When the current git branch has not been analyzed on the server yet, a banner appears above
  the tree — **"Branch … has not been analyzed on the server yet"** — with a **Send branch
  for analysis** link (server mode only, on commercial SonarQube editions).

## Configuring server mode

### 1. Server connection

**Preferences** → **SonarQube**:

- **Mode** — choose **SonarQube server**.
- **Server URL** — the SonarQube server address.
- **Token** — a user token. To create one, open **My Account → Security → Generate Tokens**
  in the SonarQube web UI and create a **User Token**. For the analysis-launch button the
  token additionally needs the **Execute Analysis** permission. The token is stored in the
  **Eclipse Secure Storage**, not in plain preferences.
- **Timeout (seconds)** — the HTTP request timeout.
- **Test Connection** verifies the URL and token and reports the server version.

![The SonarQube preference page: mode, server URL, token, and the Analysis launch and Editor markers groups](docs/images/preferences.png)
<!-- TODO: owner — add a screenshot of the Preferences → SonarQube page. -->

### 2. Project binding

**Right-click the project** → **Properties** → **SonarQube**:

- **Project key** — the project key in SonarQube. The **Fill from Server** button looks it up
  by the EDT project name (a hint is shown in the field).
- **Fixed branch** — leave empty to detect the branch from git automatically.
- **Repository path prefix** — set it only when the EDT project lives in a sub-directory of
  the git repository that CI analyzes; otherwise leave it empty.

### 3. Loading issues

Open the **SonarQube Issues** view and press **Refresh**.

### 4. Analysis launch (optional)

The **Analysis launch** group on the preference page controls how the **Run Branch Analysis**
button works. **Launch mode**:

- **Local scanner (download automatically)** — the plugin downloads the sonar-scanner CLI
  itself.
- **Local scanner (specified path)** — use the scanner at the given **Scanner path**.
- **CI trigger (URL)** — a POST to a CI webhook. Configure the **CI trigger URL** with a
  `{branch}` placeholder (the current branch is substituted) and an optional **CI secret**
  (sent as an `Authorization` header, stored in the Secure Storage).

The **Extra scanner arguments** field appends arguments in both local-scanner modes.

Example GitLab pipeline trigger URL:

```
https://gitlab.example.com/api/v4/projects/<id>/trigger/pipeline?token=<trigger_token>&ref={branch}
```

> **Warning.** GitLab expects the trigger token as a URL query parameter, so it goes right
> into the URL template — and is therefore stored in **plain (non-encrypted)** preferences.
> Prefer a low-privilege trigger token.

### 5. Branches

On commercial SonarQube editions the view shows the branch matching the project's current git
branch. On SonarQube Community Edition branches are not supported — the main branch is shown,
and analysis always lands in the single default branch (a confirmation dialog warns before
overwriting an already-analyzed branch).

## Configuring local mode (no server)

**Preferences** → **SonarQube** → **Mode** → **Local analysis (BSL Language Server)**.

- **Source**:
  - **Download automatically (internet, ~170 MB once)** — the default; the plugin downloads
    the BSL Language Server native build into its own state area on the first analysis. No
    Java is required.
  - **Use a local executable** — set the **BSL Language Server executable** path yourself
    (**Browse…** and **Verify** buttons; **Verify** runs the file with `--version`).
- Every **Refresh** runs a fresh local analysis.
- Which checks run is configured separately — see [Choosing which checks
  run](#choosing-which-checks-run) below; every check bundled with the BSL Language Server is
  enabled by default.
- Branches, the analysis-launch button and the CI settings do not apply in this mode — the
  project is analyzed as a whole.

### Choosing which checks run

Which diagnostics local analysis reports is configured on a separate page: **Preferences** →
**SonarQube** → **BSL Checks** (the `BslChecksPreferencePage` class).

- Diagnostics are shown as a tree grouped by category (**Key** and **Name** columns): **EDT
  validator duplicates** — diagnostics that overlap a built-in EDT check (a row's tooltip names
  the duplicated EDT check id); **Needs tuning for this project** — metric/threshold diagnostics
  (complexity, sizes, magic numbers and the like); **Not a good fit for EDT** — opinionated or
  EDT-conflicting diagnostics; **Other** — everything else. Each category has its own group
  checkbox (toggles every diagnostic in that category at once, and shows a tri-state when the
  category is a mix of enabled and disabled) and its own **"Disabled N of M"** count. A checked
  diagnostic is enabled, an unchecked one is disabled. Every diagnostic is enabled by default.
- The **Apply Recommended Profile** button disables the recommended set in one click — EDT
  validator duplicates + needs-tuning + not-a-good-fit diagnostics (the **Other** category is
  left untouched). This is opt-in: nothing changes until the button is pressed, so the default
  stays every diagnostic enabled, exactly as before.
- The category mapping is a first cut bundled with the plugin, not an authoritative
  classification; the community is welcome to refine it via a Pull Request (see [issue
  #3](https://github.com/Jimmo910/edt-sonarq-plugin/issues/3)).
- The filter field searches by key or name; **Enable All** / **Disable All** flip every
  diagnostic regardless of the current filter or category. The **"Disabled N of M"** label below
  the tree tracks the total number currently disabled.
- The full catalog of 186 known diagnostics ships with the plugin, so the tree is grouped and
  populated from the start, even before a local analysis or a checks-list fetch has ever run.
  This bundled catalog is merged with (not replaced by) a cache that is filled in automatically
  after every successful local analysis, or on demand with the **Fetch Checks List** button,
  which analyzes an empty temporary folder to obtain the full list without a single real issue.
  If the BSL Language Server has not been downloaded yet, the first use of either the button or
  a local analysis downloads it — about 170 MB, and requires internet access.
- The selection made on this page — whether checked by hand or by **Apply Recommended
  Profile** — is applied and stored the same way as before: through a generated BSL Language
  Server configuration file, passed to the analysis via the `--configuration` flag. If the
  project already has its own `.bsl-language-server.json` (at the project root or under `src/`),
  that file always takes priority: analysis uses it instead of the generated one, and the page's
  selection is ignored for that project.

### Analysis scope

The project's **Properties → SonarQube** page (see [Project binding](#2-project-binding) above)
has an **Analysis scope (local mode)** group with two independent filters. Both apply only to
local analysis mode, and both default to empty — meaning "analyze everything", today's behavior,
unchanged.

- **Base branch** — an editable combo pre-filled with the repository's local branch names. When
  set, only issues on lines that differ from that base are shown (the base branch or commit
  compared against your working copy, including uncommitted edits) — the same idea as EDT's own
  "hide errors of the base branch". If the value can't be resolved (the project isn't inside a
  git repository, or the ref doesn't exist), the filter is skipped and every issue is shown —
  never silently hidden.
- **Subsystems** — a checkbox tree read from the project's `src/Subsystems` folder (nested
  subsystems included). Leave everything unchecked to analyze the whole project; check one or
  more subsystems to restrict analysis to them — checking a subsystem also includes everything
  nested under it (handled by the BSL Language Server itself, via its `subsystemsFilter`
  diagnostic parameter). The **Refresh** button re-reads the tree from disk (for example after
  adding a new subsystem) without discarding the current selection.

If the project ships its own `.bsl-language-server.json`, it takes priority over the generated
checks configuration, and — like the disabled-diagnostics selection — the subsystem filter is not
applied for that project either. The base-branch line filter is independent of the checks
configuration, though, and still applies even then.

## Editor markers

The **Editor markers** group on the preference page:

- **Show issues in editor** (on by default) — issues go into the standard Problems view and
  show up as underlines in the editor. Severity mapping: BLOCKER/CRITICAL → errors, MAJOR →
  warnings, everything else → info.
- **Refresh automatically in background** + **Interval (minutes)** — periodic background
  refresh of issues and markers even while the view is closed (server mode only).

Markers are **transient**: they are rebuilt on every refresh, and after an EDT restart the
Problems view is empty until the first refresh.

![SonarQube issues in the editor: underlines in a module and rows in the Problems view](docs/images/markers.png)
<!-- TODO: owner — add a screenshot of the markers (editor underlines + Problems view). -->

## How it works

**Server mode.** The plugin acts as a SonarQube Web API reader: it loads issues by project
key and branch (`/api/issues/search`, paginated), rule descriptions (`/api/rules/show`), and
detects the server edition to know whether branches are supported. On top of that, the **Run
Branch Analysis** button can launch an analysis itself — with a local scanner (auto-downloaded
or at a given path) or via a CI trigger — and wait for the server to process the report,
after which the view refreshes.

**Local mode.** The plugin runs the BSL Language Server native build in `--analyze` mode over
the project's sources, gets a **SARIF** report, and builds the same issue tree, filters and
markers from it. No SonarQube server and no Java are needed.

## Building from source

Requires **JDK 17** and **Maven 3.9.4 or newer** (enforced by the build; there is no Maven wrapper).

```powershell
$env:JAVA_HOME = '<path-to-jdk-17>'
mvn clean verify
```

The p2 repository is produced at
`repositories/ru.jimmo.edt.sonarq.repository/target/repository/` (plus a zip archive of the
same content).

## Known limitations

- The server returns **at most 10,000 issues** (a SonarQube Web API ceiling); if you hit it,
  narrow the filters on the server side.
- Branches are supported only on commercial SonarQube editions; Community Edition shows the
  single main branch.
- In local mode the project's `src/` folder is analyzed (the conventional 1C configuration
  layout), or the project root when there is no `src/`.

## Feedback

Questions, bugs and suggestions — via the **Issues** tab of the project's GitHub repository.

## License

Licensed under the [Eclipse Public License 2.0](LICENSE).
