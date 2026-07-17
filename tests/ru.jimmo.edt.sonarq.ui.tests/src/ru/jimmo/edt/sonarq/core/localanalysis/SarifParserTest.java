/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
        assertEquals("MethodSize:src/CommonModules/X/Module.bsl:42", first.key()); //$NON-NLS-1$

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
        assertEquals("MethodSize:src/Module.bsl:0", report.issues().get(0).key()); //$NON-NLS-1$
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
    public void missingLevelMapsToInfo()
    {
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
        assertEquals(SonarSeverity.INFO, report.issues().get(0).severity());
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
        assertEquals("MethodSize:src/CommonModules/X/Module.bsl:5", issue.key()); //$NON-NLS-1$
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
        assertEquals("MagicNumber:src/M.bsl:3", issue.key()); //$NON-NLS-1$
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
        assertEquals("MagicNumber:src/CommonModules/Calc/Module.bsl:6", issue.key()); //$NON-NLS-1$
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
        assertEquals("MethodSize::0", issue.key()); //$NON-NLS-1$
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
