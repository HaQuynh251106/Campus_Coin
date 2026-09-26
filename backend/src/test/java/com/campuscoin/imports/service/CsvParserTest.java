package com.campuscoin.imports.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.campuscoin.common.exception.RequestValidationException;

/**
 * The CSV dialect, tested directly rather than through an upload.
 *
 * <p><b>Why this is a unit test while most of the module is not.</b> {@link CsvParser} has no
 * collaborator and no state: it takes the file's text and returns headers and records. Every case that
 * matters is therefore a string, and the cases that matter are exactly the ones an upload is worst at
 * producing - a byte-order mark on the first cell, a description containing a comma and a doubled
 * quote, a quote left open on the last line. Reaching those through the API would mean writing each one
 * into a multipart body and reading the refusal out of an error envelope, which tests the transport
 * rather than the dialect.
 *
 * <p><b>What is deliberately not asserted here.</b> Whether a cell is a date or an amount. The parser
 * produces strings and stops, and {@link ImportRowReaderTest} is where those strings are given meaning.
 * A test here that checked a parsed amount would be pinning a rule this class does not own.
 *
 * <p><b>The line numbers are the file's lines, not the data's.</b> The header is line 1, so the first
 * data row is line 2 - the number a student sees in their spreadsheet, which is what makes a refusal
 * that names a row something they can act on. Several tests below exist to pin that offset, because an
 * off-by-one here would send every message to the wrong row.
 */
class CsvParserTest {

    private final CsvParser parser = new CsvParser();

    // ------------------------------------------------------------------
    //  The shape of a file
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-11: a file becomes its columns and one record per data row")
    void aFileBecomesItsColumnsAndRecords() {
        CsvParser.CsvDocument document = parser.parse("""
                date,amount,type,description,category
                2026-09-01,12.50,EXPENSE,Campus cafe,Food
                2026-09-02,3000.00,INCOME,Allowance,Allowance
                """);

        assertThat(document.headers())
                .containsExactly("date", "amount", "type", "description", "category");
        assertThat(document.records()).hasSize(2);
        assertThat(document.records().getFirst().values())
                .containsEntry("date", "2026-09-01")
                .containsEntry("amount", "12.50")
                .containsEntry("description", "Campus cafe");
    }

    @Test
    @DisplayName("UC-11: the first data row is line 2, because the header is line 1")
    void theFirstDataRowIsLineTwo() {
        // The offset every message in this module depends on. A refusal says "row 7" and the student
        // opens their spreadsheet at row 7 - so a parser that numbered the data from 1 would point them
        // one row above the mistake, which on a file of two hundred rows is a bug they would not catch.
        CsvParser.CsvDocument document = parser.parse("date,amount,type\n2026-09-01,1.00,EXPENSE\n");

        assertThat(document.records()).singleElement()
                .extracting(CsvParser.CsvRecord::lineNumber).isEqualTo(2);
    }

    @Test
    @DisplayName("UC-11: a header may be written in any case, with space around it")
    void aHeaderIsMatchedCaseInsensitively() {
        // A header cell is typed by a person, and "Date " is the same column as "date". The stored form
        // is the normalised one, which is why the assertions are on the lower-case key.
        CsvParser.CsvDocument document = parser.parse("Date , AMOUNT,Type\n2026-09-01,1.00,EXPENSE\n");

        assertThat(document.headers()).containsExactly("date", "amount", "type");
        assertThat(document.hasColumn("date")).isTrue();
        assertThat(document.records().getFirst().value("amount")).isEqualTo("1.00");
    }

    @Test
    @DisplayName("UC-11: a byte-order mark is stripped, so an Excel export still finds its columns")
    void aByteOrderMarkDoesNotHideTheFirstColumn() {
        // Excel's "CSV UTF-8" export writes a BOM, and without this the first header would read
        // "﻿date" and match nothing - every upload from Excel would fail with "the file has no date
        // column" while the file visibly has one.
        CsvParser.CsvDocument document = parser.parse(
                "﻿date,amount,type\n2026-09-01,1.00,EXPENSE\n");

        assertThat(document.headers()).containsExactly("date", "amount", "type");
        assertThat(document.hasColumn("date")).isTrue();
    }

    @Test
    @DisplayName("UC-11: a column the importer does not know is carried and simply not read")
    void anUnknownColumnIsNotARefusal() {
        // A student's export has a "notes" column this importer has no use for. Refusing the file over
        // it would be refusing their own file for a reason they cannot act on, so the column is kept in
        // the header list and no row is asked about it.
        CsvParser.CsvDocument document = parser.parse(
                "date,amount,type,notes\n2026-09-01,1.00,EXPENSE,whatever\n");

        assertThat(document.headers()).containsExactly("date", "amount", "type", "notes");
        assertThat(document.records().getFirst().value("notes")).isEqualTo("whatever");
    }

