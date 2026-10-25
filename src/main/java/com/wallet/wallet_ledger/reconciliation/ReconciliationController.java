package com.wallet.wallet_ledger.reconciliation;

import com.wallet.wallet_ledger.idempotency.ActionResult;
import com.wallet.wallet_ledger.idempotency.IdempotencyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
public class ReconciliationController {

    private final ReconciliationService reconciliationService;
    private final IdempotencyService idempotencyService;

    public ReconciliationController(ReconciliationService reconciliationService,
                                    IdempotencyService idempotencyService) {
        this.reconciliationService = reconciliationService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/admin/reconciliation/run")
    public ResponseEntity<String> run(@RequestHeader("Idempotency-Key") UUID idempotencyKey) {
        return idempotencyService.execute(null, idempotencyKey, Map.of("action", "RECONCILIATION_RUN"),
                () -> new ActionResult(HttpStatus.ACCEPTED, reconciliationService.run(), null)
        ).toResponseEntity();
    }

    @GetMapping("/admin/reconciliation/runs/{runId}")
    public ReconciliationRunResponse getRun(@PathVariable long runId) {
        return reconciliationService.getRun(runId);
    }
}