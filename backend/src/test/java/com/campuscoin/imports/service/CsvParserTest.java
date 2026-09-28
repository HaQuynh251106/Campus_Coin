package com.campuscoin.imports.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.campuscoin.common.exception.RequestValidationException;

class CsvParserTest {

    private final CsvParser parser = new CsvParser();

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
        assertThat(document.records().get(0).values())
                .containsEntry("date", "2026-09-01")
                .containsEntry("amount", "12.50")
                .containsEntry("description", "Campus cafe");
    }

    @Test
    @DisplayName("UC-11: the first data row is line 2, because the header is line 1")
    void theFirstDataRowIsLineTwo() {

        CsvParser.CsvDocument document = parser.parse("date,amount,type\n2026-09-01,1.00,EXPENSE\n");

        assertThat(document.records()).singleElement()
                .extracting(CsvParser.CsvRecord::lineNumber).isEqualTo(2);
    }

    @Test
    @DisplayName("UC-11: a header may be written in any case, with space around it")
    void aHeaderIsMatchedCaseInsensitively() {

        CsvParser.CsvDocument document = parser.parse("Date , AMOUNT,Type\n2026-09-01,1.00,EXPENSE\n");

        assertThat(document.headers()).containsExactly("date", "amount", "type");
        assertThat(document.hasColumn("date")).isTrue();
        assertThat(document.records().get(0).value("amount")).isEqualTo("1.00");
    }

    @Test
    @DisplayName("UC-11: a byte-order mark is stripped, so an Excel export still finds its columns")
    void aByteOrderMarkDoesNotHideTheFirstColumn() {

        CsvParser.CsvDocument document = parser.parse(
                "﻿date,amount,type\n2026-09-01,1.00,EXPENSE\n");

        assertThat(document.headers()).containsExactly("date", "amount", "type");
        assertThat(document.hasColumn("date")).isTrue();
    }

    @Test
    @DisplayName("UC-11: a column the importer does not know is carried and simply not read")
    void anUnknownColumnIsNotARefusal() {

        CsvParser.CsvDocument document = parser.parse(
                "date,amount,type,notes\n2026-09-01,1.00,EXPENSE,whatever\n");

        assertThat(document.headers()).containsExactly("date", "amount", "type", "notes");
        assertThat(document.records().get(0).value("notes")).isEqualTo("whatever");
    }

    @Test
    @DisplayName("UC-11: a quoted field keeps a comma that would otherwise split the row")
    void aQuotedFieldKeepsItsComma() {

        CsvParser.CsvDocument document = parser.parse(
                "date,amount,type,description\n2026-09-01,12.50,EXPENSE,\"Campus cafe, level 2\"\n");

        assertThat(document.records().get(0).value("description"))
                .isEqualTo("Campus cafe, level 2");
    }

    @Test
    @DisplayName("UC-11: a doubled quote inside a quoted field is one literal quote")
    void aDoubledQuoteIsOneQuote() {

        CsvParser.CsvDocument document = parser.parse(
                "date,amount,type,description\n2026-09-01,12.50,EXPENSE,\"said \"\"hello\"\" to me\"\n");

        assertThat(document.records().get(0).value("description"))
                .isEqualTo("said \"hello\" to me");
    }

    @Test
    @DisplayName("UC-11: a quoted field may span several lines and stays one record")
    void aQuotedFieldMaySpanLines() {

        CsvParser.CsvDocument document = parser.parse(
                "date,amount,type,description\n2026-09-01,12.50,EXPENSE,\"first\nsecond\"\n");

        assertThat(document.records()).hasSize(1);
        assertThat(document.records().get(0).value("description"))
                .isEqualTo("first\nsecond");
    }

    @Test
    @DisplayName("UC-11: a quote inside an unquoted field is an ordinary character")
    void aQuoteInsideAnUnquotedFieldIsLiteral() {

        CsvParser.CsvDocument document = parser.parse(
                "date,amount,type,description\n2026-09-01,12.50,EXPENSE,6\" ruler\n");

        assertThat(document.records().get(0).value("description")).isEqualTo("6\" ruler");
    }

    @Test
    @DisplayName("UC-11: an unclosed quote is refused, and the message names the line it opened on")
    void anUnclosedQuoteIsRefused() {

        assertThatThrownBy(() -> parser.parse(
                "date,amount,type\n2026-09-01,1.00,EXPENSE\n2026-09-02,\"2.00,EXPENSE\n"))
                .isInstanceOf(RequestValidationException.class)
                .hasMessageContaining("unclosed quoted value")
                .hasMessageContaining("row 3");
    }

    @Test
    @DisplayName("UC-11: a CRLF file parses to the same records as an LF file")
    void aCrlfFileParsesLikeAnLfFile() {

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
        assertThat(document.records().get(0).value("type")).isEqualTo("EXPENSE");
    }

    @Test
    @DisplayName("UC-11: a row shorter than the header simply lacks the columns it does not reach")
    void aShortRowLacksTheColumnsItDoesNotReach() {

        CsvParser.CsvDocument document = parser.parse(
                "date,amount,type,description\n2026-09-01,1.00,EXPENSE\n");

        CsvParser.CsvRecord row = document.records().get(0);
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

        CsvParser.CsvDocument document = parser.parse("date,amount,type,amount\n2026-09-01,1.00,EXPENSE,9.99\n");

        assertThat(document.records().get(0).value("amount")).isEqualTo("1.00");
    }

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

        assertThatThrownBy(() -> parser.parse("amount,type\n1.00,EXPENSE\n"))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(thrown -> assertThat(((RequestValidationException) thrown).getFieldErrors())
                        .extracting(error -> error.field())
                        .containsExactly("content"));
    }

    @Test
    @DisplayName("UC-11: a file whose header is fine but which has no data rows parses to no records")
    void aHeaderWithNoRowsParsesToNoRecords() {

        CsvParser.CsvDocument document = parser.parse("date,amount,type\n");

        assertThat(document.headers()).containsExactly("date", "amount", "type");
        assertThat(document.records()).isEmpty();
    }

    @Test
    @DisplayName("UC-11: the parser changes no value it did not have to")
    void valuesArePassedThroughUnchanged() {

        CsvParser.CsvDocument document = parser.parse(
                "date,amount,type,description\n2026-09-01,1.00,EXPENSE,=SUM(A1:A2)\n");

        Map<String, String> values = document.records().get(0).values();
        assertThat(values).containsEntry("description", "=SUM(A1:A2)");
        assertThat(List.copyOf(values.keySet()))
                .containsExactly("date", "amount", "type", "description");
    }
}