    // ------------------------------------------------------------------
    //  Quoting
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-11: a quoted field keeps a comma that would otherwise split the row")
    void aQuotedFieldKeepsItsComma() {
        // The first thing a naive split(",") gets wrong. A description is free text the student did not
        // type for this application, so "Campus cafe, level 2" is an ordinary value.
        CsvParser.CsvDocument document = parser.parse(
                "date,amount,type,description\n2026-09-01,12.50,EXPENSE,\"Campus cafe, level 2\"\n");

        assertThat(document.records().getFirst().value("description"))
                .isEqualTo("Campus cafe, level 2");
    }

    @Test
    @DisplayName("UC-11: a doubled quote inside a quoted field is one literal quote")
    void aDoubledQuoteIsOneQuote() {
        // The second thing a naive implementation gets wrong, and the one a spreadsheet produces without
        // being asked: a field containing a double quote is written with it doubled.
        CsvParser.CsvDocument document = parser.parse(
                "date,amount,type,description\n2026-09-01,12.50,EXPENSE,\"said \"\"hello\"\" to me\"\n");

        assertThat(document.records().getFirst().value("description"))
                .isEqualTo("said \"hello\" to me");
    }

    @Test
    @DisplayName("UC-11: a quoted field may span several lines and stays one record")
    void aQuotedFieldMaySpanLines() {
        // A spreadsheet writes a multi-line note this way. Read on, the record is one entry with the
        // newline inside it rather than two broken rows.
        CsvParser.CsvDocument document = parser.parse(
                "date,amount,type,description\n2026-09-01,12.50,EXPENSE,\"first\nsecond\"\n");

        assertThat(document.records()).hasSize(1);
        assertThat(document.records().getFirst().value("description"))
                .isEqualTo("first\nsecond");
    }

    @Test
    @DisplayName("UC-11: a quote inside an unquoted field is an ordinary character")
    void aQuoteInsideAnUnquotedFieldIsLiteral() {
        // An inch mark in a description. Treating it as an opener would swallow the rest of the file.
        CsvParser.CsvDocument document = parser.parse(
                "date,amount,type,description\n2026-09-01,12.50,EXPENSE,6\" ruler\n");

        assertThat(document.records().getFirst().value("description")).isEqualTo("6\" ruler");
    }

    @Test
    @DisplayName("UC-11: an unclosed quote is refused, and the message names the line it opened on")
    void anUnclosedQuoteIsRefused() {
        // Read on to the end of the file and every remaining row would be swallowed into one field, so
        // the student would be shown a one-row preview of a two-hundred-row file with no explanation.
        assertThatThrownBy(() -> parser.parse(
                "date,amount,type\n2026-09-01,1.00,EXPENSE\n2026-09-02,\"2.00,EXPENSE\n"))
                .isInstanceOf(RequestValidationException.class)
                .hasMessageContaining("unclosed quoted value")
                .hasMessageContaining("row 3");
    }

    // ------------------------------------------------------------------
    //  Line endings and short rows
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-11: a CRLF file parses to the same records as an LF file")
    void aCrlfFileParsesLikeAnLfFile() {
        // A Windows spreadsheet writes CRLF, and the carriage return is swallowed so the two files
        // become the same records rather than the last cell of each row carrying a stray character.
        CsvParser.CsvDocument crlf = parser.parse(
                "date,amount,type\r\n2026-09-01,1.00,EXPENSE\r\n");
        CsvParser.CsvDocument lf = parser.parse(
                "date,amount,type\n2026-09-01,1.00,EXPENSE\n");

        assertThat(crlf.records()).isEqualTo(lf.records());
    }

    @Test
    @DisplayName("UC-11: a last line with no trailing newline is still a record")
    void aLastLineWithoutATrailingNewlineIsARecord() {
        CsvParser.CsvDocument document = parser.parse("date,amount,type\n2026-09-01,1.00,EXPENSE");

        assertThat(document.records()).hasSize(1);
        assertThat(document.records().getFirst().value("type")).isEqualTo("EXPENSE");
    }

    @Test
    @DisplayName("UC-11: a row shorter than the header simply lacks the columns it does not reach")
    void aShortRowLacksTheColumnsItDoesNotReach() {
        // A trailing comma the student's editor dropped. Not a parse error: a row missing its optional
        // description is importable, and a row missing its amount is refused by ImportRowReader with a
        // message about the amount rather than about the file's shape.
        CsvParser.CsvDocument document = parser.parse(
                "date,amount,type,description\n2026-09-01,1.00,EXPENSE\n");

        CsvParser.CsvRecord row = document.records().getFirst();
        assertThat(row.value("date")).isEqualTo("2026-09-01");
        assertThat(row.value("description")).isNull();
        assertThat(row.values()).doesNotContainKey("description");
    }

