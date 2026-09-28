package com.campuscoin.imports.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.campuscoin.common.exception.ApiError;
import com.campuscoin.common.exception.RequestValidationException;

@Component
public class CsvParser {

    static final String COLUMN_DATE = "date";
    static final String COLUMN_AMOUNT = "amount";
    static final String COLUMN_TYPE = "type";
    static final String COLUMN_DESCRIPTION = "description";
    static final String COLUMN_CATEGORY = "category";

    private static final List<String> REQUIRED_COLUMNS =
            List.of(COLUMN_DATE, COLUMN_AMOUNT, COLUMN_TYPE);

    private static final char BOM = '﻿';

    public record CsvRecord(int lineNumber, Map<String, String> values) {

        public String value(String column) {
            return values.get(column);
        }
    }

    public record CsvDocument(List<String> headers, List<CsvRecord> records) {

        public boolean hasColumn(String column) {
            return headers.contains(column);
        }
    }

    public CsvDocument parse(String content) {
        if (content == null || content.isBlank()) {
            throw refusal("content", "The file is empty.");
        }

        List<List<String>> allRecords = split(content);
        if (allRecords.isEmpty()) {
            throw refusal("content", "The file has no rows.");
        }

        List<String> headers = allRecords.get(0).stream()
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

                }
                case '\n' -> {
                    fields.add(field.toString());
                    field.setLength(0);
                    fieldWasQuoted = false;
                    if (anyContentOnRecord || fields.size() > 1 || !fields.get(0).isBlank()) {
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

        if (!field.isEmpty() || !fields.isEmpty()) {
            fields.add(field.toString());
            if (fields.size() > 1 || !fields.get(0).isBlank()) {
                records.add(List.copyOf(fields));
            }
        }

        return records;
    }

    private static CsvRecord toRecord(List<String> cells, List<String> headers, int index) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int column = 0; column < headers.size() && column < cells.size(); column++) {
            String header = headers.get(column);

            values.putIfAbsent(header, cells.get(column));
        }
        return new CsvRecord(index + 1, values);
    }

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
