package com.campuscoin.common.ai;

/**
 * What a provider wrote about one student's month (UC-17).
 *
 * <p>Two pieces of prose: what the month looked like, and what might help next month. Both are
 * stored in {@code insights.summary_text} and {@code insights.advice_text} with
 * {@code generated_by = 'AI'} and the model's name in {@code model_name}, so a reader can always
 * tell a provider's text from the rule-based text the procedure writes - and
 * {@code sp_generate_monthly_insight} preserves the AI text on a later run rather than overwriting
 * it.
 *
 * <p><b>It is not advice.</b> BR-13 fixes the wording: every AI result is a suggestion the student
 * reviews, and must be labelled "Gợi ý, không phải tư vấn tài chính" - "a suggestion, not financial
 * advice". The label is applied by the service that serves it, not by the provider, because a
 * provider cannot be relied on to describe its own output honestly; the storage records what was
 * generated and the response adds the disclaimer.
 *
 * @param summary what the month looked like, in prose
 * @param advice  what the student might consider next month. A suggestion, framed as one.
 * @param model   the provider's model identifier, stored in {@code insights.model_name} for audit
 */
public record MonthlyNarrative(String summary, String advice, String model) {
}
