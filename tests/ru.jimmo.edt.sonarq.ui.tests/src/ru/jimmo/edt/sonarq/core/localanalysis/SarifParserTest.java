/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.Reader;
import java.util.Iterator;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.core.model.SonarIssueType;
import ru.jimmo.edt.sonarq.core.model.SonarRule;
import ru.jimmo.edt.sonarq.core.model.SonarSeverity;

/** Tests for {@link SarifParser}. */
public class SarifParserTest
{
    private static final String PROJECT_KEY = "TestConfiguration"; //$NON-NLS-1$

    private static final String FULL_REPORT_JSON = """
        {
          "runs": [
            {
              "tool": {
                "driver": {
                  "rules": [
                    {
                      "id": "MethodSize",
                      "name": "Method size",
                      "fullDescription": { "text": "Methods should not be too long." },
                      "helpUri": "https://example.org/rules/MethodSize"
                    },
                    {
                      "id": "Typo",
                      "name": "Typo"
                    }
                  ]
                }
              },
              "results": [
                {
                  "ruleId": "MethodSize",
                  "level": "warning",
                  "message": { "text": "Too long" },
                  "locations": [
                    {
                      "physicalLocation": {
                        "artifactLocation": { "uri": "src/CommonModules/X/Module.bsl" },
                        "region": { "startLine": 42 }
                      }
                    }
                  ]
                },
                {
                  "ruleId": "Typo",
                  "level": "note",
                  "message": { "text": "Fix spelling" },
                  "locations": [
                    {
                      "physicalLocation": {
                        "artifactLocation": { "uri": "src/Catalogs/Items/ObjectModule.bsl" },
                        "region": { "startLine": 7 }
                      }
                    }
                  ]
                }
              ]
            }
          ]
        }""";

    @Test
    public void parsesFullReportWithTwoResultsAndTwoRules()
    {
        SarifReport report = SarifParser.parse(FULL_REPORT_JSON, PROJECT_KEY);

        assertEquals(2, report.issues().size());
        assertEquals(2, report.rules().size());

        SonarIssue first = report.issues().get(0);
        assertEquals("MethodSize", first.ruleKey()); //$NON-NLS-1$
        assertEquals(SonarSeverity.MAJOR, first.severity());
        assertEquals(SonarIssueType.CODE_SMELL, first.type());
        assertEquals("Too long", first.message()); //$NON-NLS-1$
        assertEquals(42, first.line());
        assertEquals("TestConfiguration:src/CommonModules/X/Module.bsl", first.componentKey()); //$NON-NLS-1$
        assertEquals("MethodSize:src/CommonModules/X/Module.bsl:42:0", first.key()); //$NON-NLS-1$

        SonarIssue second = report.issues().get(1);
        assertEquals(SonarSeverity.MINOR, second.severity());
        assertEquals(7, second.line());

        SonarRule methodSizeRule = report.rules().get("MethodSize"); //$NON-NLS-1$
        assertEquals("Method size", methodSizeRule.name()); //$NON-NLS-1$
        assertTrue(methodSizeRule.htmlDescription().contains("Methods should not be too long.")); //$NON-NLS-1$

        SonarRule typoRule = report.rules().get("Typo"); //$NON-NLS-1$
        assertEquals("Typo", typoRule.name()); //$NON-NLS-1$
        assertEquals("", typoRule.htmlDescription()); //$NON-NLS-1$
    }

    @Test
    public void resultWithoutRegionParsesLineAsZero()
    {
        String json = """
            {
              "runs": [
                {
                  "results": [
                    {
                      "ruleId": "MethodSize",
                      "level": "error",
                      "message": { "text": "No region" },
                      "locations": [
                        {
                          "physicalLocation": {
                            "artifactLocation": { "uri": "src/Module.bsl" }
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }""";
        SarifReport report = SarifParser.parse(json, PROJECT_KEY);
        assertEquals(0, report.issues().get(0).line());
        assertEquals("MethodSize:src/Module.bsl:0:0", report.issues().get(0).key()); //$NON-NLS-1$
    }

