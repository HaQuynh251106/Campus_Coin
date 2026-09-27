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

/**
 * Runs one {@link ChatTool} for one student, using the services that already own each figure.
 *
 * <p><b>The caller's identity is a field, not an argument, and there is no user id anywhere else.</b>
 * The {@link AuthenticatedUser} is supplied by {@code ChatService} from the security context, and every
 * call below hands it straight to a domain service whose queries narrow on {@code user_id} first. No
 * tool takes an identifier of any kind from the model, so the provider - which is the untrusted party
 * here, since its arguments are shaped by whatever the student typed - has nothing to name another
 * student with. That is section 9's requirement, and it is structural rather than textual: there is no
 * parameter to abuse.
 *
 * <p><b>No figure is computed here.</b> Every number in every result is a field of a response the
 * module that owns the calculation returned. This class adds no arithmetic, no threshold and no
 * comparison of its own: it chooses which response to read, renames the fields the model will see, and
 * stops. The reason is the one {@code BudgetResponse} records for the same problem - a second
 * definition of "80% of the limit" would be able to disagree with the one that fires the student's
 * alerts, and a student told "you're fine" by the assistant while their budget screen says otherwise
 * has no way to tell which is lying.
 *
 * <p><b>Every result is a small object, and none of them is the whole database.</b> Section 4 forbids
 * dumping the database into the prompt. What is returned is bounded per capability: the transaction list
 * is capped (and says so), the tips and budgets are the month's own lists, the anomaly list is the
 * detector's last verdict, and the recent-activity list is a handful of the student's own last
 * movements. Nothing returns a student's whole history.
 *
 * <p><b>A refusal is a result, not an exception.</b> If a service rejects a month the model made up, or
 * a category the student does not have, the model receives an object saying so and can answer honestly
 * from it. Section 12's case G asks for exactly this - a category that does not exist must be reported
 * as not existing rather than as zero - and the same treatment covers a malformed month, which would
 * otherwise escape as a 400 to the student for a question they did not get wrong.
 *
 * <p><b>Why this is a component and not a private method of the chat service.</b> It is the part of the
 * feature that the security argument is about, so it is the part that should be testable on its own,
 * against the real services, with a principal the test chose. It holds no state between calls.
 */
