package com.wallet.wallet_ledger.account;

import com.wallet.wallet_ledger.idempotency.ActionResult;
import com.wallet.wallet_ledger.idempotency.IdempotencyService;
import com.wallet.wallet_ledger.user.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

@RestController
public class AccountController {

    private final AccountService accountService;
    private final UserService userService;
    private final IdempotencyService idempotencyService;

    public AccountController(AccountService accountService, UserService userService,
                             IdempotencyService idempotencyService) {
        this.accountService = accountService;
        this.userService = userService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/users/{userId}/wallets")
    public ResponseEntity<String> openWallet(@PathVariable long userId,
                                             @RequestHeader("Idempotency-Key") UUID idempotencyKey,
                                             @Valid @RequestBody CreateWalletRequest request) {
        userService.requireExists(userId); // 404 before touching idempotency_keys (it has a foreign key to users)
        return idempotencyService.execute(userId, idempotencyKey, request,
                () -> new ActionResult(HttpStatus.CREATED, accountService.createWallet(userId, request), null)
        ).toResponseEntity();
    }

    @GetMapping("/wallets/{walletId}/balance")
    public BalanceResponse getBalance(@PathVariable long walletId) {
        return accountService.getBalance(walletId);
    }

    @GetMapping("/wallets/{walletId}/statement")
    public StatementResponse getStatement(@PathVariable long walletId,
                                          @RequestParam(defaultValue = "20") int limit,
                                          @RequestParam(defaultValue = "0") int offset) {
        return accountService.getStatement(walletId, limit, offset);
    }
}