    /**
     * A local-analysis key is synthesized, and one line routinely carries several findings of the same rule
     * (two operators missing a space, say). While the key was only rule, file and line, those findings
     * shared one key - and both the Problems-view quick fix (which hands its marker's key to the issue view)
     * and the suppression bookkeeping treat a key as an identity.
     */
    @Test
    public void twoFindingsOfOneRuleOnOneLineGetDistinctKeys()
    {
        String json = twoResultsOnLine10("""
            "region": { "startLine": 10, "startColumn": 5 }""", """
            "region": { "startLine": 10, "startColumn": 20 }""");

        SarifReport report = SarifParser.parse(json, PROJECT_KEY);

        assertEquals(2, report.issues().size());
        SonarIssue first = report.issues().get(0);
        SonarIssue second = report.issues().get(1);
        assertEquals(10, first.line());
        assertEquals(10, second.line());
        assertEquals("MissingSpace:src/Module.bsl:10:5", first.key()); //$NON-NLS-1$
        assertEquals("MissingSpace:src/Module.bsl:10:20", second.key()); //$NON-NLS-1$
    }

    /** Even a report that repeats a position outright must not hand out one key twice. */
    @Test
    public void findingsAtTheVerySamePositionStillGetDistinctKeys()
    {
        String json = twoResultsOnLine10("""
            "region": { "startLine": 10 }""", """
            "region": { "startLine": 10 }""");

        SarifReport report = SarifParser.parse(json, PROJECT_KEY);

        assertEquals(2, report.issues().size());
        assertEquals("MissingSpace:src/Module.bsl:10:0", report.issues().get(0).key()); //$NON-NLS-1$
        assertEquals("MissingSpace:src/Module.bsl:10:0#2", report.issues().get(1).key()); //$NON-NLS-1$
    }

    /**
     * Builds a report of two {@code MissingSpace} results in one module, differing only in their region.
     *
     * @param firstRegion the first result's {@code region} member
     * @param secondRegion the second result's {@code region} member
     * @return the SARIF report as JSON
     */
    private static String twoResultsOnLine10(String firstRegion, String secondRegion)
    {
        String result = """
            {
              "ruleId": "MissingSpace",
              "level": "note",
              "message": { "text": "Missing space" },
              "locations": [
                {
                  "physicalLocation": {
                    "artifactLocation": { "uri": "src/Module.bsl" },
                    %s
                  }
                }
              ]
            }""";
        return "{ \"runs\": [ { \"results\": [ " //$NON-NLS-1$
            + String.format(result, firstRegion) + ", " //$NON-NLS-1$
            + String.format(result, secondRegion) + " ] } ] }"; //$NON-NLS-1$
    }

    @Test
    public void missingRunsYieldsEmptyReport()
    {
        SarifReport report = SarifParser.parse("{}", PROJECT_KEY);
        assertTrue(report.issues().isEmpty());
        assertTrue(report.rules().isEmpty());
    }

    @Test
    public void emptyRunsArrayYieldsEmptyReport()
    {
        SarifReport report = SarifParser.parse("{ \"runs\": [] }", PROJECT_KEY);
        assertTrue(report.issues().isEmpty());
        assertTrue(report.rules().isEmpty());
    }

    @Test
    public void levelErrorMapsToCritical()
    {
        assertEquals(SonarSeverity.CRITICAL, parseSingleResultSeverity("error")); //$NON-NLS-1$
    }

    @Test
    public void levelWarningMapsToMajor()
    {
        assertEquals(SonarSeverity.MAJOR, parseSingleResultSeverity("warning")); //$NON-NLS-1$
    }

    @Test
    public void levelNoteMapsToMinor()
    {
        assertEquals(SonarSeverity.MINOR, parseSingleResultSeverity("note")); //$NON-NLS-1$
    }

    @Test
    public void unknownLevelMapsToInfo()
    {
        assertEquals(SonarSeverity.INFO, parseSingleResultSeverity("whatever")); //$NON-NLS-1$
    }

