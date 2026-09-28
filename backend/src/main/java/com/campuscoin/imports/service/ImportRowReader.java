package com.campuscoin.imports.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.campuscoin.category.entity.CategoryType;
import com.campuscoin.imports.entity.ImportRowDraft;
import com.campuscoin.imports.entity.ImportRowStatus;

@Component
public class ImportRowReader {

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT));

    static final int MAX_DESCRIPTION_LENGTH = 255;

    static final int MAX_CATEGORY_NAME_LENGTH = 80;

    public ImportRowDraft read(CsvParser.CsvRecord record) {
        String dateCell = trimmed(record.value(CsvParser.COLUMN_DATE));
        String amountCell = trimmed(record.value(CsvParser.COLUMN_AMOUNT));
        String typeCell = trimmed(record.value(CsvParser.COLUMN_TYPE));
        String description = trimmed(record.value(CsvParser.COLUMN_DESCRIPTION));
        String categoryName = trimmed(record.value(CsvParser.COLUMN_CATEGORY));

        LocalDate date = null;
        BigDecimal amount = null;
        CategoryType type = null;
        List<String> problems = new ArrayList<>(3);

        if (dateCell.isEmpty()) {
            problems.add("Date is missing.");
        } else {
            date = parseDate(dateCell);
            if (date == null) {
                problems.add("Date must be a date, as YYYY-MM-DD or D/M/YYYY.");
            }
        }

        if (amountCell.isEmpty()) {
            problems.add("Amount is missing.");
        } else {
            amount = parseAmount(amountCell);
            if (amount == null) {
                problems.add("Amount must be a positive number, with at most 2 decimal places.");
            }
        }

        if (typeCell.isEmpty()) {
            problems.add("Type is missing.");
        } else {
            type = parseType(typeCell);
            if (type == null) {
                problems.add("Type must be INCOME or EXPENSE.");
            }
        }

        String storedDescription = truncate(description, MAX_DESCRIPTION_LENGTH);
        String storedCategoryName = truncate(categoryName, MAX_CATEGORY_NAME_LENGTH);

        if (!problems.isEmpty()) {
            return errorDraft(record, date, amount, type, storedDescription, storedCategoryName,
                    String.join(" ", problems));
        }

        return new ImportRowDraft(
                record.lineNumber(),
                rawData(record),
                date,
                amount,
                type,
                storedDescription.isEmpty() ? null : storedDescription,
                storedCategoryName.isEmpty() ? null : storedCategoryName,
                null,
                ImportRowStatus.VALID,
                null);
    }

    private static String rawData(CsvParser.CsvRecord record) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (var entry : record.values().entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escape(entry.getKey())).append("\":\"")
                    .append(escape(entry.getValue())).append('"');
        }
        return json.append('}').toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (current < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) current));
                    } else {
                        escaped.append(current);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static ImportRowDraft errorDraft(CsvParser.CsvRecord record, LocalDate date,
                                             BigDecimal amount, CategoryType type,
                                             String description, String categoryName,
                                             String message) {
        return new ImportRowDraft(
                record.lineNumber(),
                rawData(record),
                date,
                amount,
                type,
                description.isEmpty() ? null : description,
                categoryName.isEmpty() ? null : categoryName,
                null,
                ImportRowStatus.ERROR,
                truncate(message, MAX_DESCRIPTION_LENGTH));
    }

    private static LocalDate parseDate(String cell) {
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(cell, format);
            } catch (DateTimeParseException ex) {

            }
        }
        return null;
    }

    private static BigDecimal parseAmount(String cell) {
        String normalised = cell.replace(",", "").trim();
        BigDecimal amount;
        try {
            amount = new BigDecimal(normalised);
        } catch (NumberFormatException ex) {
            return null;
        }

        if (amount.signum() <= 0) {
            return null;
        }

        if (amount.precision() - amount.scale() > 13 || amount.scale() > 2) {
            return null;
        }
        return amount.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    private static CategoryType parseType(String cell) {
        String normalised = cell.trim().toUpperCase(Locale.ROOT);
        for (CategoryType member : CategoryType.values()) {
            if (member.name().equals(normalised)) {
                return member;
            }
        }
        return null;
    }

    private static String trimmed(String cell) {
        return cell == null ? "" : cell.trim();
    }

    static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
