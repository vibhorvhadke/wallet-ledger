package com.wallet.wallet_ledger.transfer;

import com.wallet.wallet_ledger.account.AccountService;
import com.wallet.wallet_ledger.idempotency.ActionResult;
import com.wallet.wallet_ledger.idempotency.IdempotencyService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

@RestController
public class TransferController {

    private final TransferService transferService;
    private final AccountService accountService;
    private final IdempotencyService idempotencyService;
    private final TransferQueryService transferQueryService;

    public TransferController(TransferService transferService, AccountService accountService,
                              IdempotencyService idempotencyService, TransferQueryService transferQueryService) {
        this.transferService = transferService;
        this.accountService = accountService;
        this.idempotencyService = idempotencyService;
        this.transferQueryService = transferQueryService;
    }

    @PostMapping("/transfers/topup")
    public ResponseEntity<String> topup(@RequestHeader("Idempotency-Key") UUID idempotencyKey,
                                        @Valid @RequestBody TopupRequest request) {
        long ownerId = accountService.requireUserWalletOwner(request.walletId());
        long cashIn = accountService.systemAccountId("CASH_IN");
        return idempotencyService.execute(ownerId, idempotencyKey, request,
                () -> created(transferService.transfer(TransferType.TOPUP, cashIn, request.walletId(),
                        request.amount(), request.note()))
        ).toResponseEntity();
    }

    @PostMapping("/transfers")
    public ResponseEntity<String> peerTransfer(@RequestHeader("Idempotency-Key") UUID idempotencyKey,
                                               @Valid @RequestBody PeerTransferRequest request) {
        long ownerId = accountService.requireUserWalletOwner(request.fromWalletId());
        accountService.requireUserWalletOwner(request.toWalletId());
        return idempotencyService.execute(ownerId, idempotencyKey, request,
                () -> created(transferService.transfer(TransferType.PEER_TRANSFER, request.fromWalletId(),
                        request.toWalletId(), request.amount(), request.note()))
        ).toResponseEntity();
    }

    @PostMapping("/transfers/withdraw")
    public ResponseEntity<String> withdraw(@RequestHeader("Idempotency-Key") UUID idempotencyKey,
                                           @Valid @RequestBody WithdrawRequest request) {
        long ownerId = accountService.requireUserWalletOwner(request.walletId());
        long cashOut = accountService.systemAccountId("CASH_OUT");
        return idempotencyService.execute(ownerId, idempotencyKey, request,
                () -> created(transferService.transfer(TransferType.WITHDRAWAL, request.walletId(), cashOut,
                        request.amount(), request.note()))
        ).toResponseEntity();
    }

    /** 201 when money moved, 202 when the transfer is held for review. */
    private static ActionResult created(TransferResponse transfer) {
        HttpStatus status = "HELD".equals(transfer.status()) ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        return new ActionResult(status, transfer, transfer.id());
    }

    @GetMapping("/transfers/{transferId}")
    public TransferDetailsResponse getTransfer(@PathVariable long transferId) {
        return transferQueryService.getTransfer(transferId);
    }

    @PostMapping("/transfers/{transferId}/reverse")
    public ResponseEntity<String> reverse(@PathVariable long transferId,
                                          @RequestHeader("Idempotency-Key") UUID idempotencyKey,
                                          @Valid @RequestBody(required = false) ReverseRequest request) {
        String reason = (request == null) ? null : request.reason();
        ReversalCommand command = new ReversalCommand(transferId, reason);
        // Admin-style operation: no user scope (NULL), like other admin endpoints
        return idempotencyService.execute(null, idempotencyKey, command,
                () -> created(transferService.reverse(transferId, reason, "ADMIN"))
        ).toResponseEntity();
    }
}