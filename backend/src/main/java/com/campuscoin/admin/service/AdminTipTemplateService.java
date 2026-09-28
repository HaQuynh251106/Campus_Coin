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

@Service
public class AdminTipTemplateService {

    private static final Logger log = LoggerFactory.getLogger(AdminTipTemplateService.class);

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

    @Transactional(readOnly = true)
    public List<TipTemplateResponse> list() {
        return tipTemplateMapper.toResponses(tipTemplateViewDao.findAll());
    }

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

    @Transactional
    public TipTemplateResponse update(Long templateId, UpdateTipTemplateRequest request,
                                      Long actorId, String ipAddress) {
        AdminTipTemplateRow stored = requireTemplate(templateId);

        if (request.code() != null && !normaliseCode(request.code()).equals(stored.code())) {

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

    private AdminTipTemplateRow requireTemplate(Long templateId) {
        return tipTemplateViewDao.findOne(templateId)
                .orElseThrow(() -> new NotFoundException("Tip template not found."));
    }

    private static String normaliseCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

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
