/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the command line that runs a headless BSL Language Server analysis.
 *
 * <p>The command invokes the self-contained native launcher directly (see {@link BslServerInstaller});
 * it carries its own Java runtime, so no external JDK is involved. Flag names are those of the
 * {@code bsl-language-server} 1.0.4 launcher, verified against its live {@code --help} output. In 1.0.4
 * {@code analyze} is a picocli sub-command and {@code --analyze} is a registered alias for it, so it may
 * be passed as the first token followed by the sub-command options:
 *
 * <pre>
 * bsl-language-server analyze [-hq] [-c=&lt;path&gt;] [-o=&lt;path&gt;] [-s=&lt;path&gt;]
 *                             [-w=&lt;path&gt;] [-r=&lt;keys&gt;]...
 *   -c, --configuration &lt;path&gt;  Path to language server configuration file
 *   -s, --srcDir &lt;path&gt;         Source directory
 *   -o, --outputDir &lt;path&gt;      Output report directory
 *   -r, --reporter &lt;keys&gt;       Reporter key (console, generic, junit, json, sarif, tslint)
 * </pre>
 *
 * <p>The {@code --configuration}/{@code -c} flag was verified live on 2026-07-17 by running the
 * downloaded native launcher's {@code analyze --help} (task K2):
 *
 * <pre>
 * Usage: bsl-language-server analyze [-hq] [-c=&lt;path&gt;] [-o=&lt;path&gt;] [-s=&lt;path&gt;]
 *                                    [-w=&lt;path&gt;] [-r=&lt;keys&gt;]...
 * Run analysis and get diagnostic info
 *   -c, --configuration=&lt;path&gt;
 *                            Path to language server configuration file
 *   -h, --help               Show this help message and exit
 *   -o, --outputDir=&lt;path&gt;   Output report directory
 *   -q, --silent             Silent mode
 *   -r, --reporter=&lt;keys&gt;    Reporter key (code-quality, console, generic, junit,
 *                              json, sarif, tslint)
 *   -s, --srcDir=&lt;path&gt;      Source directory
 *   -w, --workspaceDir=&lt;path&gt;
 *                            Workspace directory
 * </pre>
 *
 * <p>Picocli accepts both the {@code --opt value} and {@code --opt=value} forms; this builder emits the
 * space-separated form. The {@code sarif} reporter writes a fixed file named {@code bsl-ls.sarif} into
 * the output directory (verified by a live run of the native launcher against a real 1C source tree).
 */
public final class BslAnalyzeCommand
{
    private static final String OPTION_ANALYZE = "--analyze"; //$NON-NLS-1$
    private static final String OPTION_SRC_DIR = "--srcDir"; //$NON-NLS-1$
    private static final String OPTION_REPORTER = "--reporter"; //$NON-NLS-1$
    private static final String OPTION_OUTPUT_DIR = "--outputDir"; //$NON-NLS-1$
    private static final String OPTION_CONFIGURATION = "--configuration"; //$NON-NLS-1$
    private static final String REPORTER_SARIF = "sarif"; //$NON-NLS-1$

    private BslAnalyzeCommand()
    {
    }

    /**
     * Builds the launcher invocation that analyzes {@code srcDir} and writes a SARIF report, without a
     * checks configuration file.
     *
     * <p>Delegates to {@link #build(Path, Path, Path, Path)} with a {@code null} configuration path.
     *
     * @param executable the BSL Language Server launcher, not {@code null}
     * @param srcDir the source directory to analyze, not {@code null}
     * @param outputDir the directory to write the SARIF report into, not {@code null}
     * @return the ordered command tokens, never {@code null}
     */
    public static List<String> build(Path executable, Path srcDir, Path outputDir)
    {
        return build(executable, srcDir, outputDir, null);
    }

    /**
     * Builds the launcher invocation that analyzes {@code srcDir} and writes a SARIF report, optionally
     * pointing the language server at a generated checks configuration file.
     *
     * @param executable the BSL Language Server launcher, not {@code null}
     * @param srcDir the source directory to analyze, not {@code null}
     * @param outputDir the directory to write the SARIF report into, not {@code null}
     * @param configPath the language server configuration file to pass via {@code --configuration}, or
     *     {@code null} to omit the flag and let the launcher use its own defaults
     * @return the ordered command tokens, never {@code null}
     */
    public static List<String> build(Path executable, Path srcDir, Path outputDir, Path configPath)
    {
        List<String> command = new ArrayList<>();
        command.add(String.valueOf(executable));
        command.add(OPTION_ANALYZE);
        command.add(OPTION_SRC_DIR);
        command.add(String.valueOf(srcDir));
        command.add(OPTION_REPORTER);
        command.add(REPORTER_SARIF);
        command.add(OPTION_OUTPUT_DIR);
        command.add(String.valueOf(outputDir));
        if (configPath != null)
        {
            command.add(OPTION_CONFIGURATION);
            command.add(String.valueOf(configPath));
        }
        return command;
    }
}
