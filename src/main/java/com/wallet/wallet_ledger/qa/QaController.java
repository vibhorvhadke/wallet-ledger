package com.wallet.wallet_ledger.qa;

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

import java.util.UUID;

@RestController
public class QaController {

    private final StatementQaService qaService;
    private final UserService userService;
    private final IdempotencyService idempotencyService;

    public QaController(StatementQaService qaService, UserService userService, IdempotencyService idempotencyService) {
        this.qaService = qaService;
        this.userService = userService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/users/{userId}/statement/ask")
    public ResponseEntity<String> ask(@PathVariable long userId,
                                      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
                                      @Valid @RequestBody StatementQuestionRequest request) {
        userService.requireExists(userId);
        return idempotencyService.execute(userId, idempotencyKey, request,
                () -> new ActionResult(HttpStatus.OK, qaService.ask(userId, request.question()), null)
        ).toResponseEntity();
    }
}