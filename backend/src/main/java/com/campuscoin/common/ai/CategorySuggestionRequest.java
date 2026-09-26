package com.campuscoin.common.ai;

import java.util.List;

/**
 * What UC-08 sends to a provider: one description, and the names of the categories to choose from.
 *
 * <p><b>Built to carry the least it can.</b> The description is the text the student typed, which is
 * the whole input the question needs. Everything else the transaction has - its amount, its date,
 * its row id, the student's id - is deliberately absent, because a classifier choosing between
 * "Food" and "Transportation" has no use for any of it and section 7.7 requires the provider to
 * receive only what is necessary. There is no user id here, so nothing in this object identifies
 * whose record is being classified.
 *
 * <p>The category list is names and types only. The provider must not learn the ids: it returns a
 * name, and the service maps that name back to a category the student may actually use - so a
 * provider cannot name a category the student does not have, and a hallucinated id cannot become a
 * foreign key.
 *
 * @param description  the student's own words for the transaction, trimmed and never blank
 * @param categoryNames the categories this student may file under, by name. Defaults and the
 *                      student's own, matching what {@code GET /api/v1/categories} returns.
 * @param candidateTypes which of the two types each category belongs to is carried alongside the
 *                      names, because "Interest" is income and "Entertainment" is expense and a
 *                      provider asked to choose without that can propose a category of the wrong
 *                      type. BR-05 makes the category's type the transaction's type, so a wrong
 *                      type would be a wrong record.
 */
public record CategorySuggestionRequest(String description, List<Candidate> categoryNames) {

    /**
     * One category the student may file under, as the provider sees it.
     *
     * @param name the category's name, exactly as the student's own list spells it
     * @param type {@code INCOME} or {@code EXPENSE}
     */
    public record Candidate(String name, String type) {
    }
}
