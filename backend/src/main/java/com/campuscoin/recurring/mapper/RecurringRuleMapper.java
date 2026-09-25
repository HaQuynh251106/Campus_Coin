package com.campuscoin.recurring.mapper;

import org.springframework.stereotype.Component;

import com.campuscoin.category.entity.Category;
import com.campuscoin.recurring.dto.RecurringRuleResponse;
import com.campuscoin.recurring.entity.RecurringRule;

/**
 * Maps a {@link RecurringRule} row to the API model (UC-09).
 *
 * <p>Its own class, and a plain one rather than a mapping library, for the reason the transaction
 * and category mappers give: it is the single place that decides which columns may leave the
 * server. The entity carries {@code userId}, and centralising the choice here means a column can be
 * added to it without silently appearing in a response.
 *
 * <p>The flattening of the category and the lifting of {@code type} out of it happen here, exactly
 * as in {@code TransactionMapper}. {@code type} is read from {@link Category#getType()} rather than
 * from the rule's own copy of it, so the response describes the category's current type - which is
 * what BR-05 makes authoritative, and what the two triggers keep the copy in step with.
 */
@Component
public class RecurringRuleMapper {

    /**
     * UC-09: one rule as the client sees it.
     *
     * <p>Deliberately omitted: the owner, and the row's creation and modification timestamps.
     */
    public RecurringRuleResponse toResponse(RecurringRule rule) {
        Category category = rule.getCategory();

        return new RecurringRuleResponse(
                rule.getId(),
                category.getId(),
                category.getName(),
                category.getIcon(),
                category.getColor(),
                category.getType(),
                rule.getAmount(),
                rule.getDescription(),
                rule.getFrequency(),
                rule.getIntervalCount(),
                rule.getStartDate(),
                rule.getEndDate(),
                rule.getNextRunDate(),
                rule.getLastRunDate(),
                rule.getStatus());
    }
}
