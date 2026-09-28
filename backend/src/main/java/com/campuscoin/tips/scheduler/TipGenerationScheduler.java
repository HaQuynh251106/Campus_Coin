package com.campuscoin.tips.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.campuscoin.tips.repository.TipGenerationDao;

@Component
@ConditionalOnProperty(name = "campuscoin.tips.scheduler.enabled",
        havingValue = "true", matchIfMissing = true)
public class TipGenerationScheduler {

    private static final Logger log = LoggerFactory.getLogger(TipGenerationScheduler.class);

    private final TipGenerationDao tipGenerationDao;

    public TipGenerationScheduler(TipGenerationDao tipGenerationDao) {
        this.tipGenerationDao = tipGenerationDao;
    }

    @Scheduled(cron = "${campuscoin.tips.scheduler.cron:0 10 0 * * *}",
            zone = "Asia/Ho_Chi_Minh")
    public void generateMonthlyTips() {
        try {
            int students = 0;
            int failures = 0;

            for (Long studentId : tipGenerationDao.findActiveStudentIds()) {
                try {
                    tipGenerationDao.generateTips(studentId, null);
                    students++;
                } catch (RuntimeException ex) {
                    failures++;
                    log.error("Saving-tip generation failed userId={}", studentId, ex);
                }
            }

            log.info("Saving-tip scheduler run completed students={} failures={}", students, failures);
        } catch (RuntimeException ex) {

            log.error("Saving-tip scheduler run failed", ex);
        }
    }
}