@Component
public class ChatToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ChatToolExecutor.class);

    /**
     * How many individual records one call may disclose.
     *
     * <p>The transaction tool is the only one that returns single purchases, so it is the only one that
     * needs a bound. Fifty is enough for "what did I buy at the weekend" and small enough that a
     * question about a whole year cannot turn into a disclosure of the whole year. The count of all
     * matches is returned beside the capped list, so the model is never in a position to describe a
     * capped answer as complete.
     */
    static final int TRANSACTION_CAP = 50;

    /**
     * How many of the student's own last movements the activity tool returns.
     *
     * <p>Smaller than the transaction cap on purpose: this answers "what was I just looking at", and the
     * service's own list is a recency window rather than a record of everything.
     */
    static final int RECENT_ACTIVITY_LIMIT = 10;

    /** How many flagged records the anomaly tool returns. */
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

    /**
     * Runs one tool and returns the object the provider will read.
     *
     * <p>The result is always a serialisable map, never {@code null}: a tool that cannot answer returns
     * an object carrying the reason. The provider has no way to receive nothing and then guess.
     *
     * <p><b>This method deliberately carries no {@code @Transactional} of its own.</b> It calls services
     * across several modules, and each of them already declares the transaction its own reads need -
     * {@code ReportService.getReport} and {@code BudgetService.listBudgets} are {@code readOnly}, and the
     * rest are plain reads. Joining them under one read-only transaction would be worse than doing
     * nothing: the tool loop treats a refusal as an ordinary result and carries on to the next call, so a
     * service that throws would leave the shared transaction marked rollback-only while the loop
     * continued to use it, and every later read in the same turn would fail with a rollback error. The
     * refusal is a result this class expects to handle, which rules out wrapping the calls that produce
     * it in one transaction.
     *
     * @param tool      the tool the provider named, already resolved through {@link ChatTool}
     * @param arguments the arguments it supplied, which are untrusted text
     * @param principal the caller, taken from the verified token
     */
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
            // The service rejected something the model supplied - a month that is not a month, a range
            // the wrong way round. Handing the model the sentence the API would have returned to a
            // client lets it answer honestly and, where it can, correct itself on the next turn. The
            // student is not shown this text directly; the provider writes the reply.
            log.info("Chat tool {} declined for userId={}", tool.functionName(), principal.userId());
            return message(ex.getMessage());
        } catch (RuntimeException ex) {
            // Anything else. Logged by type rather than by message, which can carry a row's contents.
            log.warn("Chat tool {} failed for userId={} ({})", tool.functionName(),
                    principal.userId(), ex.getClass().getSimpleName());
            return message("That information could not be read just now.");
        }
    }

    /**
     * UC-12: the current month's totals and the highest-spending category.
     *
     * <p>The month is the one the dashboard reports rather than one this class decided, so the assistant
     * and the student's Dashboard screen cannot be describing different months.
     *
     * <p>{@code tips} and {@code announcements} are dropped. The dashboard's tip list overlaps the
     * saving-tips tool, and an announcement is addressed to students as a population - neither belongs in
     * an answer about this student's own figures, and both would be disclosed to the provider for
     * nothing.
     */
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
            // Present-with-a-note rather than absent: "nothing was spent this month" is the answer to
            // "what is my biggest category", and an omitted field would let the model say it does not
            // know. The brief's section 6 forbids pretending not to know data that was supplied.
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

    /**
     * UC-15: one named month's totals and every category's share of it.
     *
     * <p><b>The insight narrative is deliberately not a tool of its own.</b> The brief's sketch lists
     * {@code getInsight(period)} separately, and it is derived from this same month: asking for both
     * would send one month's figures to the provider twice in one conversation and hand it a
     * pre-written paragraph to paraphrase over its own answer. The figures are what the assistant needs
     * to answer, and it writes its own sentence from them.
     *
     * <p>{@code totals} is absent when the month holds nothing at all, which is how the reports module
     * distinguishes "you recorded nothing in July" from "July netted to nothing". That distinction is
     * carried through rather than flattened to zeros, because it is exactly the distinction a student
     * asking about a quiet month needs.
     */
    private Map<String, Object> monthlySummary(AuthenticatedUser principal, String period) {
        ReportResponse report = reportService.getReport(principal, period);
        ReportTotalsResponse totals = report.totals();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", report.periodMonth());
        result.put("currency", report.currency());

        // A month with no activity has no row in v_monthly_income_expense, so the service substitutes
        // ReportTotals.empty - whose four figures are null rather than zero, because "you recorded
        // nothing in July" and "July netted to nothing" are different claims. That substitute is what
        // arrives here, and testing it means testing the figures rather than the block: the block is
        // always present, and its fields are omitted from the JSON when they are null.
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

    /**
     * What one category holds in one month, from that month's report.
     *
     * <p>The name the model supplied is matched against the student's own category names
     * case-insensitively, and against a substring when no exact name matches - "food" finds
     * "Food & Dining", which is what a student means by it. The match is deliberately conservative:
     * a substring that fits two categories is reported as ambiguous rather than resolved to whichever
     * came first, because silently picking one would put the wrong figure in a confident sentence.
     *
     * <p>This is the tool that answers section 12's case G. A name that matches nothing returns
     * {@code found: false} with the names the student actually has, so the assistant can say the
     * category does not exist instead of reporting a zero.
     */
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
            // The names this student actually has, so the assistant can offer a real one rather than
            // guessing again. These are the student's own category names and disclose nothing else.
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

    /**
     * UC-13: the month's spending limits and how much of each is used.
     *
     * <p>{@code consumedPct} and {@code consumptionStatus} are the database's, taken from
     * {@code v_budget_consumption}. This is the tool behind "was that above my budget?" and section 6's
     * requirement that the assistant not have an opinion of its own about a limit.
     */
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

    /**
     * UC-10: individual records over a range, optionally narrowed.
     *
     * <p>The only tool that returns single purchases, so the only one that caps what it will disclose.
     * The range is passed to the service as it was given, which is what lets the service refuse a range
     * that runs backwards with its own message rather than this class inventing one.
     *
     * <p>The category and kind filters are applied here rather than through the request, because the
     * transaction list endpoint takes neither: it reads a date range and the trash flag. Filtering after
     * the read is honest as long as the counts that accompany the list describe what was filtered, which
     * they do - {@code matched} counts the filtered set.
     */
    private Map<String, Object> transactions(AuthenticatedUser principal, Map<String, Object> args) {
        LocalDate from = date(text(args, "from"));
        LocalDate to = date(text(args, "to"));
        if (from == null || to == null) {
            return message("A start date and an end date in yyyy-MM-dd form are both required.");
        }

        // includeDeleted is always false: a record in the trash is not part of the student's records
        // any more (BR-09), and an assistant describing their spending should not be reading the bin.
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

    /**
     * UC-18: the saving tips that already exist for a month.
     *
     * <p>Reads, never generates. A tip the student has dismissed is absent from the response the
     * service returns, which is the service's own rule (BR-14) and is left alone here.
     */
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

    /**
     * UC-25: next month projected from the student's own recent months.
     *
     * <p>The confidence and the model that produced it are carried through verbatim. Section 6 forbids
     * the assistant inventing a confidence score, and the way not to invent one is to hand over the real
     * one with its author attached.
     */
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

    /**
     * UC-24: the records the anomaly check has already marked.
     *
     * <p>Reads the verdicts as they stand. The scan is the write and is not reachable from the chat
     * service at all, so a question cannot cause one - which matters because the scan writes to the
     * student's records and section 7 keeps the assistant read-only.
     */
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

    /**
     * UC-26: which of the student's own records they last opened or changed.
     *
     * <p>{@code transactionId} is dropped from each entry. The identifier is of no use in a sentence and
     * naming a row's internal id to a third party is disclosure for nothing; the date, category and
     * amount already say which record it was. The same reasoning applies to the {@code categoryId} this
     * record carries: it is translated to the category's name here, through the student's own category
     * list, so the provider reads "Coffee &amp; Snacks" rather than "4" - a name it can put in a sentence
     * and an identifier it could not. When the id matches no category the entry says so rather than
     * carrying the number through, because a bare id is worse than no value at all.
     */
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

    /**
     * The student's own categories as id to name, so a tool that reads a category identifier can say the
     * name instead.
     *
     * <p>One extra read, and it is the read that keeps identifiers out of the conversation. The category
     * list is small, already owned by the student whose principal is passed in, and cheaper to fetch whole
     * than to resolve one at a time - UC-26 may return several records across several categories.
     */
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

    /** One month's category rows, reduced to the four fields an answer needs. */
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

    /** An argument as text, or {@code null} when the model did not supply one. */
    private static String text(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** An argument as a date, or {@code null} when it is not one. */
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

    /** The shape every refusal takes: one sentence the provider can read and act on. */
    private static Map<String, Object> message(String text) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", text == null || text.isBlank()
                ? "That information is not available."
                : text);
        return result;
    }
}
