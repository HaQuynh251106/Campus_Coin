package com.campuscoin.chat.tool;

import java.util.Map;

import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;

/**
 * The data the chat assistant is allowed to ask for, and nothing else.
 *
 * <p><b>This enum is the whole of the assistant's data boundary.</b> Section 4 of the brief asks for a
 * controlled tool layer rather than a prompt stuffed with the student's records, and section 3 lists
 * what may be disclosed. Those two lists meet here: a capability that is not a constant of this enum
 * cannot be named by the model, and {@link ChatToolExecutor} answers only names it recognises. Adding a
 * capability is therefore a deliberate edit to this file rather than a phrase in a system prompt that a
 * student could talk their way past.
 *
 * <p><b>Every tool reads, and there is no tool that writes.</b> Section 7 makes the assistant
 * read-only by default, so the writing operations of the modules behind these tools - the anomaly scan,
 * tip generation, insight generation, marking a notification read - are deliberately absent. The
 * assistant can describe a student's month; it cannot change it, and it cannot spend a provider call on
 * the student's behalf.
 *
 * <p><b>Why these nine and not the twelve the brief sketches.</b> The brief's list is illustrative
 * ("example logical tools"), and it names several that would each be a second route to a figure another
 * already returns: {@code getBudgets} and {@code getBudgetStatus} are one call over the same list,
 * {@code getMonthlySummary} and {@code getReport} both read a month's totals, {@code getInsight} is
 * derived from the same month the report describes, and {@code getCurrentFinancialSummary} is the
 * dashboard read. Section 4's other instruction - "do not invent duplicate business logic if an
 * existing service already owns the calculation" - decides between them, so the layer exposes one tool
 * per existing service capability rather than one per phrase in the sketch. Every capability the brief
 * lists that survives that test is here; the only one it names that is absent is the insight narrative,
 * which is the same month's figures read twice and is discussed in
 * {@link ChatToolExecutor#monthlySummary}.
 *
 * <p><b>The declarations are built, not parsed from JSON text.</b> Gemini expects a JSON Schema, and the
 * SDK offers {@code FunctionDeclaration.fromJson}, but a JSON literal inside a Java text block is a
 * second language embedded in this file: a stray escape silently produces a declaration the provider
 * rejects, and nothing in the compiler notices. The builders below are checked by the compiler, which
 * makes the declarations the one place in this feature where a mistake is caught before a student asks
 * a question.
 *
 * <p><b>No declaration contains a figure, a name or an identifier.</b> They are constants describing
 * what may be asked for - the data itself never passes through this class, and every value the model
 * eventually sees is assembled by {@link ChatToolExecutor} from the caller's own rows.
 */
public enum ChatTool {

    /**
     * The current month's income, expense and net, the highest-spending category, and the saving goal.
     *
     * <p>The default first call for a question with no month in it. It is the dashboard read, so the
     * figures are the ones the student sees on their own Dashboard screen.
     */
    FINANCIAL_SUMMARY("getFinancialSummary",
            "The student's current month: total income, total expense, the net difference, the "
                    + "highest-spending expense category, the monthly allowance baseline and the "
                    + "monthly savings goal. Use this for questions about 'my balance', 'how am I "
                    + "doing', or 'this month' when no particular month is named. Takes no arguments.",
            Schema.builder().type(Type.Known.OBJECT).properties(Map.of()).build()),

    /**
     * One named month's totals, with every category's share of it.
     *
     * <p>This is the read that resolves "last month", "August" or "the month before". The period is
     * passed through unchanged to the reports service, which refuses a malformed value rather than
     * guessing what was meant.
     */
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

    /**
     * What one category's spending in one month, taken from that month's own report.
     *
     * <p>It reads the report rather than filtering a transaction list, so the figure is the one the
     * student's Reports screen shows for that category and not a second sum that could differ from it.
     * A category this student does not have is reported as not found, which is the honest answer to
     * section 12's case G rather than a zero.
     */
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

    /**
     * The month's spending limits with how much of each has been used.
     *
     * <p>Carries the status the database derived ({@code ON_TRACK}, {@code NEAR}, {@code EXCEEDED})
     * rather than letting the model judge from the percentage, so "was I over budget?" is answered by
     * the same comparison that fires the student's budget alerts.
     */
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

    /**
     * Individual records over a date range, narrowed by category and by income/expense.
     *
     * <p>The one tool that discloses single purchases, so it is the one that needs a scope: the range is
     * mandatory and the result is capped. Section 3 permits transactions; asking for the whole history to
     * answer "what did I spend on coffee?" would not be the minimum the brief asks for.
     */
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

    /**
     * The saving tips the student already has, unmodified.
     *
     * <p>Deliberately not the generate endpoint. Generating writes rows and can compute new advice, which
     * is a change to the student's data and not something a question should trigger - section 7's
     * read-only rule. If the student has no tips for the month, that is the answer.
     */
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

    /**
     * Next month's projection from the student's recent months.
     *
     * <p>Trend and "what should I expect" questions. The confidence the application reports is carried
     * through with the model that produced it, so the assistant can attribute the number instead of
     * asserting it - section 6 forbids inventing a confidence, and the way not to is to hand over the
     * real one.
     */
    FORECAST("getForecast",
            "The student's spending projected for the next month, computed from their own recent months, "
                    + "together with those months' totals and this month's running totals. Returns how "
                    + "many months the projection is based on, the projected income, expense and net, and "
                    + "the confidence the application reports for it. Use this for questions about trends "
                    + "or what next month looks like. Takes no arguments.",
            Schema.builder().type(Type.Known.OBJECT).properties(Map.of()).build()),

    /**
     * Records the anomaly check has already flagged.
     *
     * <p>Reads the marks as they stand. The scan is a write and is not exposed - so asking "is anything
     * wrong?" reports the last examination rather than causing a new one.
     */
    ANOMALIES("getAnomalies",
            "The student's records that the anomaly check has already marked, each with the amount, date, "
                    + "description and the detector's explanation of why it was flagged. The two kinds "
                    + "are DUPLICATE (another record has the same amount in the same category within a "
                    + "few days) and UNUSUAL_AMOUNT (the amount is several times what the student usually "
                    + "spends in that category). An empty list means nothing of the student's looks "
                    + "wrong, which is the ordinary case. Takes no arguments.",
            Schema.builder().type(Type.Known.OBJECT).properties(Map.of()).build()),

    /**
     * Which of the student's transactions they have recently opened or changed.
     *
     * <p>Answers "where was I?" rather than a question about money. Included because section 4 lists
     * recent activity, and because it is the one read that can place the student's own last movement
     * without disclosing anything about another student.
     */
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

    /** A required-or-optional string argument. */
    private static Schema string(String description) {
        return Schema.builder().type(Type.Known.STRING).description(description).build();
    }

    /** The name the provider must use to call this tool. Sent in the declaration, matched on reply. */
    public String functionName() {
        return functionName;
    }

    /**
     * The declaration as the provider receives it.
     *
     * <p>Built per call rather than cached in a field. The objects are small, the SDK's builder is
     * immutable, and a static cache would be shared mutable state for no measurable gain on a request
     * that waits on a network round trip.
     */
    public FunctionDeclaration declaration() {
        return FunctionDeclaration.builder()
                .name(functionName)
                .description(description)
                .parameters(parameters)
                .build();
    }

    /** Looks up a declared tool by the name the provider used, or {@code null} when it named none. */
    public static ChatTool byFunctionName(String name) {
        for (ChatTool tool : values()) {
            if (tool.functionName.equals(name)) {
                return tool;
            }
        }
        return null;
    }
}
