package com.campuscoin.imports.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.campuscoin.common.exception.ApiError;
import com.campuscoin.common.exception.RequestValidationException;

/**
 * Reads a CSV file into records, without a CSV library (UC-11).
 *
 * <p><b>Hand-written because the project has no CSV dependency.</b> {@code pom.xml} carries neither
 * commons-csv nor opencsv, and adding one to parse five columns would be adding a dependency to solve a
 * problem the JDK already solves - the format is small, and the two things a naive {@code split(",")}
 * gets wrong are both fixing here: a quoted field containing a comma, and a quoted field containing a
 * newline.
 *
 * <p><b>The dialect is RFC 4180, narrowed to what a student's spreadsheet exports.</b> Fields are
 * separated by commas, records by {@code \n} or {@code \r\n}, a field may be wrapped in double quotes,
 * and inside a quoted field a doubled quote ({@code ""}) is one literal quote. Nothing else is
 * interpreted. In particular a leading {@code =}, {@code +}, {@code -} or {@code @} is left alone -
 * sanitising it is a spreadsheet's job, not a parser's, and silently rewriting a student's description
 * would be changing their data.
 *
 * <p><b>A byte-order mark is stripped, and that is not cosmetic.</b> Excel writes a UTF-8 BOM before
 * the header on "CSV UTF-8" exports, so the first header cell would otherwise read {@code ﻿date}
 * and match no column - every upload from Excel would fail with "the file has no date column" while the
 * file visibly has one.
 *
 * <p><b>An unbalanced quote is a refusal, not a guess.</b> Reading on to the end of the file looking for
 * the closing quote would silently swallow every remaining row into one field, and the student would be
 * shown a one-row preview of a two-hundred-row file with no explanation. Refusing names the line the
 * quote opened on, which is where the problem is.
 *
 * <p><b>Nothing here validates a value.</b> It produces strings and stops; deciding whether a string is
 * a date or an amount is {@link ImportRowReader}'s job. Keeping the two apart is what lets the parser be
 * tested with no knowledge of the domain, and the reader with no knowledge of quoting.
 */
@Component
public class CsvParser {

    /**
     * The column names this importer recognises, lower-cased.
     *
     * <p>Matched case-insensitively and with surrounding space ignored, because a header cell is typed
     * by a person and {@code "Date "} is the same column as {@code "date"}. The stored form is the
     * lower-case one, so the mapping below is a plain lookup.
     */
    static final String COLUMN_DATE = "date";
    static final String COLUMN_AMOUNT = "amount";
    static final String COLUMN_TYPE = "type";
    static final String COLUMN_DESCRIPTION = "description";
    static final String COLUMN_CATEGORY = "category";

    /**
     * The three columns without which no row could ever become a transaction (BR-05).
     *
     * <p>{@code type} is required alongside the two obvious ones, and the reason is the schema rather
     * than taste. A record's type is its <em>category's</em> type (BR-05), so the category has to be
     * chosen by name <em>and</em> type - and {@code sp_apply_csv_batch}'s fallback is the default
     * category for the type ({@code Other Income} / {@code Miscellaneous}). With no type there is no
     * name to match and no default to fall back to, so every row of the file would be refused one at a
     * time with "No matching category could be determined".
     *
     * <p>Requiring it in the header turns that into one message about the file, naming the column that
     * is missing, which is a thing the student can fix. {@code description} and {@code category} stay
     * optional: a row with no description is importable, and a row with no category falls back.
     */
    private static final List<String> REQUIRED_COLUMNS =
            List.of(COLUMN_DATE, COLUMN_AMOUNT, COLUMN_TYPE);

    private static final char BOM = '﻿';

    /**
     * One record: its physical line in the file, and its cells keyed by column.
     *
     * <p>{@code lineNumber} is where the record <em>begins</em>, counting the header as line 1. That is
     * what a student sees in their spreadsheet, so an error message naming line 7 points at the row they
     * can go and fix. A record containing a quoted newline spans several physical lines and is still
     * reported by the first, which is the line the record starts on.
     *
     * <p>Missing cells are absent from the map rather than present and null, so a reader asks once
     * whether the column was there instead of testing twice.
     */
    public record CsvRecord(int lineNumber, Map<String, String> values) {

        /** The cell for a column, or null when the row is shorter than the header. */
        public String value(String column) {
            return values.get(column);
        }
    }

    /**
     * A parsed file: the columns it declared, and its records in file order.
     *
     * <p>{@code headers} is published so the refusal for an unknown-but-harmless extra column can stay a
     * refusal-free omission: a spreadsheet with a "notes" column the importer does not know about is
     * imported, and the extra column is simply not read. Only the two required columns missing is fatal.
     */
    public record CsvDocument(List<String> headers, List<CsvRecord> records) {

        public boolean hasColumn(String column) {
            return headers.contains(column);
        }
    }

