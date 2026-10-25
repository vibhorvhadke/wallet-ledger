package com.wallet.wallet_ledger.user;

import com.wallet.wallet_ledger.common.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
public class UserService {

    private final JdbcTemplate jdbc;

    public UserService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UserResponse create(CreateUserRequest request) {
        String kycStatus = request.kycStatus() == null ? "PENDING" : request.kycStatus();
        try {
            return jdbc.queryForObject("""
                    INSERT INTO users (name, email, phone, kyc_status)
                    VALUES (?, ?, ?, ?)
                    RETURNING id, name, email, phone, kyc_status, created_at
                    """,
                    (rs, rowNum) -> new UserResponse(
                            rs.getLong("id"), rs.getString("name"), rs.getString("email"),
                            rs.getString("phone"), rs.getString("kyc_status"),
                            rs.getObject("created_at", OffsetDateTime.class)),
                    request.name(), request.email(), request.phone(), kycStatus);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "USER_ALREADY_EXISTS",
                    "A user with this email or phone already exists");
        }
    }

    public void requireExists(long userId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM users WHERE id = ?)", Boolean.class, userId);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User " + userId + " not found");
        }
    }
}