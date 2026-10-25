package com.wallet.wallet_ledger.risk;

import com.wallet.wallet_ledger.transfer.TransferType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates the active fraud rules. Called from inside the transfer transaction,
 * after the sender's account row is locked, so counts can't race.
 */
@Service
public class RiskService {

    private final JdbcTemplate jdbc;

    public RiskService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<RuleHit> evaluate(TransferType type, long fromAccountId, long toAccountId, long amount) {
        List<RiskRule> rules = jdbc.query("""
                SELECT id, code, action, threshold_count, window_minutes,
                       amount_multiplier, lookback_days, threshold_amount
                FROM risk_rules WHERE is_active ORDER BY id
                """,
                (rs, rowNum) -> new RiskRule(
                        rs.getLong("id"), rs.getString("code"), rs.getString("action"),
                        rs.getObject("threshold_count", Integer.class),
                        rs.getObject("window_minutes", Integer.class),
                        rs.getBigDecimal("amount_multiplier"),
                        rs.getObject("lookback_days", Integer.class),
                        rs.getObject("threshold_amount", Long.class)));

        List<RuleHit> hits = new ArrayList<>();
        for (RiskRule rule : rules) {
            boolean fired = switch (rule.code()) {
                case "VELOCITY" -> velocity(fromAccountId, rule);
                case "AMOUNT_SPIKE" -> amountSpike(fromAccountId, amount, rule);
                case "NEW_PAYEE_LARGE_AMOUNT" ->
                        type == TransferType.PEER_TRANSFER && newPayeeLargeAmount(fromAccountId, toAccountId, amount, rule);
                default -> false;
            };
            if (fired) {
                hits.add(new RuleHit(rule.id(), rule.code(), rule.action()));
            }
        }
        return hits;
    }

    /** More than N outgoing transfers in the window: N already exist, so this one would be N+1. */
    private boolean velocity(long fromAccountId, RiskRule rule) {
        Long recent = jdbc.queryForObject("""
                SELECT COUNT(*) FROM transfers
                WHERE from_account_id = ?
                  AND type IN ('PEER_TRANSFER', 'WITHDRAWAL')
                  AND created_at > now() - make_interval(mins => ?)
                """, Long.class, fromAccountId, rule.windowMinutes());
        return recent >= rule.thresholdCount();
    }

    /** Amount over multiplier x the wallet's average completed outgoing transfer in the lookback window. */
    private boolean amountSpike(long fromAccountId, long amount, RiskRule rule) {
        BigDecimal average = jdbc.queryForObject("""
                SELECT AVG(amount) FROM transfers
                WHERE from_account_id = ?
                  AND type IN ('PEER_TRANSFER', 'WITHDRAWAL')
                  AND status = 'COMPLETED'
                  AND created_at > now() - make_interval(days => ?)
                """, BigDecimal.class, fromAccountId, rule.lookbackDays());
        return average != null
                && BigDecimal.valueOf(amount).compareTo(average.multiply(rule.amountMultiplier())) > 0;
    }

    /** First completed transfer to this recipient, above the threshold. */
    private boolean newPayeeLargeAmount(long fromAccountId, long toAccountId, long amount, RiskRule rule) {
        if (amount <= rule.thresholdAmount()) {
            return false;
        }
        Boolean paidBefore = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM transfers
                    WHERE from_account_id = ? AND to_account_id = ? AND status = 'COMPLETED'
                )
                """, Boolean.class, fromAccountId, toAccountId);
        return !Boolean.TRUE.equals(paidBefore);
    }

    private record RiskRule(long id, String code, String action, Integer thresholdCount, Integer windowMinutes,
                            BigDecimal amountMultiplier, Integer lookbackDays, Long thresholdAmount) {
    }
}