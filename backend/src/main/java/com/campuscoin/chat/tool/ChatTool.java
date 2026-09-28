package com.campuscoin.chat.tool;

import java.util.Map;

import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;

public enum ChatTool {

    FINANCIAL_SUMMARY("getFinancialSummary",
            "The student's current month: total income, total expense, the net difference, the "
                    + "highest-spending expense category, the monthly allowance baseline and the "
                    + "monthly savings goal. Use this for questions about 'my balance', 'how am I "
                    + "doing', or 'this month' when no particular month is named. Takes no arguments.",
            Schema.builder().type(Type.Known.OBJECT).properties(Map.of()).build()),

    MONTHLY_SUMMARY("getMonthlySummary",
            "One named month's financial totals and per-category breakdown. Returns total income, total "
                    + "expense, net, the number of records, and every category's total, share and record "
                    + "count. Use this when the student names a month, or asks about a month other than "
                    + "the current one.",
            Schema.builder()
                    .type(Type.Known.OBJECT)
                    .properties(Map.of("period", string(
                            "The month to report, as yyyy-MM, for example 2026-08. Required - there is "
                                    + "no default and no way to mean 'the current month' here.")))
                    .required("period")
                    .build()),

    CATEGORY_SPENDING("getCategorySpending",
            "How much was spent or received in one category during one month, and that category's share "
                    + "of the month. The category is matched against the student's own category names, "
                    + "case-insensitively; part of a name works when it identifies exactly one category. "
                    + "If nothing matches, the result says the category was not found - it never reports "
                    + "zero for a category that does not exist.",
            Schema.builder()
                    .type(Type.Known.OBJECT)
                    .properties(Map.of(
                            "period", string("The month to look in, as yyyy-MM."),
                            "category", string("The category's name, or enough of it to identify one, "
                                    + "for example 'Food'.")))
                    .required("period", "category")
                    .build()),

    BUDGET_STATUS("getBudgetStatus",
            "The student's spending limits for a month, each with the amount spent, the amount left, the "
                    + "percentage consumed and a status of ON_TRACK, NEAR or EXCEEDED. The statuses and "
                    + "their thresholds are the application's own, not an opinion - report them as given. "
                    + "Use this to answer whether the student is over budget. An empty list means the "
                    + "student has set no limits for that month, which is a different answer from being "
                    + "under every limit.",
            Schema.builder()
                    .type(Type.Known.OBJECT)
                    .properties(Map.of("period", string(
                            "The month to report limits for, as yyyy-MM.")))
                    .required("period")
                    .build()),

    TRANSACTIONS("getTransactions",
            "The student's individual records between two dates, newest first, optionally narrowed to "
                    + "one category and to income or expense. Each entry carries the category, amount, "
                    + "date, description and source. Use this only when the student asks about particular "
                    + "purchases - for totals prefer getMonthlySummary or getCategorySpending. The range "
                    + "is required and the result is capped, and the result reports how many records "
                    + "matched in all, so a capped answer is never presented as complete.",
            Schema.builder()
                    .type(Type.Known.OBJECT)
                    .properties(Map.of(
                            "from", string("Earliest date to include, as yyyy-MM-dd. Required."),
                            "to", string("Latest date to include, as yyyy-MM-dd. Required."),
                            "category", string("Limit to one category by name. Omit for every category."),
                            "kind", Schema.builder()
                                    .type(Type.Known.STRING)
                                    .enum_("INCOME", "EXPENSE")
                                    .description("Limit to income or to expense. Omit for both.")
                                    .build()))
                    .required("from", "to")
                    .build()),

    SAVING_TIPS("getSavingTips",
            "The saving tips that already exist for one month of the student's own records, each with its "
                    + "category, title, body, the potential saving it names and whether it is new or "
                    + "pinned. This reads the tips the application has already produced; it never creates "
                    + "new ones. An empty list means no tips exist for that month.",
            Schema.builder()
                    .type(Type.Known.OBJECT)
                    .properties(Map.of("period", string("The month to read tips for, as yyyy-MM.")))
                    .required("period")
                    .build()),

    FORECAST("getForecast",
            "The student's spending projected for the next month, computed from their own recent months, "
                    + "together with those months' totals and this month's running totals. Returns how "
                    + "many months the projection is based on, the projected income, expense and net, and "
                    + "the confidence the application reports for it. Use this for questions about trends "
                    + "or what next month looks like. Takes no arguments.",
            Schema.builder().type(Type.Known.OBJECT).properties(Map.of()).build()),

    ANOMALIES("getAnomalies",
            "The student's records that the anomaly check has already marked, each with the amount, date, "
                    + "description and the detector's explanation of why it was flagged. The two kinds "
                    + "are DUPLICATE (another record has the same amount in the same category within a "
                    + "few days) and UNUSUAL_AMOUNT (the amount is several times what the student usually "
                    + "spends in that category). An empty list means nothing of the student's looks "
                    + "wrong, which is the ordinary case. Takes no arguments.",
            Schema.builder().type(Type.Known.OBJECT).properties(Map.of()).build()),

    RECENT_ACTIVITY("getRecentActivity",
            "The student's own most recent movements through their records: which transaction they opened "
                    + "or changed, and when. This is about the student's activity in the application "
                    + "rather than about their money - use it only when they ask what they were last "
                    + "looking at or changing. Takes no arguments.",
            Schema.builder().type(Type.Known.OBJECT).properties(Map.of()).build());

    private final String functionName;
    private final String description;
    private final Schema parameters;

    ChatTool(String functionName, String description, Schema parameters) {
        this.functionName = functionName;
        this.description = description;
        this.parameters = parameters;
    }

    private static Schema string(String description) {
        return Schema.builder().type(Type.Known.STRING).description(description).build();
    }

    public String functionName() {
        return functionName;
    }

    public FunctionDeclaration declaration() {
        return FunctionDeclaration.builder()
                .name(functionName)
                .description(description)
                .parameters(parameters)
                .build();
    }

    public static ChatTool byFunctionName(String name) {
        for (ChatTool tool : values()) {
            if (tool.functionName.equals(name)) {
                return tool;
            }
        }
        return null;
    }
}
