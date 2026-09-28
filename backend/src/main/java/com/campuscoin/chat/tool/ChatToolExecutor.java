package com.campuscoin.chat.tool;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.campuscoin.anomaly.dto.FlaggedTransactionResponse;
import com.campuscoin.anomaly.service.AnomalyService;
import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.budget.dto.BudgetResponse;
import com.campuscoin.budget.service.BudgetService;
import com.campuscoin.category.dto.CategoryResponse;
import com.campuscoin.category.service.CategoryService;
import com.campuscoin.common.exception.ApiException;
import com.campuscoin.dashboard.dto.DashboardResponse;
import com.campuscoin.dashboard.dto.DashboardTipResponse;
import com.campuscoin.dashboard.dto.DashboardTopCategoryResponse;
import com.campuscoin.dashboard.service.DashboardService;
import com.campuscoin.forecast.dto.ForecastResponse;
import com.campuscoin.forecast.service.ForecastService;
import com.campuscoin.recent.dto.RecentActivityResponse;
import com.campuscoin.recent.service.RecentActivityService;
import com.campuscoin.reports.dto.ReportCategoryResponse;
import com.campuscoin.reports.dto.ReportResponse;
import com.campuscoin.reports.dto.ReportTotalsResponse;
import com.campuscoin.reports.service.ReportService;
import com.campuscoin.tips.dto.TipListResponse;
import com.campuscoin.tips.dto.TipResponse;
import com.campuscoin.tips.service.TipService;
import com.campuscoin.transaction.dto.TransactionResponse;
import com.campuscoin.transaction.service.TransactionService;