    @Test
    @DisplayName("UC-11: a blank line in the middle of a file is not a record")
    void aBlankLineIsNotARecord() {
        CsvParser.CsvDocument document = parser.parse(
                "date,amount,type\n2026-09-01,1.00,EXPENSE\n\n2026-09-02,2.00,EXPENSE\n");

        assertThat(document.records()).hasSize(2);
        assertThat(document.records().get(1).value("date")).isEqualTo("2026-09-02");
    }

    @Test
    @DisplayName("UC-11: a duplicated header column keeps its first occurrence")
    void aDuplicatedHeaderKeepsTheFirstOccurrence() {
        // A file with two "amount" columns is malformed, but which one decides the amount must be
        // decidable - and the first is what the mapping does. Silently letting the second win would make
        // the result depend on the order the map happened to be filled.
        CsvParser.CsvDocument document = parser.parse("date,amount,type,amount\n2026-09-01,1.00,EXPENSE,9.99\n");

        assertThat(document.records().getFirst().value("amount")).isEqualTo("1.00");
    }

    // ------------------------------------------------------------------
    //  Refusals about the file itself
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UC-11: an empty or blank file is refused before anything is parsed")
    void anEmptyFileIsRefused() {
        for (String content : new String[]{"", "   ", "\n", null}) {
            assertThatThrownBy(() -> parser.parse(content))
                    .as("content=%s", content)
                    .isInstanceOf(RequestValidationException.class)
                    .hasMessage("The file is empty.");
        }
    }

    @Test
    @DisplayName("UC-11: a header missing a required column is refused and names every column it lacks")
    void aMissingRequiredColumnIsNamed() {
        // The message is the whole remedy: it names the columns the file has to grow. All three absent
        // at once is one refusal about the header rather than three about the rows, which is a thing the
        // student can fix once.
        assertThatThrownBy(() -> parser.parse("description,category\nsomething\n"))
                .isInstanceOf(RequestValidationException.class)
                .hasMessageContaining("no date or amount or type column")
                .hasMessageContaining("example: date,amount,type,description,category");
    }

    @Test
    @DisplayName("UC-11: a required column named is not required again, so the message lists only what is absent")
    void onlyTheAbsentColumnsAreListed() {
        assertThatThrownBy(() -> parser.parse("date,description\n2026-09-01,note\n"))
                .isInstanceOf(RequestValidationException.class)
                .hasMessageContaining("no amount or type column")
                .hasMessageNotContaining("no date");
    }

    @Test
    @DisplayName("UC-11: a refusal carries a field error on content, which is the field the client sent")
    void aRefusalNamesTheContentField() {
        // Every refusal here is about the file the student chose, so the error is attachable to the
        // upload control rather than being a form-level message with nowhere to render.
        assertThatThrownBy(() -> parser.parse("amount,type\n1.00,EXPENSE\n"))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(thrown -> assertThat(((RequestValidationException) thrown).getFieldErrors())
                        .extracting(error -> error.field())
                        .containsExactly("content"));
    }

    @Test
    @DisplayName("UC-11: a file whose header is fine but which has no data rows parses to no records")
    void aHeaderWithNoRowsParsesToNoRecords() {
        // The parser does not refuse this - a header alone is a well-formed document with nothing in it.
        // Whether an empty batch is worth storing is the previewer's decision, and ImportPreviewerTest
        // pins that answer.
        CsvParser.CsvDocument document = parser.parse("date,amount,type\n");

        assertThat(document.headers()).containsExactly("date", "amount", "type");
        assertThat(document.records()).isEmpty();
    }

    @Test
    @DisplayName("UC-11: the parser changes no value it did not have to")
    void valuesArePassedThroughUnchanged() {
        // A leading "=" or "@" is left alone: sanitising it is a spreadsheet's job, and silently
        // rewriting a student's description would be changing their data. The only value this class
        // alters is a header cell, and only by trimming and lower-casing it.
        CsvParser.CsvDocument document = parser.parse(
                "date,amount,type,description\n2026-09-01,1.00,EXPENSE,=SUM(A1:A2)\n");

        Map<String, String> values = document.records().getFirst().values();
        assertThat(values).containsEntry("description", "=SUM(A1:A2)");
        assertThat(List.copyOf(values.keySet()))
                .containsExactly("date", "amount", "type", "description");
    }
}
