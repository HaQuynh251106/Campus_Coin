package com.campuscoin.admin.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.admin.dto.AdminTopCategoryResponse;
import com.campuscoin.admin.dto.AdminUsageStatsResponse;
import com.campuscoin.admin.mapper.AdminStatsMapper;
import com.campuscoin.admin.repository.AdminStatsViewDao;

/**
 * The two system-wide figures an administrator reads (UC-23).
 *
 * <p><b>Both come from database views and neither is computed here.</b>
 * {@code v_admin_usage_stats} is one aggregate row and {@code v_admin_top_categories} is a ranking
 * over {@code categories}, and the definitions of "student", "active", "transaction" and "amount"
 * belong to the schema - three of them involve conditions this module has no business restating
 * ({@code is_deleted = 0} for BR-09, the role and status columns for a student, a session window for
 * activity). A Java aggregate would be a second answer to a question the view already answers, and the
 * two would drift the moment either changed.
 *
 * <p><b>This service reads and does nothing else.</b> There is no write on either path, no audit row -
 * a read is not what {@code admin_audit_log} records - and no per-student figure. Every number is a
 * count or a {@code SUM} across many accounts; the two money columns are the aggregate exposure
 * recorded as OB-013, and no endpoint in this module returns one student's amounts.
 *
 * <p><b>It is a class rather than a controller that calls the DAO directly</b> because the two reads
 * are still the module's published contract: the one-per-resource split keeps the shapes and their
 * mappers in one place, matching every other module, and it is where a later figure would be added.
 */
@Service
public class AdminStatsService {

    private final AdminStatsViewDao statsViewDao;
    private final AdminStatsMapper statsMapper;

    public AdminStatsService(AdminStatsViewDao statsViewDao, AdminStatsMapper statsMapper) {
        this.statsViewDao = statsViewDao;
        this.statsMapper = statsMapper;
    }

    /**
     * UC-23: the single aggregate row.
     *
     * <p>{@code readOnly = true}, and that is a statement about the SQL as well as the intent: the two
     * statements here are {@code SELECT}s over views, which a read-only connection permits - unlike a
     * {@code CALL}, which MySQL refuses on one. No admin procedure is reachable from this class.
     */
    @Transactional(readOnly = true)
    public AdminUsageStatsResponse usageStats() {
        // The view returns exactly one row by construction. An absent row would mean it was replaced by
        // something that is not the view this module was written against - a deployment fault, not a
        // missing resource the caller could do anything about, so it is not a 404.
        return statsViewDao.findUsageStats()
                .map(statsMapper::toResponse)
                .orElseThrow(() -> new IllegalStateException(
                        "v_admin_usage_stats returned no row; it is defined to return exactly one."));
    }

    /**
     * UC-23: every category by usage, most-used first.
     *
     * <p>Zero-transaction categories are included, because the view is a {@code LEFT JOIN} from
     * {@code categories} and its definition is "every category, with its usage" rather than "the
     * categories in use". An administrator reading this wants to know which shared categories nobody
     * uses as much as which ones everybody does - that is what retiring one (BR-07) is decided on.
     *
     * <p>The order is the DAO's, with a total tie-break, so two identical calls return the same
     * sequence. The view itself has no {@code ORDER BY} and no {@code LIMIT}; see
     * {@code AdminStatsViewDao}.
     */
    @Transactional(readOnly = true)
    public List<AdminTopCategoryResponse> topCategories() {
        return statsMapper.toResponses(statsViewDao.findTopCategories());
    }
}
