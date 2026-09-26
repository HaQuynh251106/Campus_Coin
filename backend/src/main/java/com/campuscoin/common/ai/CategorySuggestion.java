package com.campuscoin.common.ai;

/**
 * What a provider proposes for one description (UC-08).
 *
 * <p>It proposes a category <em>by name</em>, and names one from the list it was given. The service
 * resolves that name against the categories the student may actually use, so a name the student does
 * not have is discarded rather than trusted - the provider's answer is checked before it becomes
 * anything, which is the "backend validates and processes the response" step in the required flow.
 *
 * <p><b>Nothing here is applied on its own.</b> BR-13 requires the result to be shown as a
 * suggestion the student reviews and may override, so the calling service stores this beside the
 * record as {@code ai_suggested_category_id} and {@code ai_confidence} and never as the record's
 * category. A student who changes it is what produces the {@code OVERRIDE} rule that teaches the
 * system for next time - which is UC-08's "học từ sửa đổi", implemented as a per-student keyword
 * mapping rather than by retraining a model.
 *
 * @param categoryName the name the provider chose, expected to be one of the names it was given
 * @param type         the proposed type, which the service checks agrees with the resolved category
 * @param confidence   how sure the provider says it is, clamped to {@code [0,1]}. Advisory only:
 *                     it is shown to the student and stored, and never gates anything.
 * @param reason       a short phrase for the student, e.g. "looks like a cafe purchase". Rendered
 *                     beside the suggestion; never presented as advice (BR-13).
 */
public record CategorySuggestion(String categoryName, String type, double confidence, String reason) {
}