    @Test
    public void missingLevelMapsToMajorLikeAWarning()
    {
        // SARIF 2.1.0 defines "warning" as the default value of result.level, so an omitted level is a
        // warning (MAJOR), not the lowest severity there is.
        String json = """
            {
              "runs": [
                {
                  "results": [
                    {
                      "ruleId": "MethodSize",
                      "message": { "text": "No level" },
                      "locations": [
                        {
                          "physicalLocation": {
                            "artifactLocation": { "uri": "src/Module.bsl" },
                            "region": { "startLine": 1 }
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }""";
        SarifReport report = SarifParser.parse(json, PROJECT_KEY);
        assertEquals(SonarSeverity.MAJOR, report.issues().get(0).severity());
    }

    @Test
    public void bslDiagnosticTypesMapOntoSonarIssueTypes()
    {
        assertEquals(SonarIssueType.BUG, SarifParser.issueTypeOf("Error")); //$NON-NLS-1$
        assertEquals(SonarIssueType.VULNERABILITY, SarifParser.issueTypeOf("Vulnerability")); //$NON-NLS-1$
        assertEquals(SonarIssueType.VULNERABILITY, SarifParser.issueTypeOf("Security Hotspot")); //$NON-NLS-1$
        assertEquals(SonarIssueType.CODE_SMELL, SarifParser.issueTypeOf("Code smell")); //$NON-NLS-1$
    }

    @Test
    public void unknownOrMissingBslDiagnosticTypeFallsBackToCodeSmell()
    {
        assertEquals(SonarIssueType.CODE_SMELL, SarifParser.issueTypeOf("")); //$NON-NLS-1$
        assertEquals(SonarIssueType.CODE_SMELL, SarifParser.issueTypeOf(null));
        assertEquals(SonarIssueType.CODE_SMELL, SarifParser.issueTypeOf("Something new")); //$NON-NLS-1$
    }

    @Test
    public void resultTypeComesFromTheBundledDiagnosticCatalog()
    {
        // The three rule keys below are bundled in resources/bsl-diagnostic-categories.json with the BSL
        // Language Server types Error, Vulnerability and Code smell respectively; NoSuchDiagnosticEver is
        // not in the catalog at all and must fall back to CODE_SMELL.
        SarifReport report = SarifParser.parse(typedResultsJson(), PROJECT_KEY);
        assertEquals(SonarIssueType.BUG, report.issues().get(0).type());
        assertEquals(SonarIssueType.VULNERABILITY, report.issues().get(1).type());
        assertEquals(SonarIssueType.CODE_SMELL, report.issues().get(2).type());
        assertEquals(SonarIssueType.CODE_SMELL, report.issues().get(3).type());
    }

    private static String typedResultsJson()
    {
        String result = """
            {
              "ruleId": "%s",
              "level": "warning",
              "message": { "text": "m" },
              "locations": [
                {
                  "physicalLocation": {
                    "artifactLocation": { "uri": "src/Module.bsl" },
                    "region": { "startLine": 1 }
                  }
                }
              ]
            }""";
        return "{ \"runs\": [ { \"results\": [" //$NON-NLS-1$
            + result.formatted("UsingHardcodePath") + ',' //$NON-NLS-1$
            + result.formatted("UsingHardcodeSecretInformation") + ',' //$NON-NLS-1$
            + result.formatted("MethodSize") + ',' //$NON-NLS-1$
            + result.formatted("NoSuchDiagnosticEver") //$NON-NLS-1$
            + "] } ] }"; //$NON-NLS-1$
    }

    @Test
    public void uriWithDotSlashPrefixAndBackslashesIsNormalized()
    {
        String json = """
            {
              "runs": [
                {
                  "results": [
                    {
                      "ruleId": "MethodSize",
                      "level": "error",
                      "message": { "text": "Windows-style path" },
                      "locations": [
                        {
                          "physicalLocation": {
                            "artifactLocation": { "uri": ".\\\\src\\\\CommonModules\\\\X\\\\Module.bsl" },
                            "region": { "startLine": 5 }
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }""";
        SarifReport report = SarifParser.parse(json, PROJECT_KEY);
        SonarIssue issue = report.issues().get(0);
        assertEquals("TestConfiguration:src/CommonModules/X/Module.bsl", issue.componentKey()); //$NON-NLS-1$
        assertEquals("MethodSize:src/CommonModules/X/Module.bsl:5:0", issue.key()); //$NON-NLS-1$
    }

