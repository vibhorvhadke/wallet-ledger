package com.wallet.wallet_ledger.reconciliation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduled jobs run only where worker.enabled=true (the worker container, or locally by default). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "worker.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}