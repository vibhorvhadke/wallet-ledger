package com.wallet.wallet_ledger.user;

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
public class UserController {

    private final UserService userService;
    private final IdempotencyService idempotencyService;

    public UserController(UserService userService, IdempotencyService idempotencyService) {
        this.userService = userService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/users")
    public ResponseEntity<String> createUser(@RequestHeader("Idempotency-Key") UUID idempotencyKey,
                                             @Valid @RequestBody CreateUserRequest request) {
        return idempotencyService.execute(null, idempotencyKey, request,
                () -> new ActionResult(HttpStatus.CREATED, userService.create(request), null)
        ).toResponseEntity();
    }
}