    @Test
    public void fileSchemeUriPrefixIsStripped()
    {
        String json = """
            {
              "runs": [
                {
                  "results": [
                    {
                      "ruleId": "MethodSize",
                      "level": "error",
                      "message": { "text": "file scheme" },
                      "locations": [
                        {
                          "physicalLocation": {
                            "artifactLocation": { "uri": "file:///src/CommonModules/X/Module.bsl" },
                            "region": { "startLine": 9 }
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }""";
        SarifReport report = SarifParser.parse(json, PROJECT_KEY);
        // file:///src/... is a POSIX-absolute path: the scheme is stripped but the root slash survives.
        assertEquals("TestConfiguration:/src/CommonModules/X/Module.bsl", //$NON-NLS-1$
            report.issues().get(0).componentKey());
    }

    @Test
    public void percentEncodedPathIsDecodedBeforeMapping()
    {
        String json = """
            {
              "runs": [
                {
                  "results": [
                    {
                      "ruleId": "R1",
                      "level": "warning",
                      "message": { "text": "m" },
                      "locations": [
                        {
                          "physicalLocation": {
                            "artifactLocation": { "uri": "file:///E:/My%20Proj/src/M.bsl" },
                            "region": { "startLine": 3 }
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }""";
        SonarIssue issue = SarifParser.parse(json, PROJECT_KEY, "E:\\My Proj").issues().get(0); //$NON-NLS-1$
        assertEquals("TestConfiguration:src/M.bsl", issue.componentKey()); //$NON-NLS-1$
    }

    @Test
    public void plusSignInPathIsPreservedNotDecodedToSpace()
    {
        String json = """
            {
              "runs": [
                {
                  "tool": { "driver": { "rules": [] } },
                  "results": [
                    {
                      "ruleId": "R1",
                      "level": "warning",
                      "message": { "text": "m" },
                      "locations": [
                        {
                          "physicalLocation": {
                            "artifactLocation": { "uri": "file:///E:/a+b/src/M.bsl" },
                            "region": { "startLine": 1 }
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }""";
        SarifReport report = SarifParser.parse(json, PROJECT_KEY, "E:\\a+b"); //$NON-NLS-1$
        assertEquals("TestConfiguration:src/M.bsl", report.issues().get(0).componentKey()); //$NON-NLS-1$
    }

    @Test
    public void basePrefixIsNotStrippedAcrossASegmentBoundary()
    {
        String json = """
            {
              "runs": [
                {
                  "tool": { "driver": { "rules": [] } },
                  "results": [
                    {
                      "ruleId": "R1",
                      "level": "warning",
                      "message": { "text": "m" },
                      "locations": [
                        {
                          "physicalLocation": {
                            "artifactLocation": { "uri": "file:///work/project-old/src/M.bsl" },
                            "region": { "startLine": 1 }
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }""";
        SarifReport report = SarifParser.parse(json, PROJECT_KEY, "/work/project"); //$NON-NLS-1$
        assertTrue(report.issues().get(0).componentKey().contains("project-old")); //$NON-NLS-1$
    }

    @Test
    public void uncFileUriKeepsAuthorityAndRelativizes()
    {
        String json = """
            {
              "runs": [
                {
                  "results": [
                    {
                      "ruleId": "R1",
                      "level": "warning",
                      "message": { "text": "m" },
                      "locations": [
                        {
                          "physicalLocation": {
                            "artifactLocation": { "uri": "file://server/share/proj/src/M.bsl" },
                            "region": { "startLine": 1 }
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }""";
        SarifReport report = SarifParser.parse(json, PROJECT_KEY, "\\\\server\\share\\proj"); //$NON-NLS-1$
        assertEquals("TestConfiguration:src/M.bsl", report.issues().get(0).componentKey()); //$NON-NLS-1$
    }

