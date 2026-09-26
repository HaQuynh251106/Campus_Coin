package com.campuscoin.common.ai;

import java.util.Optional;

/**
 * The one place the application talks to an external AI provider (UC-08, UC-17).
 *
 * <p><b>The provider never sees the database.</b> This port takes fully-formed context objects that
 * the calling service has already built from the student's own rows, filtered and reduced to what
 * the answer needs. There is no repository, no {@code EntityManager} and no user id in this
 * interface, so an implementation cannot fetch anything for itself - the only data a provider can
 * ever receive is what a service deliberately put in the request. That is the flow section 7 of the
 * brief requires:
 *
 * <pre>
 *   Angular -&gt; Spring Boot -&gt; (backend reads and filters the student's own data)
 *                            -&gt; prepared context -&gt; AI provider
 *           &lt;- backend validates the answer &lt;-
 * </pre>
 *
 * <p><b>Every method returns {@link Optional} and may legitimately be empty.</b> "No provider is
 * configured" and "the provider declined or failed" are both ordinary outcomes, not errors. The
 * caller falls back to the deterministic behaviour the database already implements - the
 * {@code CATEGORY_RULE} keyword match for UC-08 and {@code sp_generate_monthly_insight}'s
 * {@code RULE_BASED} text for UC-17 - so the feature works with no AI provider at all, and gets
 * better when one is configured. An implementation therefore must not throw for a provider fault;
 * it returns empty and the caller continues.
 *
 * <p><b>Nothing here is authoritative.</b> BR-13 requires every AI result to be presented as a
 * suggestion the student can review and override, and never as financial advice. The calling
 * service marks the result as such; this port does not decide presentation, and an implementation
 * must not present its own output as a decision - it proposes a category, it does not file one.
 *
 * @see AiProperties for the {@code ai.enabled} and {@code ai.send_aggregates_only} settings
 */
public interface AiSuggestionPort {

    /**
     * UC-08: proposes a category for one transaction description.
     *
     * <p>The request carries the description the student typed and the list of categories the
     * student may file under, by name only. It deliberately carries no amount, no date, no
     * identifier and no history: classifying twelve words of text does not need any of them, and
     * not sending them is what keeps the call to the minimum section 7 requires.
     *
     * @return the proposal, or empty when no provider is configured or the call failed. An empty
     *         result is not an error - the caller matches the student's own {@code category_rules}
     *         instead.
     */
    Optional<CategorySuggestion> suggestCategory(CategorySuggestionRequest request);

    /**
     * UC-17: turns prepared monthly aggregates into a summary and a piece of advice.
     *
     * <p>The request carries the month's totals and per-category totals for the caller alone -
     * already computed by the database, already scoped to one student, and containing no identifier
     * of any kind. It never carries individual transactions: a monthly insight is about a pattern,
     * and sending the rows that make it up would disclose far more than the answer needs.
     *
     * @return the narrative, or empty when no provider is configured or the call failed. An empty
     *         result is not an error - {@code sp_generate_monthly_insight} already wrote a
     *         rule-based summary, which is what the caller keeps.
     */
    Optional<MonthlyNarrative> narrateMonth(MonthlyNarrativeRequest request);

    /**
     * Whether this implementation calls an external provider.
     *
     * <p>Reported through the API so an operator can tell "the AI suggested this" from "the built-in
     * rules suggested this" without reading logs. It does not change any behaviour.
     */
    boolean isExternalProvider();
}
