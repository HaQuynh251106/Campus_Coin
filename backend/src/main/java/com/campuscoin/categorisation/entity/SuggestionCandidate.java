package com.campuscoin.categorisation.entity;

import com.campuscoin.category.entity.CategoryType;

/**
 * One category the student may file under, as UC-08 proposes from (UC-08).
 *
 * <p><b>Why not the {@code Category} entity.</b> Two reasons, and the second is the load-bearing one.
 * A suggestion is computed from three facts about a category - its id, its name and its type - plus
 * one more for a tie-break, and a record that carries exactly those cannot come to depend on a column
 * that was added to {@code categories} later. And {@code CategorySuggester} is unit-tested with plain
 * lists: building {@code Category} fixtures means going through module 3's factory, which exists for
 * personal rows and has no counterpart for a default one, so the test would have to construct an entity
 * through a path the application never takes.
 *
 * <p><b>{@code isDefault} is carried for the tie-break, mirroring a precedent rather than inventing a
 * rule.</b> Nothing prevents a student's own category from sharing a name and type with a default one,
 * and when a provider names that name there is then more than one category it could mean.
 * {@code sp_apply_csv_batch} resolves exactly this ambiguity - and resolves it the same way - by
 * ordering {@code (user_id IS NULL)} first, i.e. preferring the student's own row over the shared one.
 * {@code CategorySuggester} follows it, because the same two rows are being chosen between for the same
 * reason, and a second answer to the same question would be a second definition of it.
 * {@code Category#isDefault} is the same reading of {@code user_id IS NULL}.
 */
public record SuggestionCandidate(
        Long categoryId,
        String categoryName,
        CategoryType type,
        boolean isDefault) {
}