    @Test
    public void posixAbsoluteFileUriIsRelativizedAgainstBasePrefix()
    {
        // Linux BSL Language Server output: an absolute POSIX file:/// location keeps its root slash so the
        // base prefix strips cleanly instead of eating the leading slash of /home/... .
        String json = """
            {
              "runs": [
                {
                  "results": [
                    {
                      "ruleId": "MagicNumber",
                      "level": "note",
                      "message": { "text": "Magic number" },
                      "locations": [
                        {
                          "physicalLocation": {
                            "artifactLocation": {
                              "uri": "file:///home/user/proj/src/M.bsl"
                            },
                            "region": { "startLine": 3 }
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }""";
        SarifReport report = SarifParser.parse(json, PROJECT_KEY, "/home/user/proj"); //$NON-NLS-1$
        SonarIssue issue = report.issues().get(0);
        assertEquals("TestConfiguration:src/M.bsl", issue.componentKey()); //$NON-NLS-1$
        assertEquals("MagicNumber:src/M.bsl:3:0", issue.key()); //$NON-NLS-1$
    }

    @Test
    public void absoluteFileUriIsRelativizedAgainstBasePrefix()
    {
        // Real BSL Language Server 1.0.4 output: absolute file:/// artifact locations.
        String json = """
            {
              "runs": [
                {
                  "results": [
                    {
                      "ruleId": "MagicNumber",
                      "level": "note",
                      "message": { "text": "Magic number" },
                      "locations": [
                        {
                          "physicalLocation": {
                            "artifactLocation": {
                              "uri": "file:///E:/proj/TestConfiguration/src/CommonModules/Calc/Module.bsl"
                            },
                            "region": { "startLine": 6 }
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }""";
        SarifReport report = SarifParser.parse(json, PROJECT_KEY, "E:/proj/TestConfiguration"); //$NON-NLS-1$
        SonarIssue issue = report.issues().get(0);
        assertEquals("TestConfiguration:src/CommonModules/Calc/Module.bsl", issue.componentKey()); //$NON-NLS-1$
        assertEquals("MagicNumber:src/CommonModules/Calc/Module.bsl:6:0", issue.key()); //$NON-NLS-1$
    }

    @Test
    public void basePrefixWithBackslashesAndDriveLetterCaseIsHandled()
    {
        String json = """
            {
              "runs": [
                {
                  "results": [
                    {
                      "ruleId": "MagicNumber",
                      "level": "note",
                      "message": { "text": "Magic number" },
                      "locations": [
                        {
                          "physicalLocation": {
                            "artifactLocation": {
                              "uri": "file:///E:/proj/TestConfiguration/src/CommonModules/Calc/Module.bsl"
                            },
                            "region": { "startLine": 6 }
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }""";
        // Windows-style base with back-slashes and a lower-case drive letter must still match.
        SarifReport report = SarifParser.parse(json, PROJECT_KEY, "e:\\proj\\TestConfiguration\\"); //$NON-NLS-1$
        assertEquals("TestConfiguration:src/CommonModules/Calc/Module.bsl", //$NON-NLS-1$
            report.issues().get(0).componentKey());
    }

    @Test
    public void nonMatchingBasePrefixLeavesUriIntact()
    {
        String json = """
            {
              "runs": [
                {
                  "results": [
                    {
                      "ruleId": "MagicNumber",
                      "level": "note",
                      "message": { "text": "Magic number" },
                      "locations": [
                        {
                          "physicalLocation": {
                            "artifactLocation": { "uri": "src/CommonModules/X/Module.bsl" },
                            "region": { "startLine": 1 }
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }""";
        // A base prefix that does not match must not corrupt an already-relative uri.
        SarifReport report = SarifParser.parse(json, PROJECT_KEY, "E:/other/project"); //$NON-NLS-1$
        assertEquals("TestConfiguration:src/CommonModules/X/Module.bsl", //$NON-NLS-1$
            report.issues().get(0).componentKey());
    }

    @Test
    public void helpUriIsAppendedAsDocumentationLinkInRuleHtml()
    {
        SarifReport report = SarifParser.parse(FULL_REPORT_JSON, PROJECT_KEY);
        SonarRule rule = report.rules().get("MethodSize"); //$NON-NLS-1$
        assertTrue(rule.htmlDescription()
            .contains("<p><a href=\"https://example.org/rules/MethodSize\">Documentation</a></p>")); //$NON-NLS-1$
    }