    /**
     * Parses a whole file.
     *
     * @param content the file's text, already decoded as UTF-8 by the caller
     * @return the headers and the data records, in file order
     * @throws RequestValidationException if the file is empty, has no header row, or the header lacks
     *                                    {@code date} or {@code amount}
     */
    public CsvDocument parse(String content) {
        if (content == null || content.isBlank()) {
            throw refusal("content", "The file is empty.");
        }

        List<List<String>> allRecords = split(content);
        if (allRecords.isEmpty()) {
            throw refusal("content", "The file has no rows.");
        }

        List<String> headers = allRecords.getFirst().stream()
                .map(CsvParser::normaliseHeader)
                .toList();

        List<String> missing = REQUIRED_COLUMNS.stream().filter(column -> !headers.contains(column)).toList();
        if (!missing.isEmpty()) {
            throw refusal("content",
                    "The file's first row must name its columns, and it has no "
                            + String.join(" or ", missing) + " column. A header row is required - for "
                            + "example: date,amount,type,description,category");
        }

        List<CsvRecord> records = new ArrayList<>(allRecords.size() - 1);
        for (int index = 1; index < allRecords.size(); index++) {
            records.add(toRecord(allRecords.get(index), headers, index));
        }

        return new CsvDocument(headers, records);
    }

    // ------------------------------------------------------------------
    //  Splitting
    // ------------------------------------------------------------------

    /**
     * The physical line number each record started on, paired with its cells.
     *
     * <p>Returned as a list of records rather than a map, and the line numbers are carried alongside so
     * the caller can report one. A record with a quoted newline is one entry here and several lines in
     * the file, which is exactly the case the line number exists to explain.
     */
    private static List<List<String>> split(String content) {
        List<List<String>> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();

        boolean inQuotes = false;
        boolean fieldWasQuoted = false;
        boolean anyContentOnRecord = false;
        int quoteOpenLine = 0;
        int line = 1;

        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);

            if (inQuotes) {
                if (current == '"') {
                    // A doubled quote is one literal quote; a single one closes the field.
                    if (index + 1 < content.length() && content.charAt(index + 1) == '"') {
                        field.append('"');
                        index++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    if (current == '\n') {
                        line++;
                    }
                    field.append(current);
                }
                continue;
            }

            switch (current) {
                case '"' -> {
                    // A quote opens a quoted field only at the start of one; inside an unquoted field it
                    // is a literal character, which is what a description containing an inch mark is.
                    if (field.isEmpty() && !fieldWasQuoted) {
                        inQuotes = true;
                        fieldWasQuoted = true;
                        quoteOpenLine = line;
                    } else {
                        field.append(current);
                    }
                }
                case ',' -> {
                    fields.add(field.toString());
                    field.setLength(0);
                    fieldWasQuoted = false;
                    anyContentOnRecord = true;
                }
                case '\r' -> {
                    // Swallowed, so a CRLF file and an LF file parse to the same records.
                }
                case '\n' -> {
                    fields.add(field.toString());
                    field.setLength(0);
                    fieldWasQuoted = false;
                    if (anyContentOnRecord || fields.size() > 1 || !fields.getFirst().isBlank()) {
                        records.add(List.copyOf(fields));
                    }
                    fields.clear();
                    anyContentOnRecord = false;
                    line++;
                }
                default -> {
                    field.append(current);
                    anyContentOnRecord = true;
                }
            }
        }

        if (inQuotes) {
            throw refusal("content",
                    "The file has an unclosed quoted value starting on row " + quoteOpenLine
                            + ". A quoted value must be closed with a double quote.");
        }

        // A last line with no trailing newline is still a record.
        if (!field.isEmpty() || !fields.isEmpty()) {
            fields.add(field.toString());
            if (fields.size() > 1 || !fields.getFirst().isBlank()) {
                records.add(List.copyOf(fields));
            }
        }

        return records;
    }

    /**
     * One data row, with its cells keyed by column name.
     *
     * <p>A row shorter than the header simply has no entry for the columns it does not reach, which is
     * the ordinary case for a trailing comma the student's editor dropped. It is not a parse error: a
     * row missing its optional {@code description} is importable, and a row missing its {@code amount}
     * is refused by {@link ImportRowReader} with a message about the amount rather than a message about
     * the file's shape.
     */
    private static CsvRecord toRecord(List<String> cells, List<String> headers, int index) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int column = 0; column < headers.size() && column < cells.size(); column++) {
            String header = headers.get(column);
            // A duplicated header keeps its first occurrence, so a file with two "amount" columns does
            // not have its second one silently decide the amount.
            values.putIfAbsent(header, cells.get(column));
        }
        return new CsvRecord(index + 1, values);
    }

    /**
     * A header cell in the form the mapping uses.
     *
     * <p>Strips the BOM (Excel's "CSV UTF-8" export writes one, and it would hide the first column),
     * trims the surrounding space a person leaves, and lower-cases with {@link Locale#ROOT} so the match
     * cannot depend on the server's locale - the reasoning {@code CategoryRuleMatcher#normalise} records
     * for the same operation.
     */
    private static String normaliseHeader(String cell) {
        String header = cell == null ? "" : cell;
        if (!header.isEmpty() && header.charAt(0) == BOM) {
            header = header.substring(1);
        }
        return header.trim().toLowerCase(Locale.ROOT);
    }

    private static RequestValidationException refusal(String field, String message) {
        return new RequestValidationException(message, List.of(new ApiError.FieldError(field, message)));
    }
}
