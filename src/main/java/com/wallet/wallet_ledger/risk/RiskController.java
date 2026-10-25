package com.wallet.wallet_ledger.risk;

import com.wallet.wallet_ledger.idempotency.ActionResult;
import com.wallet.wallet_ledger.idempotency.IdempotencyService;
import com.wallet.wallet_ledger.transfer.TransferResponse;
import com.wallet.wallet_ledger.transfer.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
public class RiskController {

    private final RiskQueueService riskQueueService;
    private final TransferService transferService;
    private final IdempotencyService idempotencyService;
    private final RiskExplanationService riskExplanationService;

    public RiskController(
            RiskQueueService riskQueueService, TransferService transferService,
            IdempotencyService idempotencyService, RiskExplanationService riskExplanationService
    ) {
        this.riskQueueService = riskQueueService;
        this.transferService = transferService;
        this.idempotencyService = idempotencyService;
        this.riskExplanationService = riskExplanationService;
    }

    @GetMapping("/admin/risk/queue")
    public RiskQueueResponse queue() {
        return riskQueueService.queue();
    }

    @PostMapping("/admin/risk/{transferId}/decision")
    public ResponseEntity<String> decide(
            @PathVariable long transferId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody RiskDecisionRequest request
    ) {
        RiskDecisionCommand command = new RiskDecisionCommand(transferId, request.decision(), request.reason());
        return idempotencyService.execute(null, idempotencyKey, command, () -> {
                    TransferResponse result = transferService.decideHeld(transferId, request.decision(), "ADMIN");
                    return new ActionResult(HttpStatus.OK, result, result.id());
                }
        ).toResponseEntity();
    }

    @PostMapping("/admin/risk/explain")
    public ResponseEntity<String> explainFlags(@RequestHeader("Idempotency-Key") UUID idempotencyKey) {
        return idempotencyService.execute(null, idempotencyKey, Map.of("action", "EXPLAIN_FLAGS"),
                () -> new ActionResult(HttpStatus.OK,
                        Map.of("explained", riskExplanationService.explainPendingFlags()), null
                )
        ).toResponseEntity();
    }
}