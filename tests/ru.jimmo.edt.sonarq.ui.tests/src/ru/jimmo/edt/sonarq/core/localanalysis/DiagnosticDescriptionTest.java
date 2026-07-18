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

/** Tests for {@link DiagnosticDescription}. */
public class DiagnosticDescriptionTest
{
    @Test
    public void nullMarkdownReturnsEmpty()
    {
        assertEquals("", DiagnosticDescription.cleanMarkdown(null));
    }

    @Test
    public void blankMarkdownReturnsEmpty()
    {
        assertEquals("", DiagnosticDescription.cleanMarkdown("   \n  "));
    }

    @Test
    public void htmlCommentsAreRemoved()
    {
        String raw = """
            # Title

            <!-- auto-generated, do not touch -->
            Body text.""";

        String cleaned = DiagnosticDescription.cleanMarkdown(raw);

        assertFalse(cleaned.contains("<!--"));
        assertFalse(cleaned.contains("-->"));
        assertFalse(cleaned.contains("auto-generated"));
        assertTrue(cleaned.contains("Body text."));
    }

    @Test
    public void metadataTableIsDroppedButHeadingAndBodyAreKept()
    {
        String raw = """
            # Название (EventHandlerInvalidSignature)

            |   Тип    |  Поддерживаются<br>языки  | Важность | Теги |
            |:--------:|:-------------------------:|:--------:|:----:|
            | `Ошибка` |     `BSL`<br>`OS`         | `Важный` | `suspicious`<br>`standard` |

            <!-- Блоки выше заполняются автоматически, не трогать -->
            ## Описание диагностики
            <!-- Описание диагностики заполняется вручную -->

            Реальный текст описания.

            ## Примеры
            Пример кода.""";

        String cleaned = DiagnosticDescription.cleanMarkdown(raw);

        // The H1 title survives.
        assertTrue(cleaned.contains("# Название (EventHandlerInvalidSignature)"));
        // Both H2 sections and the real body survive.
        assertTrue(cleaned.contains("## Описание диагностики"));
        assertTrue(cleaned.contains("Реальный текст описания."));
        assertTrue(cleaned.contains("## Примеры"));
        assertTrue(cleaned.contains("Пример кода."));
        // The metadata table and comments are gone.
        assertFalse(cleaned.contains("Тип"));
        assertFalse(cleaned.contains("Важность"));
        assertFalse(cleaned.contains("suspicious"));
        assertFalse(cleaned.contains("|"));
        assertFalse(cleaned.contains("<br>"));
        assertFalse(cleaned.contains("<!--"));
    }

    @Test
    public void noHeadingKeepsWholeBodyWhenNoMetadataTablePresent()
    {
        String raw = "Just a plain description, no headings, no table.";

        String cleaned = DiagnosticDescription.cleanMarkdown(raw);

        assertEquals("Just a plain description, no headings, no table.", cleaned);
    }

    @Test
    public void noH2FallbackDropsOnlyBrBearingPipeRows()
    {
        String raw = """
            # Title Only

            | Col1 | Col2<br>more |
            |------|------|
            | `a` | `b`<br>`c` |

            Body text without a heading.""";

        String cleaned = DiagnosticDescription.cleanMarkdown(raw);

        assertTrue(cleaned.contains("# Title Only"));
        assertTrue(cleaned.contains("Body text without a heading."));
        // Rows containing <br> are dropped...
        assertFalse(cleaned.contains("Col1"));
        assertFalse(cleaned.contains("`a`"));
        assertFalse(cleaned.contains("<br>"));
        // ...but a pipe row without <br> (e.g. the separator) is not touched by the fallback.
        assertTrue(cleaned.contains("------"));
    }

    @Test
    public void remainingBrTagVariantsAreReplacedWithSingleSpace()
    {
        String raw = "# Title\n## Description\nLine one<br>Line two<br/>Line three<br />Line four";

        String cleaned = DiagnosticDescription.cleanMarkdown(raw);

        assertFalse(cleaned.toLowerCase(java.util.Locale.ROOT).contains("<br"));
        assertTrue(cleaned.contains("Line one Line two Line three Line four"));
    }

    @Test
    public void runsOfThreeOrMoreNewlinesAreCollapsedToTwo()
    {
        String raw = "# Title\n\n\n\n\n## Section\nBody";

        String cleaned = DiagnosticDescription.cleanMarkdown(raw);

        assertFalse(cleaned.contains("\n\n\n"));
    }

    @Test
    public void resultIsStrippedOfLeadingAndTrailingWhitespace()
    {
        String raw = "\n\n  # Title\nBody\n\n  ";

        String cleaned = DiagnosticDescription.cleanMarkdown(raw);

        assertEquals(cleaned, cleaned.strip());
        assertTrue(cleaned.startsWith("#"));
    }
}
