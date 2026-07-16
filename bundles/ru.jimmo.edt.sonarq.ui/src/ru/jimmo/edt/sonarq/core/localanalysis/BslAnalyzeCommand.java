/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Assembles the command line that runs a headless BSL Language Server analysis.
 *
 * <p>Flag names are those of the {@code bsl-language-server} 1.0.4 executable jar, verified against its
 * live {@code --help} output. In 1.0.4 {@code analyze} is a picocli sub-command; {@code --analyze} is a
 * registered alias for it, so it may be passed as the first token followed by the sub-command options:
 *
 * <pre>
 * bsl-language-server analyze [-hq] [-o=&lt;path&gt;] [-s=&lt;path&gt;] [-w=&lt;path&gt;] [-r=&lt;keys&gt;]...
 *   -s, --srcDir &lt;path&gt;      Source directory
 *   -o, --outputDir &lt;path&gt;   Output report directory
 *   -r, --reporter &lt;keys&gt;    Reporter key (console, generic, junit, json, sarif, tslint)
 * </pre>
 *
 * <p>Picocli accepts both the {@code --opt value} and {@code --opt=value} forms; this builder emits the
 * space-separated form. The {@code sarif} reporter writes a fixed file named {@code bsl-ls.sarif} into
 * the output directory (verified by a live run against a real 1C source tree).
 */
public final class BslAnalyzeCommand
{
    private static final String OPTION_JAR = "-jar"; //$NON-NLS-1$
    private static final String OPTION_ANALYZE = "--analyze"; //$NON-NLS-1$
    private static final String OPTION_SRC_DIR = "--srcDir"; //$NON-NLS-1$
    private static final String OPTION_REPORTER = "--reporter"; //$NON-NLS-1$
    private static final String OPTION_OUTPUT_DIR = "--outputDir"; //$NON-NLS-1$
    private static final String REPORTER_SARIF = "sarif"; //$NON-NLS-1$
    private static final String BIN_DIR = "bin"; //$NON-NLS-1$
    private static final String JAVA_WINDOWS = "java.exe"; //$NON-NLS-1$
    private static final String JAVA_OTHER = "java"; //$NON-NLS-1$

    private BslAnalyzeCommand()
    {
    }

    /**
     * Builds the {@code java -jar} invocation that analyzes {@code srcDir} and writes a SARIF report.
     *
     * @param javaExecutable the Java launcher to run, not {@code null}
     * @param serverJar the BSL Language Server executable jar, not {@code null}
     * @param srcDir the source directory to analyze, not {@code null}
     * @param outputDir the directory to write the SARIF report into, not {@code null}
     * @return the ordered command tokens, never {@code null}
     */
    public static List<String> build(Path javaExecutable, Path serverJar, Path srcDir, Path outputDir)
    {
        List<String> command = new ArrayList<>();
        command.add(String.valueOf(javaExecutable));
        command.add(OPTION_JAR);
        command.add(String.valueOf(serverJar));
        command.add(OPTION_ANALYZE);
        command.add(OPTION_SRC_DIR);
        command.add(String.valueOf(srcDir));
        command.add(OPTION_REPORTER);
        command.add(REPORTER_SARIF);
        command.add(OPTION_OUTPUT_DIR);
        command.add(String.valueOf(outputDir));
        return command;
    }

    /**
     * Returns the Java launcher of the running JVM, honoring the host operating system.
     *
     * @return the path to {@code java}/{@code java.exe} under {@code java.home/bin}, never {@code null}
     */
    public static Path javaExecutable()
    {
        String javaHome = System.getProperty("java.home"); //$NON-NLS-1$
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$
        String launcher = os.contains("win") ? JAVA_WINDOWS : JAVA_OTHER; //$NON-NLS-1$
        return Path.of(javaHome, BIN_DIR, launcher);
    }
}
