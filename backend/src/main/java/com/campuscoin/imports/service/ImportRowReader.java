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

/**
 * Turns one parsed CSV record into a typed row, or into an error row that says why (UC-11).
 *
 * <p><b>UC-11's "invalid rows must be identifiable" is enforced here and that is the whole design.</b>
 * The alternative - refusing the whole file because one line is wrong - would be worse for the student
 * in every case that matters: a bank export with one malformed line is still a file worth importing,
 * and a preview that names the bad line is what lets them fix it and upload again. So a bad row does
 * not throw; it is returned with {@link ImportRowStatus#ERROR} and a sentence about the row.
 *
 * <p><b>Every message names the remedy, not the parser.</b> "Amount must be a positive number" is
 * something a student can act on; a {@code NumberFormatException} message is not. There are no stack
 * traces and no column positions, because the student's remedy is to open the file at the row number
 * the message is filed under.
 *
 * <p><b>What is checked here, and what is deliberately left to the commit.</b> This class decides only
 * whether a value is well-formed and whether the row is internally consistent - a date that parses, an
 * amount that is a positive number, a type that is one of the two members, a description the column can
 * hold. It does <em>not</em> decide whether the category exists, is active, or belongs to the student:
 * those are BR-02/BR-05/BR-07, and {@code sp_apply_csv_batch} enforces them at the commit for every row
 * including ones this class never saw. Duplicating them here would be a second definition of a rule the
 * procedure already owns, and the two could disagree.
 *
 * <p><b>The amount bound is the schema's, not this class's.</b> {@code ck_txn_amount} requires
 * {@code amount > 0} and {@code transactions.amount} is {@code DECIMAL(15,2)}, so a zero, a negative
 * figure and one with more than thirteen integer digits are refused here with a message rather than
 * allowed through to fail as a server error at the commit. That is the same two checks
 * {@code CreateTransactionRequest} applies to a hand-entered record, so a value the API accepts by hand
 * is exactly a value the importer accepts.
 */
@Component
public class ImportRowReader {

