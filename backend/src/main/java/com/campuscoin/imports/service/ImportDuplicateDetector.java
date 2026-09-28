package com.campuscoin.imports.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.campuscoin.common.setting.SettingReader;

@Component
public class ImportDuplicateDetector {

    static final int DEFAULT_DUPLICATE_WINDOW_DAYS = 3;

    static final int MAX_NOTE_LENGTH = 255;

    private final SettingReader settingReader;

    public ImportDuplicateDetector(SettingReader settingReader) {
        this.settingReader = settingReader;
    }

    public record Candidate(int lineNumber, Long categoryId, BigDecimal amount, LocalDate txnDate) {
    }

    public record Finding(int lineNumber, LocalDate earlierDate, String note) {
    }

    int duplicateWindowDays() {
        return settingReader.getInt(SettingReader.ANOMALY_DUPLICATE_WINDOW_DAYS,
                DEFAULT_DUPLICATE_WINDOW_DAYS);
    }

    public List<Finding> detect(List<Candidate> existing, List<Candidate> fileRows) {
        int windowDays = duplicateWindowDays();

        List<Candidate> earlier = new ArrayList<>(existing.size() + fileRows.size());
        earlier.addAll(existing);

        List<Finding> findings = new ArrayList<>();

        for (Candidate row : fileRows) {
            Candidate twin = earlierDuplicateOf(row, earlier, windowDays);
            if (twin != null) {
                findings.add(new Finding(row.lineNumber(), twin.txnDate(),
                        duplicateNote(row, twin)));
            }

            earlier.add(row);
        }

        return findings;
    }

    private static Candidate earlierDuplicateOf(Candidate row, List<Candidate> earlier, int windowDays) {
        for (int index = earlier.size() - 1; index >= 0; index--) {
            Candidate other = earlier.get(index);

            if (other.categoryId() == null || row.categoryId() == null
                    || !other.categoryId().equals(row.categoryId())) {
                continue;
            }
            if (other.amount() == null || row.amount() == null
                    || other.amount().compareTo(row.amount()) != 0) {
                continue;
            }
            if (other.txnDate() == null || row.txnDate() == null
                    || Math.abs(ChronoUnit.DAYS.between(other.txnDate(), row.txnDate())) > windowDays) {
                continue;
            }
            return other;
        }

        return null;
    }

    private static String duplicateNote(Candidate row, Candidate twin) {
        String note = "You already have a record of " + row.amount().toPlainString() + " in this "
                + "category on " + twin.txnDate() + ". This row was not imported.";

        return note.length() <= MAX_NOTE_LENGTH
                ? note
                : "You already have a record of this amount in this category. This row was not "
                        + "imported.";
    }

    static boolean sameFinding(Finding first, Finding second) {
        return first != null && second != null
                && first.lineNumber() == second.lineNumber()
                && Objects.equals(first.earlierDate(), second.earlierDate());
    }
}
