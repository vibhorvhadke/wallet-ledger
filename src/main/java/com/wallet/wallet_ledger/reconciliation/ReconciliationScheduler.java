package com.wallet.wallet_ledger.reconciliation;

import com.wallet.wallet_ledger.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationService reconciliationService;

    public ReconciliationScheduler(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(cron = "${reconciliation.cron}", zone = "${reconciliation.zone}")
    public void nightlyRun() {
        try {
            ReconciliationRunResponse run = reconciliationService.run();
            log.info("Reconciliation run {} finished: {} with {} issue(s)", run.id(), run.status(), run.issuesFound());
        } catch (ApiException e) {
            log.warn("Reconciliation skipped: {}", e.getMessage()); // another run holds the lock
        }
    }
}