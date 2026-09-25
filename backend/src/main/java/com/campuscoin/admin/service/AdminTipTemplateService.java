package com.campuscoin.admin.service;

import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.admin.dto.CreateTipTemplateRequest;
import com.campuscoin.admin.dto.TipTemplateResponse;
import com.campuscoin.admin.dto.UpdateTipTemplateRequest;
import com.campuscoin.admin.entity.AdminTipTemplateRow;
import com.campuscoin.admin.mapper.AdminTipTemplateMapper;
import com.campuscoin.admin.repository.AdminTipTemplateProcedureDao;
import com.campuscoin.admin.repository.AdminTipTemplateViewDao;
import com.campuscoin.common.exception.DataConflictException;
import com.campuscoin.common.exception.NotFoundException;
import com.campuscoin.common.exception.TipTemplateCodeImmutableException;
import com.campuscoin.common.exception.TipTemplateCodeTakenException;

/**
 * The templates the saving-tip generator renders advice from (UC-21 B3, B4).
 *
 * <p><b>The code is the template's identity and cannot be changed.</b> {@code uk_tip_template_code} says
 * so, the four seeded templates are referred to by it from {@code sp_generate_tips}, and
 * {@code sp_admin_upsert_tip_template}'s update branch does not write the column at all. That last fact
 * is the dangerous one: a {@code PATCH} carrying a different code would succeed, change nothing, and
 * report success - leaving the caller with a code that never moved and no way to find out. This service
 * compares the stored code with the one sent and refuses the difference
 * ({@link TipTemplateCodeImmutableException}); sending the stored code unchanged is accepted, so a
 * client can round-trip a whole template through the endpoint without special-casing the field.
 *
 * <p><b>The code is normalised to upper case here.</b> The schema's collation
 * ({@code utf8mb4_0900_ai_ci}) is case-insensitive, so {@code over_budget} and {@code OVER_BUDGET}
 * collide at {@code uk_tip_template_code} - and the generated tips join on the literal
 * {@code 'OVER_BUDGET'}. Storing the caller's casing would therefore let a template be created that the
 * unique index considers a duplicate of a seeded one while the join, on a case-sensitive path, would
 * not find it. Normalising on the way in keeps the stored value equal to what the join looks for, and
 * makes the immutability comparison below a comparison of like with like.
 *
 * <p><b>{@code condition_params} is not reachable from this module.</b> No request type carries it and
 * {@link AdminTipTemplateRow} has no component for it, so nothing here can write or publish the column.
 * Its meaning is not documented by the schema and nothing in this build reads it; that is recorded as a
 * follow-up rather than guessed at.
 *
 * <p><b>Every write is a {@code CALL}.</b> Nothing here holds a managed {@code TipTemplate} instance,
 * so there is no path that could reach the table without {@code sp_require_admin} and the audit row.
 */
@Service
public class AdminTipTemplateService {

    private static final Logger log = LoggerFactory.getLogger(AdminTipTemplateService.class);

    /** What a template write says when the refusal is not one of the two code rules. */
    private static final String RELOAD_AND_RETRY =
            "The tip template could not be changed. Reload it and try again.";

    private final AdminTipTemplateViewDao tipTemplateViewDao;
    private final AdminTipTemplateProcedureDao tipTemplateProcedureDao;
    private final AdminTipTemplateMapper tipTemplateMapper;

    public AdminTipTemplateService(AdminTipTemplateViewDao tipTemplateViewDao,
                                   AdminTipTemplateProcedureDao tipTemplateProcedureDao,
                                   AdminTipTemplateMapper tipTemplateMapper) {
        this.tipTemplateViewDao = tipTemplateViewDao;
        this.tipTemplateProcedureDao = tipTemplateProcedureDao;
        this.tipTemplateMapper = tipTemplateMapper;
    }

    /** UC-21: every template, active and switched off alike, in display order. */
    @Transactional(readOnly = true)
    public List<TipTemplateResponse> list() {
        return tipTemplateMapper.toResponses(tipTemplateViewDao.findAll());
    }

    /**
     * UC-21 B3: create a template.
     *
     * <p><b>A code already in use is answered as its own conflict</b> rather than as the generic
     * "reload and retry": the caller can act on it - choose another code, or edit the template that
     * holds this one - and the two situations are indistinguishable from a generic message. The
     * database's guarantee is {@code uk_tip_template_code}; the pre-check exists so the common case
     * answers without a round trip, and the constraint branch catches the race where two requests pass
     * the check at once.
     *
     * <p><b>Read back by code, not by {@code LAST_INSERT_ID()}.</b> The procedure has no OUT parameter,
     * and it writes its {@code admin_audit_log} row after the template row, so {@code LAST_INSERT_ID()}
     * after the call reports the audit row's id. The code is unique by index, so it identifies the row
     * just created - and it is what the caller will use to refer to the template afterwards.
     */
    @Transactional
    public TipTemplateResponse create(CreateTipTemplateRequest request, Long actorId, String ipAddress) {
        String code = normaliseCode(request.code());

        if (tipTemplateViewDao.findByCode(code).isPresent()) {
            throw codeTaken(code);
        }

        try {
            tipTemplateProcedureDao.upsertTipTemplate(
                    actorId,
                    null,
                    code,
                    request.conditionType(),
                    request.titleTemplate(),
                    request.bodyTemplate(),
                    request.defaultPriority(),
                    request.isActive(),
                    ipAddress);
        } catch (RuntimeException ex) {
            throw translateWriteFailure(ex, code);
        }

        log.info("Tip template created actorId={} code={}", actorId, code);

        return tipTemplateViewDao.findByCode(code)
                .map(tipTemplateMapper::toResponse)
                .orElseThrow(() -> new IllegalStateException(
                        "The tip template '" + code + "' was not readable back after creation."));
    }

