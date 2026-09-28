package com.campuscoin.recurring.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.campuscoin.recurring.repository.RecurringProcedureDao;

@Component
@ConditionalOnProperty(name = "campuscoin.recurring.scheduler.enabled",
        havingValue = "true", matchIfMissing = true)
public class RecurringScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecurringScheduler.class);

    private final RecurringProcedureDao recurringProcedureDao;

    public RecurringScheduler(RecurringProcedureDao recurringProcedureDao) {
        this.recurringProcedureDao = recurringProcedureDao;
    }

    @Scheduled(cron = "${campuscoin.recurring.scheduler.cron:0 5 0 * * *}",
            zone = "Asia/Ho_Chi_Minh")
    public void postDueOccurrences() {
        try {
            recurringProcedureDao.postDueOccurrences(null);
            log.info("Recurring scheduler run completed");
        } catch (RuntimeException ex) {

            log.error("Recurring scheduler run failed", ex);
        }
    }
}