@Component
public class ChatToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ChatToolExecutor.class);

    static final int TRANSACTION_CAP = 50;

    static final int RECENT_ACTIVITY_LIMIT = 10;

    static final int ANOMALY_LIMIT = 20;

    private final DashboardService dashboardService;
    private final ReportService reportService;
    private final BudgetService budgetService;
    private final TransactionService transactionService;
    private final TipService tipService;
    private final ForecastService forecastService;
    private final AnomalyService anomalyService;
    private final RecentActivityService recentActivityService;
    private final CategoryService categoryService;

    public ChatToolExecutor(DashboardService dashboardService,
                            ReportService reportService,
                            BudgetService budgetService,
                            TransactionService transactionService,
                            TipService tipService,
                            ForecastService forecastService,
                            AnomalyService anomalyService,
                            RecentActivityService recentActivityService,
                            CategoryService categoryService) {
        this.dashboardService = dashboardService;
        this.reportService = reportService;
        this.budgetService = budgetService;
        this.transactionService = transactionService;
        this.tipService = tipService;
        this.forecastService = forecastService;
        this.anomalyService = anomalyService;
        this.recentActivityService = recentActivityService;
        this.categoryService = categoryService;
    }

    public Map<String, Object> execute(ChatTool tool, Map<String, Object> arguments,
                                       AuthenticatedUser principal) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;

        log.debug("Chat tool {} for userId={}", tool.functionName(), principal.userId());

        try {
            return switch (tool) {
                case FINANCIAL_SUMMARY -> financialSummary(principal);
                case MONTHLY_SUMMARY -> monthlySummary(principal, text(args, "period"));
                case CATEGORY_SPENDING -> categorySpending(principal, text(args, "period"),
                        text(args, "category"));
                case BUDGET_STATUS -> budgetStatus(principal, text(args, "period"));
                case TRANSACTIONS -> transactions(principal, args);
                case SAVING_TIPS -> savingTips(principal, text(args, "period"));
                case FORECAST -> forecast(principal);
                case ANOMALIES -> anomalies(principal);
                case RECENT_ACTIVITY -> recentActivity(principal);
            };
        } catch (ApiException ex) {

            log.info("Chat tool {} declined for userId={}", tool.functionName(), principal.userId());
            return message(ex.getMessage());
        } catch (RuntimeException ex) {

            log.warn("Chat tool {} failed for userId={} ({})", tool.functionName(),
                    principal.userId(), ex.getClass().getSimpleName());
            return message("That information could not be read just now.");
        }
    }

    private Map<String, Object> financialSummary(AuthenticatedUser principal) {
        DashboardResponse dashboard = dashboardService.getDashboard(principal);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", dashboard.periodMonth());
        result.put("currency", dashboard.summary().currency());
        result.put("totalIncome", dashboard.summary().totalIncome());
        result.put("totalExpense", dashboard.summary().totalExpense());
        result.put("netAmount", dashboard.summary().netAmount());

        if (dashboard.summary().monthlyAllowanceBaseline() != null) {
            result.put("monthlyAllowanceBaseline", dashboard.summary().monthlyAllowanceBaseline());
        }
        if (dashboard.summary().monthlySavingsGoal() != null) {
            result.put("monthlySavingsGoal", dashboard.summary().monthlySavingsGoal());
        }
        if (dashboard.summary().savingsGoalPct() != null) {
            result.put("savingsGoalPct", dashboard.summary().savingsGoalPct());
        }

        DashboardTopCategoryResponse top = dashboard.topCategory();
        if (top == null) {

            result.put("topCategory", null);
            result.put("topCategoryNote",
                    "No expense was recorded this month, so there is no highest-spending category.");
        } else {
            Map<String, Object> category = new LinkedHashMap<>();
            category.put("name", top.categoryName());
            category.put("total", top.totalAmount());
            result.put("topCategory", category);
        }

        return result;
    }

    private Map<String, Object> monthlySummary(AuthenticatedUser principal, String period) {
        ReportResponse report = reportService.getReport(principal, period);
        ReportTotalsResponse totals = report.totals();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", report.periodMonth());
        result.put("currency", report.currency());

        if (totals == null || totals.income() == null) {
            result.put("recorded", false);
            result.put("note", "No records were made in this month at all, so there is nothing to "
                    + "report for it.");
            return result;
        }

        result.put("recorded", true);
        result.put("totalIncome", totals.income());
        result.put("totalExpense", totals.expense());
        result.put("netAmount", totals.net());
        result.put("transactionCount", totals.transactionCount());
        result.put("expenseByCategory", categories(report.expenseByCategory()));
        result.put("incomeByCategory", categories(report.incomeByCategory()));
        return result;
    }

    private Map<String, Object> categorySpending(AuthenticatedUser principal, String period,
                                                 String category) {
        ReportResponse report = reportService.getReport(principal, period);

        List<ReportCategoryResponse> all = new ArrayList<>();
        if (report.expenseByCategory() != null) {
            all.addAll(report.expenseByCategory());
        }
        if (report.incomeByCategory() != null) {
            all.addAll(report.incomeByCategory());
        }

        String wanted = category == null ? "" : category.trim().toLowerCase();
        List<ReportCategoryResponse> matches = all.stream()
                .filter(row -> row.categoryName() != null
                        && row.categoryName().toLowerCase().equals(wanted))
                .toList();
        if (matches.isEmpty() && !wanted.isEmpty()) {
            matches = all.stream()
                    .filter(row -> row.categoryName() != null
                            && row.categoryName().toLowerCase().contains(wanted))
                    .toList();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", report.periodMonth());
        result.put("currency", report.currency());
        result.put("categoryAskedFor", category);

        if (matches.isEmpty()) {
            result.put("found", false);
            result.put("note", all.isEmpty()
                    ? "No records were made in this month at all, so no category has a figure."
                    : "No category of this student's matches that name.");

            if (!all.isEmpty()) {
                result.put("categoriesThisMonth",
                        all.stream().map(ReportCategoryResponse::categoryName).toList());
            }
            return result;
        }

        if (matches.size() > 1) {
            result.put("found", false);
            result.put("note", "More than one of this student's categories matches that name, so which "
                    + "one was meant is unclear.");
            result.put("ambiguousMatches",
                    matches.stream().map(ReportCategoryResponse::categoryName).toList());
            return result;
        }

        ReportCategoryResponse row = matches.get(0);
        result.put("found", true);
        result.put("category", row.categoryName());
        result.put("type", row.type() == null ? null : row.type().name());
        result.put("total", row.total());
        result.put("shareOfMonthPct", row.percentage());
        result.put("transactionCount", row.transactionCount());
        return result;
    }

    private Map<String, Object> budgetStatus(AuthenticatedUser principal, String period) {
        List<BudgetResponse> budgets = budgetService.listBudgets(principal, period);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", period);
        if (budgets.isEmpty()) {
            result.put("budgets", List.of());
            result.put("note", "This student has set no spending limits for that month. That is not the "
                    + "same as being under every limit.");
            return result;
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        for (BudgetResponse budget : budgets) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("category", budget.categoryName());
            entry.put("limit", budget.limitAmount());
            entry.put("spent", budget.spentAmount());
            entry.put("remaining", budget.remainingAmount());
            entry.put("consumedPct", budget.consumedPct());
            entry.put("status", budget.consumptionStatus() == null
                    ? null
                    : budget.consumptionStatus().name());
            entries.add(entry);
        }
        result.put("budgets", entries);
        return result;
    }

    private Map<String, Object> transactions(AuthenticatedUser principal, Map<String, Object> args) {
        LocalDate from = date(text(args, "from"));
        LocalDate to = date(text(args, "to"));
        if (from == null || to == null) {
            return message("A start date and an end date in yyyy-MM-dd form are both required.");
        }

        List<TransactionResponse> rows =
                transactionService.listTransactions(principal, from, to, false);

        String category = text(args, "category");
        String kind = text(args, "kind");
        List<TransactionResponse> filtered = rows.stream()
                .filter(row -> category == null || category.isBlank()
                        || (row.categoryName() != null
                            && row.categoryName().toLowerCase().contains(category.toLowerCase())))
                .filter(row -> kind == null || kind.isBlank()
                        || (row.type() != null && row.type().name().equalsIgnoreCase(kind)))
                .toList();

        List<Map<String, Object>> entries = new ArrayList<>();
        for (TransactionResponse row : filtered.subList(0, Math.min(filtered.size(), TRANSACTION_CAP))) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("date", row.txnDate() == null ? null : row.txnDate().toString());
            entry.put("category", row.categoryName());
            entry.put("type", row.type() == null ? null : row.type().name());
            entry.put("amount", row.amount());
            entry.put("description", row.description());
            entry.put("source", row.source() == null ? null : row.source().name());
            entries.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from.toString());
        result.put("to", to.toString());
        result.put("matched", filtered.size());
        result.put("returned", entries.size());
        result.put("capped", filtered.size() > entries.size());
        if (filtered.size() > entries.size()) {
            result.put("note", "More records matched than are listed here. The list is not complete.");
        }
        result.put("transactions", entries);
        return result;
    }

    private Map<String, Object> savingTips(AuthenticatedUser principal, String period) {
        TipListResponse tips = tipService.listTips(principal, period);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", tips.periodMonth());
        List<Map<String, Object>> entries = new ArrayList<>();
        for (TipResponse tip : tips.tips()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("title", tip.title());
            entry.put("body", tip.body());
            entry.put("potentialSaving", tip.potentialSaving());
            entry.put("state", tip.state() == null ? null : tip.state().name());
            entries.add(entry);
        }
        result.put("tips", entries);
        if (entries.isEmpty()) {
            result.put("note", "No saving tips exist for that month. This only reads tips that were "
                    + "already generated; it does not create any.");
        }
        return result;
    }

    private Map<String, Object> forecast(AuthenticatedUser principal) {
        ForecastResponse forecast = forecastService.forecast(principal);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currentMonth", forecast.currentMonth());
        result.put("projectedMonth", forecast.nextMonth());
        result.put("basedOnMonths", forecast.basedOnMonths());

        if (forecast.currentMonthTotals() != null) {
            Map<String, Object> current = new LinkedHashMap<>();
            current.put("income", forecast.currentMonthTotals().income());
            current.put("expense", forecast.currentMonthTotals().expense());
            current.put("net", forecast.currentMonthTotals().net());
            result.put("currentMonthTotals", current);
        }

        if (forecast.projected() != null) {
            Map<String, Object> projected = new LinkedHashMap<>();
            projected.put("income", forecast.projected().income());
            projected.put("expense", forecast.projected().expense());
            projected.put("savings", forecast.projected().savings());
            result.put("projected", projected);
        }

        if (forecast.recentMonths() != null) {
            List<Map<String, Object>> months = new ArrayList<>();
            for (ForecastResponse.ForecastMonthResponse month : forecast.recentMonths()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("period", month.periodMonth());
                entry.put("income", month.income());
                entry.put("expense", month.expense());
                entry.put("net", month.net());
                months.add(entry);
            }
            result.put("recentMonths", months);
        }

        return result;
    }

    private Map<String, Object> anomalies(AuthenticatedUser principal) {
        List<FlaggedTransactionResponse> flagged =
                anomalyService.list(principal, ANOMALY_LIMIT).entries();

        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (FlaggedTransactionResponse row : flagged) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("date", row.txnDate() == null ? null : row.txnDate().toString());
            entry.put("category", row.categoryName());
            entry.put("amount", row.amount());
            entry.put("description", row.description());
            entry.put("flagType", row.flagType() == null ? null : row.flagType().name());
            entry.put("explanation", row.flagNote());
            entries.add(entry);
        }
        result.put("flagged", entries);
        if (entries.isEmpty()) {
            result.put("note", "Nothing in this student's records is currently flagged. This reads the "
                    + "existing marks; it does not run a new check.");
        }
        return result;
    }

    private Map<String, Object> recentActivity(AuthenticatedUser principal) {
        List<RecentActivityResponse> entries =
                recentActivityService.list(principal, RECENT_ACTIVITY_LIMIT).entries();
        Map<Long, String> categoryNames = categoryNames(principal);

        List<Map<String, Object>> mapped = new ArrayList<>();
        for (RecentActivityResponse row : entries) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("action", row.action() == null ? null : row.action().name());
            entry.put("occurredAt", row.occurredAt() == null ? null : row.occurredAt().toString());
            entry.put("category", categoryNames.getOrDefault(row.categoryId(), "an unnamed category"));
            entry.put("kind", row.categoryType() == null ? null : row.categoryType().name());
            entry.put("amount", row.amount());
            entry.put("description", row.description());
            entry.put("date", row.txnDate() == null ? null : row.txnDate().toString());
            mapped.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activity", mapped);
        if (mapped.isEmpty()) {
            result.put("note", "This student has opened or changed no records yet.");
        }
        return result;
    }

    private Map<Long, String> categoryNames(AuthenticatedUser principal) {
        List<CategoryResponse> categories = categoryService.listCategories(principal);
        Map<Long, String> names = new LinkedHashMap<>();
        if (categories != null) {
            for (CategoryResponse category : categories) {
                if (category.id() != null) {
                    names.put(category.id(), category.name());
                }
            }
        }
        return names;
    }

    private static List<Map<String, Object>> categories(List<ReportCategoryResponse> rows) {
        if (rows == null) {
            return List.of();
        }
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (ReportCategoryResponse row : rows) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("category", row.categoryName());
            entry.put("total", row.total());
            entry.put("shareOfMonthPct", row.percentage());
            entry.put("transactionCount", row.transactionCount());
            mapped.add(entry);
        }
        return mapped;
    }

    private static String text(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static Map<String, Object> message(String text) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", text == null || text.isBlank()
                ? "That information is not available."
                : text);
        return result;
    }
}