    /**
     * UC-21 B4: change a template.
     *
     * <p><b>An absent field means "leave it alone", and that is the procedure's rule.</b>
     * {@code sp_admin_upsert_tip_template} writes each editable column as {@code IFNULL(p_x, x)}, so a
     * null preserves the stored value and cannot clear it. Passing the request's fields through
     * unchanged is therefore the correct mapping, and it is why {@code UpdateTipTemplateRequest}
     * refuses a blank title or body rather than treating one as "remove the text": a template with no
     * text has nothing to render, and the column is {@code NOT NULL}.
     *
     * <p><b>The stored code is what is sent to the procedure, never the request's.</b> The update branch
     * ignores the parameter, so this changes no behaviour - but the procedure writes it into its audit
     * row, and recording the code the caller typed would put a value in the audit trail that the
     * template does not have. What the caller sent is checked above, not forwarded.
     *
     * @throws NotFoundException                no template has this id
     * @throws TipTemplateCodeImmutableException the request carried a different code
     */
    @Transactional
    public TipTemplateResponse update(Long templateId, UpdateTipTemplateRequest request,
                                      Long actorId, String ipAddress) {
        AdminTipTemplateRow stored = requireTemplate(templateId);

        if (request.code() != null && !normaliseCode(request.code()).equals(stored.code())) {
            // Refused rather than ignored: the procedure would report success and change nothing.
            log.info("Tip template code change refused templateId={} stored={} sent={}",
                    templateId, stored.code(), request.code());
            throw new TipTemplateCodeImmutableException(
                    "A tip template's code cannot be changed. It is currently '" + stored.code() + "'.");
        }

        try {
            tipTemplateProcedureDao.upsertTipTemplate(
                    actorId,
                    templateId,
                    stored.code(),
                    request.conditionType(),
                    request.titleTemplate(),
                    request.bodyTemplate(),
                    request.defaultPriority(),
                    request.isActive(),
                    ipAddress);
        } catch (RuntimeException ex) {
            throw translateWriteFailure(ex, stored.code());
        }

        log.info("Tip template updated actorId={} templateId={}", actorId, templateId);

        return tipTemplateViewDao.findOne(templateId)
                .map(tipTemplateMapper::toResponse)
                .orElseThrow(() -> new IllegalStateException(
                        "Tip template " + templateId + " was not readable back after update."));
    }

    // ------------------------------------------------------------------
    //  Internals
    // ------------------------------------------------------------------

    /** One template, or the {@code 404} that says there is none. */
    private AdminTipTemplateRow requireTemplate(Long templateId) {
        return tipTemplateViewDao.findOne(templateId)
                .orElseThrow(() -> new NotFoundException("Tip template not found."));
    }

    /**
     * The code as {@code tip_templates.code} stores it.
     *
     * <p>{@code Locale.ROOT} rather than the default locale: the Turkish locale maps {@code i} to
     * {@code İ}, so {@code "tip".toUpperCase()} there is not {@code "TIP"} - and a code stored that way
     * would not match the literal {@code sp_generate_tips} joins on. Trimming is part of the same
     * guarantee, since {@code ' CODE '} and {@code 'CODE'} are equal to the collation's trailing-space
     * rules but not to a reader's eye.
     */
    private static String normaliseCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Turns a database refusal into the API error that explains it.
     *
     * <p>The duplicate is asked about first and by constraint name, because every duplicate is also an
     * integrity violation and the specific answer is the one the caller can act on. {@code fk_tip_tpl_created_by}
     * - the acting administrator's account removed mid-request - falls to the constraint branch, and the
     * surviving 45000 to the last: the pre-read has already answered "no such template", so it can only
     * mean {@code sp_require_admin} refusing the actor or the row disappearing.
     */
    private RuntimeException translateWriteFailure(RuntimeException ex, String code) {
        if (AdminWriteFailure.isDuplicateTipTemplateCode(ex)) {
            log.info("Tip template code taken code={}", code);
            return codeTaken(code);
        }

        if (AdminWriteFailure.isSignalledRefusal(ex) || AdminWriteFailure.isConstraintViolation(ex)) {
            log.info("Tip template write refused code={}", code);
            return new DataConflictException(RELOAD_AND_RETRY);
        }

        log.error("Tip template write failed unexpectedly code={}", code, ex);
        return ex;
    }

    private TipTemplateCodeTakenException codeTaken(String code) {
        return new TipTemplateCodeTakenException(
                "A tip template already uses the code '" + code + "'.");
    }
}
