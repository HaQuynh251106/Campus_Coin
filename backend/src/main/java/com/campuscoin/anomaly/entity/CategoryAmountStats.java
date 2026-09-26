package com.campuscoin.anomaly.entity;

import java.math.BigDecimal;

/**
 * One category's totals for one student: how many records it holds and what they add up to (UC-24).
 *
 * <p><b>Why the pair and not the average.</b> The detector asks whether a particular record is
 * unusual <em>for its category</em>, and the answer must not include the record under examination in
 * its own baseline - a single 900.00 lunch would otherwise raise its own category's average enough to
 * excuse itself. So what is carried is the count and the sum, and the detector derives
 * {@code (total - thisAmount) / (count - 1)} per row. Carrying a pre-computed average would make that
 * exclusion impossible and would put the arithmetic in two places rather than one.
 *
 * <p><b>Dividing rather than adding up a second time is also what keeps the read one query.</b> The
 * alternative - one average query per candidate row - is a query per record scanned, and the scan is
 * bounded by the student's own history rather than by a page. One grouped read answers it for every
 * row at once.
 *
 * <p>{@code categoryId} is the grouping key and is unique within one answer; the detector looks a row
 * up by it, so it is meaningfully a key here even though this type is not an entity.
 */
public record CategoryAmountStats(
        Long categoryId,
        long recordCount,
        BigDecimal totalAmount) {
}
