package com.wallet.wallet_ledger.risk;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

/** Read-only view of transfers that need (or got) risk attention. */
@Service
public class RiskQueueService {

    private static final RowMapper<RiskQueueItem> ITEM_MAPPER = (rs, rowNum) -> new RiskQueueItem(
            rs.getLong("id"), rs.getString("type"), rs.getString("status"), rs.getLong("amount"),
            rs.getString("note"), rs.getLong("from_account_id"), rs.getLong("to_account_id"),
            rs.getObject("created_at", OffsetDateTime.class),
            Arrays.asList((String[]) rs.getArray("rules").getArray()));

    private final JdbcTemplate jdbc;

    public RiskQueueService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RiskQueueResponse queue() {
        // Held transfers waiting for a decision, oldest first (uses partial index idx_transfers_held)
        List<RiskQueueItem> held = jdbc.query("""
                SELECT t.id, t.type, t.status, t.amount, t.note, t.from_account_id, t.to_account_id, t.created_at,
                       array_agg(r.code || ':' || f.action_taken ORDER BY r.id) AS rules
                FROM transfers t
                JOIN risk_flags f ON f.transfer_id = t.id
                JOIN risk_rules r ON r.id = f.risk_rule_id
                WHERE t.status = 'HELD'
                GROUP BY t.id
                ORDER BY t.created_at
                LIMIT 100
                """, ITEM_MAPPER);

        // Allowed-but-flagged transfers from the last 7 days, newest first
        List<RiskQueueItem> flagged = jdbc.query("""
                SELECT t.id, t.type, t.status, t.amount, t.note, t.from_account_id, t.to_account_id, t.created_at,
                       array_agg(r.code || ':' || f.action_taken ORDER BY r.id) AS rules
                FROM risk_flags f
                JOIN transfers t ON t.id = f.transfer_id
                JOIN risk_rules r ON r.id = f.risk_rule_id
                WHERE f.action_taken = 'FLAG_ALLOW'
                  AND t.status = 'COMPLETED'
                  AND f.created_at > now() - interval '7 days'
                GROUP BY t.id
                ORDER BY t.created_at DESC
                LIMIT 100
                """, ITEM_MAPPER);

        return new RiskQueueResponse(held, flagged);
    }
}