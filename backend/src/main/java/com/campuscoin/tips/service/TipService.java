package com.campuscoin.tips.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.common.exception.ApiError;
import com.campuscoin.common.exception.NotFoundException;
import com.campuscoin.common.exception.RequestValidationException;
import com.campuscoin.tips.dto.TipListResponse;
import com.campuscoin.tips.dto.TipMonthsResponse;
import com.campuscoin.tips.dto.TipResponse;
import com.campuscoin.tips.dto.UpdateTipStateRequest;
import com.campuscoin.tips.entity.TipState;
import com.campuscoin.tips.entity.UserTip;
import com.campuscoin.tips.mapper.TipMapper;
import com.campuscoin.tips.repository.TipGenerationDao;
import com.campuscoin.tips.repository.TipViewDao;
import com.campuscoin.tips.repository.UserTipRepository;

@Service
public class TipService {

    private static final Logger log = LoggerFactory.getLogger(TipService.class);

    private static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final TipViewDao tipViewDao;
    private final UserTipRepository userTipRepository;
    private final TipGenerationDao tipGenerationDao;
    private final TipMapper tipMapper;

    public TipService(TipViewDao tipViewDao,
                      UserTipRepository userTipRepository,
                      TipGenerationDao tipGenerationDao,
                      TipMapper tipMapper) {
        this.tipViewDao = tipViewDao;
        this.userTipRepository = userTipRepository;
        this.tipGenerationDao = tipGenerationDao;
        this.tipMapper = tipMapper;
    }

    @Transactional(readOnly = true)
    public TipListResponse listTips(AuthenticatedUser principal, String month) {
        LocalDate periodMonth = resolveMonth(month);

        return tipMapper.toListResponse(periodMonth,
                tipViewDao.findTips(principal.userId(), periodMonth));
    }

    @Transactional(readOnly = true)
    public TipMonthsResponse listMonths(AuthenticatedUser principal) {
        return tipMapper.toMonthsResponse(tipViewDao.findMonthsWithTips(principal.userId()));
    }

    @Transactional
    public TipListResponse generateTips(AuthenticatedUser principal) {
        Long userId = principal.userId();
        LocalDate periodMonth = currentMonth();

        tipGenerationDao.generateTips(userId, periodMonth);

        log.info("Saving tips generated userId={} periodMonth={}", userId, periodMonth);

        return tipMapper.toListResponse(periodMonth,
                tipViewDao.findTips(userId, periodMonth));
    }

    @Transactional
    public TipResponse changeState(AuthenticatedUser principal, Long tipId,
                                   UpdateTipStateRequest request) {
        Long userId = principal.userId();
        TipState requested = request.state();

        UserTip tip = userTipRepository.findByIdAndUserId(tipId, userId)
                .orElseThrow(() -> new NotFoundException("Tip not found."));

        TipState current = tip.getState();

        if (current == TipState.DISMISSED && requested != TipState.DISMISSED) {
            throw new RequestValidationException(
                    "A dismissed tip cannot be brought back.",
                    List.of(new ApiError.FieldError("state",
                            "This tip was dismissed and cannot be restored.")));
        }

        if (current == requested) {

            return tipMapper.toTipResponse(tip);
        }

        tip.setState(requested, LocalDateTime.now(APPLICATION_ZONE));
        userTipRepository.saveAndFlush(tip);

        log.info("Tip state changed userId={} tipId={} from={} to={}",
                userId, tipId, current, requested);

        return tipMapper.toTipResponse(tip);
    }

    private LocalDate currentMonth() {
        return YearMonth.from(LocalDate.now(APPLICATION_ZONE)).atDay(1);
    }

    private LocalDate resolveMonth(String month) {
        if (month == null || month.isBlank()) {
            return currentMonth();
        }
        try {
            return YearMonth.parse(month.trim()).atDay(1);
        } catch (RuntimeException ex) {
            throw new RequestValidationException(
                    "The month is not a valid month.",
                    List.of(new ApiError.FieldError("month",
                            "Enter a real month in yyyy-MM form, for example 2026-09.")));
        }
    }
}
