package com.wallet.wallet_ledger.reconciliation;

import com.wallet.wallet_ledger.common.ApiException;
import com.wallet.wallet_ledger.risk.RiskExplanationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationService reconciliationService;
    private final RiskExplanationService riskExplanationService;

    public ReconciliationScheduler(ReconciliationService reconciliationService,
                                   RiskExplanationService riskExplanationService) {
        this.reconciliationService = reconciliationService;
        this.riskExplanationService = riskExplanationService;
    }

    @Scheduled(cron = "${reconciliation.cron}", zone = "${reconciliation.zone}")
    public void nightlyRun() {
        // Step 1: check the books
        try {
            ReconciliationRunResponse run = reconciliationService.run();
            log.info("Reconciliation run {} finished: {} with {} issue(s)", run.id(), run.status(), run.issuesFound());
        } catch (ApiException e) {
            log.warn("Reconciliation skipped: {}", e.getMessage()); // another run holds the lock
        }

        // Step 2: explain the day's risk flags (independent of step 1)
        try {
            int explained = riskExplanationService.explainPendingFlags();
            log.info("Risk explanations: {} flag(s) explained", explained);
        } catch (RuntimeException e) {
            log.error("Risk explanation step failed", e);
        }
    }
}