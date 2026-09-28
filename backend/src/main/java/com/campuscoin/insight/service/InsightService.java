package com.campuscoin.insight.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.common.ai.AiSuggestionPort;
import com.campuscoin.common.ai.MonthlyNarrative;
import com.campuscoin.common.ai.MonthlyNarrativeRequest;
import com.campuscoin.common.exception.ApiError;
import com.campuscoin.common.exception.NotFoundException;
import com.campuscoin.common.exception.RequestValidationException;
import com.campuscoin.common.setting.SettingReader;
import com.campuscoin.insight.dto.InsightMonthsResponse;
import com.campuscoin.insight.dto.MonthlyInsightResponse;
import com.campuscoin.insight.entity.InsightRow;
import com.campuscoin.insight.entity.MonthlyCategoryTotal;
import com.campuscoin.insight.mapper.InsightMapper;
import com.campuscoin.insight.repository.InsightViewDao;
import com.campuscoin.insight.repository.InsightWriteDao;

@Service
public class InsightService {

    private static final Logger log = LoggerFactory.getLogger(InsightService.class);

    private static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final String DEFAULT_CURRENCY = "USD";

    private final InsightViewDao insightViewDao;
    private final InsightWriteDao insightWriteDao;
    private final InsightMapper insightMapper;
    private final AiSuggestionPort aiSuggestionPort;
    private final SettingReader settingReader;

    public InsightService(InsightViewDao insightViewDao,
                          InsightWriteDao insightWriteDao,
                          InsightMapper insightMapper,
                          AiSuggestionPort aiSuggestionPort,
                          SettingReader settingReader) {
        this.insightViewDao = insightViewDao;
        this.insightWriteDao = insightWriteDao;
        this.insightMapper = insightMapper;
        this.aiSuggestionPort = aiSuggestionPort;
        this.settingReader = settingReader;
    }

    @Transactional(readOnly = true)
    public MonthlyInsightResponse getInsight(AuthenticatedUser principal, String month) {
        LocalDate periodMonth = resolveMonth(month);

        InsightRow row = insightViewDao.find(principal.userId(), periodMonth)
                .orElseThrow(() -> new NotFoundException(
                        "No insight has been generated for that month."));

        return insightMapper.toInsightResponse(row);
    }

    @Transactional(readOnly = true)
    public InsightMonthsResponse listMonths(AuthenticatedUser principal) {
        return insightMapper.toMonthsResponse(insightViewDao.findMonths(principal.userId()));
    }

    @Transactional
    public MonthlyInsightResponse generate(AuthenticatedUser principal, String month) {
        Long userId = principal.userId();
        LocalDate periodMonth = resolveMonth(month);

        insightWriteDao.generate(userId, periodMonth);

        InsightRow row = insightViewDao.find(userId, periodMonth)
                .orElseThrow(() -> new IllegalStateException(
                        "Generating an insight for " + periodMonth + " stored no row."));

        boolean narrated = narrate(userId, periodMonth, row);

        InsightRow stored = narrated
                ? insightViewDao.find(userId, periodMonth).orElse(row)
                : row;

        log.info("Insight generated userId={} periodMonth={} generatedBy={} provider={}",
                userId, periodMonth, stored.generatedBy(), aiSuggestionPort.isExternalProvider());

        return insightMapper.toInsightResponse(stored);
    }

    private boolean narrate(Long userId, LocalDate periodMonth, InsightRow row) {
        List<MonthlyCategoryTotal> totals =
                insightViewDao.findExpenseTotalsByCategory(userId, periodMonth);

        MonthlyNarrativeRequest request = new MonthlyNarrativeRequest(
                insightMapper.toMonthString(periodMonth),
                settingReader.getString(SettingReader.APP_CURRENCY, DEFAULT_CURRENCY),
                row.totalIncome(),
                row.totalExpense(),
                row.netAmount(),
                totals.stream()
                        .map(total -> new MonthlyNarrativeRequest.CategoryTotal(
                                total.categoryName(), total.totalAmount()))
                        .toList());

        Optional<MonthlyNarrative> narrative = aiSuggestionPort.narrateMonth(request);
        if (narrative.isEmpty()) {
            return false;
        }

        MonthlyNarrative answer = narrative.get();
        if (isBlank(answer.summary()) && isBlank(answer.advice())) {
            log.warn("Insight narrative discarded as empty userId={} periodMonth={}",
                    userId, periodMonth);
            return false;
        }

        int updated = insightWriteDao.writeAiNarrative(
                userId, periodMonth, answer.summary(), answer.advice(), answer.model());

        if (updated != 1) {
            throw new IllegalStateException("Insight narrative wrote " + updated
                    + " rows for " + periodMonth + ", which should be exactly one.");
        }

        return true;
    }

    private LocalDate currentMonth() {
        return YearMonth.from(LocalDate.now(APPLICATION_ZONE)).atDay(1);
    }

    private LocalDate resolveMonth(String month) {
        if (month == null || month.isBlank()) {
            return currentMonth();
        }
        try {
            return YearMonth.parse(month.trim()).atDay(1);
        } catch (RuntimeException ex) {
            throw new RequestValidationException(
                    "The month is not a valid month.",
                    List.of(new ApiError.FieldError("month",
                            "Enter a real month in yyyy-MM form, for example 2026-09.")));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
