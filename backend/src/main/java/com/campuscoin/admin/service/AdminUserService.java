package com.campuscoin.admin.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.admin.dto.AdminPasswordResetResponse;
import com.campuscoin.admin.dto.AdminUserResponse;
import com.campuscoin.admin.dto.SetUserStatusRequest;
import com.campuscoin.admin.entity.AdminUserRow;
import com.campuscoin.admin.mapper.AdminUserMapper;
import com.campuscoin.admin.repository.AdminUserProcedureDao;
import com.campuscoin.admin.repository.AdminUserViewDao;
import com.campuscoin.auth.entity.AccountStatus;
import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.auth.security.PasswordResetLinkBuilder;
import com.campuscoin.auth.security.PasswordResetNotifier;
import com.campuscoin.auth.security.TokenHashService;
import com.campuscoin.common.exception.DataConflictException;
import com.campuscoin.common.exception.NotFoundException;
import com.campuscoin.common.exception.SelfDisableForbiddenException;

@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    static final String RESET_SENT_MESSAGE =
            "A password reset link has been sent to the account's email address.";

    private static final String RELOAD_AND_RETRY =
            "The account could not be changed. Reload it and try again.";

    private final AdminUserViewDao userViewDao;
    private final AdminUserProcedureDao userProcedureDao;
    private final AdminUserMapper userMapper;
    private final TokenHashService tokenHashService;
    private final PasswordResetNotifier notifier;
    private final PasswordResetLinkBuilder linkBuilder;

    public AdminUserService(AdminUserViewDao userViewDao,
                            AdminUserProcedureDao userProcedureDao,
                            AdminUserMapper userMapper,
                            TokenHashService tokenHashService,
                            PasswordResetNotifier notifier,
                            PasswordResetLinkBuilder linkBuilder) {
        this.userViewDao = userViewDao;
        this.userProcedureDao = userProcedureDao;
        this.userMapper = userMapper;
        this.tokenHashService = tokenHashService;
        this.notifier = notifier;
        this.linkBuilder = linkBuilder;
    }

    @Transactional(readOnly = true)
    public List<AdminUserResponse> list() {
        return userMapper.toResponses(userViewDao.findAll());
    }

    @Transactional
    public AdminUserResponse setStatus(AuthenticatedUser actor, Long targetUserId,
                                       SetUserStatusRequest request, String ipAddress) {

        AdminUserRow target = loadAccount(targetUserId);

        if (target.id().equals(actor.userId()) && request.status() == AccountStatus.DISABLED) {
            log.info("Self-disable refused adminUserId={}", actor.userId());
            throw new SelfDisableForbiddenException(
                    "You cannot disable your own account. Ask another administrator to do it.");
        }

        try {
            userProcedureDao.setStatus(targetUserId, actor.userId(), request.status(), ipAddress);
        } catch (RuntimeException ex) {
            throw translateWriteFailure(ex, targetUserId);
        }

        log.info("Account status changed actorId={} targetUserId={} status={}",
                actor.userId(), targetUserId, request.status());

        return userMapper.toResponse(loadForResponse(targetUserId));
    }

    @Transactional
    public AdminPasswordResetResponse sendPasswordReset(AuthenticatedUser actor, Long targetUserId,
                                                        String ipAddress) {
        AdminUserRow target = loadAccount(targetUserId);

        String rawToken = tokenHashService.newSecretToken();

        try {
            userProcedureDao.sendPasswordReset(
                    targetUserId,
                    actor.userId(),
                    tokenHashService.sha256Hex(rawToken),
                    ipAddress);
        } catch (RuntimeException ex) {
            throw translateWriteFailure(ex, targetUserId);
        }

        notifier.sendPasswordResetLink(target.email(), linkBuilder.build(rawToken));

        log.info("Administrator sent a password reset actorId={} targetUserId={}",
                actor.userId(), targetUserId);

        return new AdminPasswordResetResponse(RESET_SENT_MESSAGE);
    }

    private AdminUserRow loadAccount(Long userId) {
        return userViewDao.findOne(userId)
                .orElseThrow(() -> new NotFoundException("User account not found."));
    }

    private AdminUserRow loadForResponse(Long userId) {
        return userViewDao.findOne(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "User " + userId + " was not readable back after being written."));
    }

    private RuntimeException translateWriteFailure(RuntimeException ex, Long targetUserId) {
        if (AdminWriteFailure.isSignalledRefusal(ex)) {
            log.info("Account write rejected by a procedure signal targetUserId={}", targetUserId);
            return new DataConflictException(RELOAD_AND_RETRY);
        }

        if (AdminWriteFailure.isConstraintViolation(ex)) {

            log.info("Account write rejected by a constraint targetUserId={}", targetUserId);
            return new DataConflictException(RELOAD_AND_RETRY);
        }

        log.error("Account write failed unexpectedly targetUserId={}", targetUserId, ex);
        return ex;
    }
}
