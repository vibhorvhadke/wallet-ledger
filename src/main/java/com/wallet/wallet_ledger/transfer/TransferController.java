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

import java.util.UUID;

@RestController
public class TransferController {

    private final TransferService transferService;
    private final AccountService accountService;
    private final IdempotencyService idempotencyService;

    public TransferController(TransferService transferService, AccountService accountService,
                              IdempotencyService idempotencyService) {
        this.transferService = transferService;
        this.accountService = accountService;
        this.idempotencyService = idempotencyService;
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

    private static ActionResult created(TransferResponse transfer) {
        return new ActionResult(HttpStatus.CREATED, transfer, transfer.id());
    }
}