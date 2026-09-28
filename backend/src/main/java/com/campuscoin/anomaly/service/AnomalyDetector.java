package com.campuscoin.anomaly.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.campuscoin.anomaly.entity.AnomalyFlagType;
import com.campuscoin.anomaly.entity.CategoryAmountStats;
import com.campuscoin.anomaly.entity.FlaggedTransactionRow;
import com.campuscoin.common.setting.SettingReader;

@Component
public class AnomalyDetector {

    static final int DEFAULT_DUPLICATE_WINDOW_DAYS = 3;

    static final BigDecimal DEFAULT_UNUSUAL_MULTIPLIER = new BigDecimal("3");

    static final int MAX_NOTE_LENGTH = 255;

    private final SettingReader settingReader;

    public AnomalyDetector(SettingReader settingReader) {
        this.settingReader = settingReader;
    }

    public record Verdict(Long transactionId, AnomalyFlagType flagType, String flagNote) {
    }

    public List<Verdict> detect(List<FlaggedTransactionRow> candidates,
                                Map<Long, CategoryAmountStats> categoryStats,
                                int windowDays,
                                BigDecimal multiplier) {
        List<Verdict> verdicts = new ArrayList<>(candidates.size());

        for (int index = 0; index < candidates.size(); index++) {
            FlaggedTransactionRow record = candidates.get(index);

            FlaggedTransactionRow earlierTwin = earlierDuplicateOf(record, candidates, index, windowDays);
            if (earlierTwin != null) {
                verdicts.add(new Verdict(record.transactionId(), AnomalyFlagType.DUPLICATE,
                        duplicateNote(record, earlierTwin)));
                continue;
            }

            if (isUnusuallyLarge(record, categoryStats.get(record.categoryId()), multiplier)) {
                verdicts.add(new Verdict(record.transactionId(), AnomalyFlagType.UNUSUAL_AMOUNT,
                        unusualNote(record, categoryStats.get(record.categoryId()))));
                continue;
            }

            verdicts.add(new Verdict(record.transactionId(), AnomalyFlagType.NONE, null));
        }

        return verdicts;
    }

    int duplicateWindowDays() {
        return settingReader.getInt(SettingReader.ANOMALY_DUPLICATE_WINDOW_DAYS,
                DEFAULT_DUPLICATE_WINDOW_DAYS);
    }

    BigDecimal unusualMultiplier() {
        return settingReader.getDecimal(SettingReader.ANOMALY_UNUSUAL_MULTIPLIER,
                DEFAULT_UNUSUAL_MULTIPLIER);
    }

    private FlaggedTransactionRow earlierDuplicateOf(FlaggedTransactionRow record,
                                                     List<FlaggedTransactionRow> candidates,
                                                     int index,
                                                     int windowDays) {
        for (int earlierIndex = index - 1; earlierIndex >= 0; earlierIndex--) {
            FlaggedTransactionRow other = candidates.get(earlierIndex);

            if (!Objects.equals(other.categoryId(), record.categoryId())) {
                continue;
            }

            if (other.amount().compareTo(record.amount()) != 0) {
                continue;
            }
            if (Math.abs(ChronoUnit.DAYS.between(other.txnDate(), record.txnDate())) > windowDays) {
                continue;
            }

            return other;
        }

        return null;
    }

    private boolean isUnusuallyLarge(FlaggedTransactionRow record,
                                     CategoryAmountStats stats,
                                     BigDecimal multiplier) {
        if (stats == null || stats.recordCount() <= 1) {
            return false;
        }

        BigDecimal othersTotal = stats.totalAmount().subtract(record.amount());
        long othersCount = stats.recordCount() - 1;
        BigDecimal othersAverage = othersTotal.divide(BigDecimal.valueOf(othersCount), 6,
                RoundingMode.HALF_UP);
        BigDecimal threshold = othersAverage.multiply(multiplier);

        return record.amount().compareTo(threshold) >= 0;
    }

    private String duplicateNote(FlaggedTransactionRow record, FlaggedTransactionRow earlierTwin) {
        String note = "This looks like a record you already entered: the same amount in "
                + record.categoryName() + " on " + earlierTwin.txnDate()
                + ". Check whether it was recorded twice.";

        return fits(note)
                ? note
                : "This looks like a record you already entered. Check whether it was recorded twice.";
    }

    private String unusualNote(FlaggedTransactionRow record, CategoryAmountStats stats) {
        long othersCount = stats.recordCount() - 1;
        BigDecimal othersAverage = stats.totalAmount().subtract(record.amount())
                .divide(BigDecimal.valueOf(othersCount), 2, RoundingMode.HALF_UP);

        String note = "Much higher than your usual " + record.categoryName() + " amount: "
                + record.amount().setScale(2, RoundingMode.HALF_UP) + " against your average of "
                + othersAverage + " over your other " + othersCount
                + (othersCount == 1 ? " record" : " records") + " in this category.";

        return fits(note)
                ? note
                : "This amount is much higher than your usual spending in this category.";
    }

    private static boolean fits(String note) {
        return note.length() <= MAX_NOTE_LENGTH;
    }

    static boolean differsFromStored(FlaggedTransactionRow row, Verdict verdict) {
        if (row.flagType() != verdict.flagType()) {
            return true;
        }
        if (verdict.flagType() == AnomalyFlagType.NONE) {

            return row.flagNote() != null || Boolean.TRUE.equals(row.isFlagged());
        }
        return !Objects.equals(row.flagNote(), verdict.flagNote());
    }
}