    @Test
    public void unsafeHelpUriSchemeIsNotRenderedAsLink()
    {
        String json = """
            {
              "runs": [
                {
                  "tool": { "driver": { "rules": [
                    { "id": "R1", "name": "Rule 1", "fullDescription": { "text": "Body" },
                      "helpUri": "javascript:alert(1)" }
                  ] } },
                  "results": []
                }
              ]
            }""";
        SonarRule rule = SarifParser.parse(json, PROJECT_KEY).rules().get("R1"); //$NON-NLS-1$
        assertFalse(rule.htmlDescription().contains("<a ")); //$NON-NLS-1$
        assertFalse(rule.htmlDescription().contains("javascript:")); //$NON-NLS-1$
    }

    @Test
    public void ruleWithoutNameFallsBackToId()
    {
        String json = """
            {
              "runs": [
                {
                  "tool": {
                    "driver": {
                      "rules": [
                        { "id": "NoName" }
                      ]
                    }
                  }
                }
              ]
            }""";
        SarifReport report = SarifParser.parse(json, PROJECT_KEY);
        assertEquals("NoName", report.rules().get("NoName").name()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void ruleDescriptionFallsBackToMarkdownWhenTextMissing()
    {
        String json = """
            {
              "runs": [
                {
                  "tool": {
                    "driver": {
                      "rules": [
                        {
                          "id": "MdOnly",
                          "fullDescription": { "markdown": "**bold** description" }
                        }
                      ]
                    }
                  }
                }
              ]
            }""";
        SarifReport report = SarifParser.parse(json, PROJECT_KEY);
        // The markdown fallback is rendered to HTML the same way as "text": **bold** becomes <b>bold</b>,
        // wrapped in a single paragraph.
        assertEquals("<p><b>bold</b> description</p>", //$NON-NLS-1$
            report.rules().get("MdOnly").htmlDescription()); //$NON-NLS-1$
    }

    @Test
    public void metadataTableAndCommentsAreStrippedFromRuleHtmlDescription()
    {
        String rawMarkdown = "# Название (EventHandlerInvalidSignature)\n" //$NON-NLS-1$
            + "\n" //$NON-NLS-1$
            + "| Тип | Поддерживаются<br>языки | Важность | Теги |\n" //$NON-NLS-1$
            + "|:---:|:---:|:---:|:---:|\n" //$NON-NLS-1$
            + "| `Ошибка` | `BSL`<br>`OS` | `Важный` | `suspicious`<br>`standard` |\n" //$NON-NLS-1$
            + "\n" //$NON-NLS-1$
            + "<!-- Блоки выше заполняются автоматически, не трогать -->\n" //$NON-NLS-1$
            + "## Описание диагностики\n" //$NON-NLS-1$
            + "<!-- заполняется вручную -->\n" //$NON-NLS-1$
            + "\n" //$NON-NLS-1$
            + "Реальный текст описания."; //$NON-NLS-1$

        JsonObject fullDescription = new JsonObject();
        fullDescription.addProperty("text", rawMarkdown); //$NON-NLS-1$
        JsonObject rule = new JsonObject();
        rule.addProperty("id", "EventHandlerInvalidSignature"); //$NON-NLS-1$ //$NON-NLS-2$
        rule.add("fullDescription", fullDescription); //$NON-NLS-1$
        JsonArray rules = new JsonArray();
        rules.add(rule);
        JsonObject driver = new JsonObject();
        driver.add("rules", rules); //$NON-NLS-1$
        JsonObject tool = new JsonObject();
        tool.add("driver", driver); //$NON-NLS-1$
        JsonObject run = new JsonObject();
        run.add("tool", tool); //$NON-NLS-1$
        JsonArray runs = new JsonArray();
        runs.add(run);
        JsonObject root = new JsonObject();
        root.add("runs", runs); //$NON-NLS-1$

        SarifReport report = SarifParser.parse(root.toString(), PROJECT_KEY);
        String html = report.rules().get("EventHandlerInvalidSignature").htmlDescription(); //$NON-NLS-1$

        assertFalse(html.contains("<!--")); //$NON-NLS-1$
        assertFalse(html.contains("Блоки выше")); //$NON-NLS-1$
        assertFalse(html.contains("Важность")); //$NON-NLS-1$
        assertFalse(html.contains("&lt;br&gt;")); //$NON-NLS-1$
        assertTrue(html.contains("Реальный текст описания.")); //$NON-NLS-1$
    }

    @Test
    public void resultWithoutLocationsParsesUriAndLineAsEmptyAndZero()
    {
        String json = """
            {
              "runs": [
                {
                  "results": [
                    {
                      "ruleId": "MethodSize",
                      "level": "error",
                      "message": { "text": "No locations" }
                    }
                  ]
                }
              ]
            }""";
        SarifReport report = SarifParser.parse(json, PROJECT_KEY);
        SonarIssue issue = report.issues().get(0);
        assertEquals(0, issue.line());
        assertEquals("TestConfiguration:", issue.componentKey()); //$NON-NLS-1$
        assertEquals("MethodSize::0:0", issue.key()); //$NON-NLS-1$
    }

    /**
     * The parser must consume the report as a stream, not as a document. This report is produced lazily,
     * chunk by chunk, by a {@link Reader} the test controls: it is never a {@code String} and never a JSON
     * tree, so it can only be parsed at all by a pull parser. Fifty thousand results are generated - far
     * more than the hundred kept - and every one of them is still counted, so the caller can report an
     * honest total.
     */
    @Test
    public void hugeResultsArrayIsStreamedOffAReaderAndCappedAtTheIssueLimit() throws IOException
    {
        int generated = 50_000;
        int limit = 100;

        SarifReport report;
        try (Reader reader = new ChunkedReader(sarifChunks(generated)))
        {
            report = SarifParser.parse(reader, PROJECT_KEY, "", limit); //$NON-NLS-1$
        }

        assertEquals(limit, report.issues().size());
        assertEquals(generated, report.totalResults());
        assertTrue(report.truncated());
        // The kept issues are the first ones, in report order, fully parsed - not placeholders.
        assertEquals(1, report.issues().get(0).line());
        assertEquals(limit, report.issues().get(limit - 1).line());
        assertEquals("MethodSize", report.issues().get(0).ruleKey()); //$NON-NLS-1$
        // The rule catalog still comes out of the same single pass.
        assertNotNull(report.rules().get("MethodSize")); //$NON-NLS-1$
    }

    @Test
    public void reportWithinTheIssueLimitIsNotReportedAsTruncated() throws IOException
    {
        SarifReport report;
        try (Reader reader = new ChunkedReader(sarifChunks(5)))
        {
            report = SarifParser.parse(reader, PROJECT_KEY, "", 100); //$NON-NLS-1$
        }

        assertEquals(5, report.issues().size());
        assertEquals(5, report.totalResults());
        assertFalse(report.truncated());
    }

    @Test
    public void unlimitedStringParseKeepsEveryResultAndReportsNoTruncation()
    {
        SarifReport report = SarifParser.parse(FULL_REPORT_JSON, PROJECT_KEY);

        assertEquals(2, report.totalResults());
        assertFalse(report.truncated());
    }

    /**
     * SARIF puts {@code tool} before {@code results} in practice, but the streaming parser must not depend
     * on that: a run whose rule catalog trails its results still yields both.
     */
    @Test
    public void ruleCatalogIsCollectedEvenWhenItFollowsTheResults()
    {
        String json = """
            {
              "runs": [
                {
                  "results": [
                    {
                      "ruleId": "MethodSize",
                      "level": "warning",
                      "message": { "text": "Too long" },
                      "locations": [
                        {
                          "physicalLocation": {
                            "artifactLocation": { "uri": "src/Module.bsl" },
                            "region": { "startLine": 3 }
                          }
                        }
                      ]
                    }
                  ],
                  "tool": {
                    "driver": {
                      "rules": [
                        { "id": "MethodSize", "name": "Method size" }
                      ]
                    }
                  }
                }
              ]
            }""";

        SarifReport report = SarifParser.parse(json, PROJECT_KEY);

        assertEquals(1, report.issues().size());
        assertEquals("Method size", report.rules().get("MethodSize").name()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A report cut off mid-document (a killed analyzer, a full disk) must fail cleanly, not as an Error. */
    @Test
    public void truncatedReportFailsAsAJsonSyntaxException()
    {
        String json = "{ \"runs\": [ { \"results\": [ { \"ruleId\": \"MethodSize\""; //$NON-NLS-1$

        try
        {
            SarifParser.parse(json, PROJECT_KEY);
            fail("expected a JsonSyntaxException"); //$NON-NLS-1$
        }
        catch (JsonSyntaxException e)
        {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void corruptReportFailsAsAJsonSyntaxException()
    {
        try
        {
            SarifParser.parse("{ \"runs\": [ not json at all ] }", PROJECT_KEY); //$NON-NLS-1$
            fail("expected a JsonSyntaxException"); //$NON-NLS-1$
        }
        catch (JsonSyntaxException e)
        {
            assertNotNull(e.getMessage());
        }
    }

    /** A root that is not a JSON object is corrupt input, not an internal {@code IllegalStateException}. */
    @Test
    public void nonObjectRootFailsAsAJsonSyntaxException()
    {
        try
        {
            SarifParser.parse("[]", PROJECT_KEY); //$NON-NLS-1$
            fail("expected a JsonSyntaxException"); //$NON-NLS-1$
        }
        catch (JsonSyntaxException e)
        {
            assertNotNull(e.getMessage());
        }
    }

    /**
     * Produces one SARIF document as a lazy sequence of chunks: a prefix carrying the rule catalog, one
     * chunk per result, then the closing brackets. Nothing ever holds the whole document.
     *
     * @param resultCount how many results to generate
     * @return the chunk sequence, never {@code null}
     */
    private static Iterator<String> sarifChunks(int resultCount)
    {
        String prefix = "{\"runs\":[{\"tool\":{\"driver\":{\"rules\":[" //$NON-NLS-1$
            + "{\"id\":\"MethodSize\",\"name\":\"Method size\"}]}},\"results\":["; //$NON-NLS-1$
        Stream<String> results = IntStream.rangeClosed(1, resultCount)
            .mapToObj(line -> (line == 1 ? "" : ",") + generatedResult(line)); //$NON-NLS-1$ //$NON-NLS-2$
        return Stream.concat(Stream.of(prefix), Stream.concat(results, Stream.of("]}]}"))).iterator(); //$NON-NLS-1$
    }

    private static String generatedResult(int line)
    {
        return "{\"ruleId\":\"MethodSize\",\"level\":\"warning\",\"message\":{\"text\":\"Too long\"}," //$NON-NLS-1$
            + "\"locations\":[{\"physicalLocation\":{\"artifactLocation\":{\"uri\":\"src/M.bsl\"}," //$NON-NLS-1$
            + "\"region\":{\"startLine\":" + line + ",\"startColumn\":1}}}]}"; //$NON-NLS-1$
    }

    /** Serves a lazily produced sequence of chunks as a {@link Reader}, never materializing the whole. */
    private static final class ChunkedReader extends Reader
    {
        private final Iterator<String> chunks;

        private String current = ""; //$NON-NLS-1$

        private int position;

        ChunkedReader(Iterator<String> chunks)
        {
            this.chunks = chunks;
        }

        @Override
        public int read(char[] buffer, int offset, int length)
        {
            while (position >= current.length())
            {
                if (!chunks.hasNext())
                {
                    return -1;
                }
                current = chunks.next();
                position = 0;
            }
            int count = Math.min(length, current.length() - position);
            current.getChars(position, position + count, buffer, offset);
            position += count;
            return count;
        }

        @Override
        public void close()
        {
            // Nothing to release; the chunk sequence is pure computation.
        }
    }

    private static SonarSeverity parseSingleResultSeverity(String level)
    {
        String json = """
            {
              "runs": [
                {
                  "results": [
                    {
                      "ruleId": "MethodSize",
                      "level": "%s",
                      "message": { "text": "m" },
                      "locations": [
                        {
                          "physicalLocation": {
                            "artifactLocation": { "uri": "src/Module.bsl" },
                            "region": { "startLine": 1 }
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }""".formatted(level);
        return SarifParser.parse(json, PROJECT_KEY).issues().get(0).severity();
    }
}