    /**
     * The two date formats accepted, and they are the two things a spreadsheet actually writes.
     *
     * <p>{@code uuuu-MM-dd} is ISO and is what this application's own export produces, so a file
     * exported from Campus Coin and imported back works. {@code d/M/uuuu} is the form a Vietnamese or
     * British spreadsheet defaults to. {@code d/M/uuuu} is tried only if ISO fails, so an unambiguous
     * ISO date is never reinterpreted.
     *
     * <p>{@code ResolverStyle.STRICT} with {@code uuuu} rather than {@code yyyy} is deliberate:
     * {@code yyyy} is year-of-era, which accepts a date with no era and resolves {@code 2024-02-30} by
     * rolling it to 2024-03-01 under {@code SMART}. A file with a typo in a day would then import the
     * wrong date silently. Strict mode refuses it, which is what the message says.
     */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT));

    /**
     * The width of {@code import_rows.parsed_description}.
     *
     * <p>Matches the column, and a description longer than it is <em>truncated</em> rather than refused.
     * Refusing would be the wrong call: the description is free text the student did not type into this
     * application, and dropping a whole row - its amount, its date, its category - because a note was
     * long would lose a real record over an annotation. The truncation is bounded by the column, and
     * what the transaction ends up carrying is the same truncated value the preview showed, so the two
     * cannot disagree.
     *
     * <p>Note that this is a plaintext field - see {@code ImportRow} - so there is no envelope to size
     * against here, unlike every other description in this application.
     */
    static final int MAX_DESCRIPTION_LENGTH = 255;

    /** {@code categories.name} and {@code import_rows.parsed_category_name} are both this wide. */
    static final int MAX_CATEGORY_NAME_LENGTH = 80;

    /**
     * UC-11: reads one record.
     *
     * @param record the cells, keyed by column
     * @return a draft that is either {@link ImportRowStatus#VALID} or {@link ImportRowStatus#ERROR}; the
     *         duplicate and ownership decisions are made elsewhere, because both need data this method
     *         does not have
     */
    public ImportRowDraft read(CsvParser.CsvRecord record) {
        String dateCell = trimmed(record.value(CsvParser.COLUMN_DATE));
        String amountCell = trimmed(record.value(CsvParser.COLUMN_AMOUNT));
        String typeCell = trimmed(record.value(CsvParser.COLUMN_TYPE));
        String description = trimmed(record.value(CsvParser.COLUMN_DESCRIPTION));
        String categoryName = trimmed(record.value(CsvParser.COLUMN_CATEGORY));

        // Every field is read before the first failure is reported, so the draft carries whatever was
        // readable even on a refused row: the preview shows the student the values it managed to
        // understand beside the reason it refused, rather than a row of blanks.
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

        // An absent optional is null rather than empty, the same normalisation errorDraft applies and
        // the same one ImportWriteDao's NULLIF(:parsedDescription, '') performs on the way in. Keeping
        // the two draft paths and the write agreeing means "no description" has one representation
        // throughout, so a reader never has to ask whether "" and null mean the same thing here.
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

    /**
     * The line as it arrived, for the preview to show beside the interpretation.
     *
     * <p>Reconstructed from the column map rather than kept as the original substring, which keeps the
     * stored JSON small and its keys stable: a client reads {@code raw_data.description}, not
     * {@code raw_data["3"]}. It is never used to re-derive a parsed value - the parsed columns are what
     * the commit reads - so a client showing the two side by side is showing the student what the
     * importer understood, not a second source of truth.
     */
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

    /**
     * Escapes a value for the JSON object above.
     *
     * <p>Hand-written for the same reason the parser is: one small object does not justify a library,
     * and the alternative - interpolating the raw cell into a JSON string - would corrupt the column
     * for any description containing a quote. {@code raw_data} is a JSON column, so a malformed object
     * is a refused INSERT rather than a wrong value, which is how this would surface.
     */
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

    // ------------------------------------------------------------------
    //  The three values
    // ------------------------------------------------------------------

    /** The first format that parses, or null when neither does. */
    private static LocalDate parseDate(String cell) {
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(cell, format);
            } catch (DateTimeParseException ex) {
                // Tried the next format; the caller reports one message for all of them.
            }
        }
        return null;
    }

    /**
     * A positive amount of at most fifteen digits and two decimal places, or null.
     *
     * <p>Thousands separators are stripped first, because {@code 1,234.50} is what a spreadsheet
     * produces and refusing it would be refusing the student's own file. A comma is only ever a
     * separator here and never a decimal point, which matches the file's own comma-delimited shape: a
     * field cannot contain an unquoted comma at all, so a comma inside this value came from a quoted
     * cell and is a separator. The decimal point must be a dot, because accepting a comma in both roles
     * would make {@code 1,234} ambiguous - 1234 or 1.234 - and a money value silently read as a
     * thousandth of itself is the worst bug this method could have.
     */
    private static BigDecimal parseAmount(String cell) {
        String normalised = cell.replace(",", "").trim();
        BigDecimal amount;
        try {
            amount = new BigDecimal(normalised);
        } catch (NumberFormatException ex) {
            return null;
        }

        // ck_txn_amount. Refused rather than stored, because the CHECK would refuse it at the commit
        // and the student would learn about it one row at a time instead of in the preview.
        if (amount.signum() <= 0) {
            return null;
        }
        // DECIMAL(15,2): 13 integer digits and 2 fraction digits.
        if (amount.precision() - amount.scale() > 13 || amount.scale() > 2) {
            return null;
        }
        return amount.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    /**
     * The member the cell names, or null.
     *
     * <p>Matched case-insensitively and with the surrounding space already trimmed, because a
     * spreadsheet will happily write {@code Expense} or {@code expense}. The comparison uppercases with
     * the default locale's rules, which {@code CategoryRuleMatcher#normalise} avoids for a stored key -
     * the difference is that this value is compared once and immediately discarded, so the Turkish
     * dotless-i hazard cannot outlive the call.
     */
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

    /**
     * The value cut to a column's width.
     *
     * <p>Cut rather than refused, for the reason {@link #MAX_DESCRIPTION_LENGTH} gives. A string that
     * fits is returned unchanged, so nothing here alters a value that did not need altering.
     */
    static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
