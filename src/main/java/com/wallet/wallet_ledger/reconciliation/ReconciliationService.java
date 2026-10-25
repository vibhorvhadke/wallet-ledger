package com.wallet.wallet_ledger.reconciliation;

import com.wallet.wallet_ledger.common.ApiException;
import org.springframework.dao.DataAccessException;
import java.sql.SQLException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ReconciliationService {

    private static final int CHECKS_RUN = 5;

    /** Transfers whose debits != credits. Shared by several checks. */
    private static final String UNBALANCED_TRANSFERS_CTE = """
            unbalanced_transfers AS (
                SELECT transfer_id FROM ledger_entries
                GROUP BY transfer_id
                HAVING COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0)
                    <> COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
            ),
               affected_accounts AS (
                   SELECT from_account_id AS account_id FROM transfers
                   WHERE id IN (SELECT transfer_id FROM unbalanced_transfers)
                   UNION
                   SELECT to_account_id FROM transfers
                   WHERE id IN (SELECT transfer_id FROM unbalanced_transfers)
               )
            """;

    private final JdbcTemplate jdbc;

    public ReconciliationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Runs all checks in ONE REPEATABLE READ transaction, so they all see the same snapshot.
     * REQUIRES_NEW: always its own transaction, even when called from the idempotency transaction.
     * Reports problems; never fixes them.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public ReconciliationRunResponse run() {
        // 1. Only one run at a time: fail immediately if another run holds the lock
        try {
            jdbc.queryForObject(
                    "SELECT id FROM job_locks WHERE job_name = 'RECONCILIATION' FOR UPDATE NOWAIT", Long.class);
        } catch (DataAccessException e) {
            if (isLockNotAvailable(e)) {
                throw new ApiException(HttpStatus.CONFLICT, "RECONCILIATION_ALREADY_RUNNING",
                        "Another reconciliation run is in progress");
            }
            throw e;
        }


        // 2. Record the run
        long runId = jdbc.queryForObject(
                "INSERT INTO reconciliation_runs (status) VALUES ('RUNNING') RETURNING id", Long.class);

        // 3. Run the checks (all on the same snapshot)
        List<ReconciliationIssue> issues = new ArrayList<>();
        List<ReconciliationIssue> unbalancedTransfers = checkEveryTransferBalances();
        issues.addAll(checkEverythingBalances(unbalancedTransfers.isEmpty()));
        issues.addAll(unbalancedTransfers);
        issues.addAll(checkStoredBalances());
        issues.addAll(checkNoOverdrafts());
        issues.addAll(checkNoOrphans());

        // 4. Save one row per problem
        jdbc.batchUpdate("""
                INSERT INTO reconciliation_issues
                    (reconciliation_run_id, check_name, entity_type, entity_id, expected_value, actual_value)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                issues.stream().map(i -> new Object[]{
                        runId, i.checkName(), i.entityType(), i.entityId(), i.expected(), i.actual()}).toList());

        // 5. Finish the run (clock_timestamp() = real time now; now() would be the transaction start)
        jdbc.update("""
                UPDATE reconciliation_runs
                SET ended_at = clock_timestamp(), status = ?, checks_run = ?, issues_found = ?
                WHERE id = ?
                """, issues.isEmpty() ? "PASSED" : "FAILED", CHECKS_RUN, issues.size(), runId);
        jdbc.update("UPDATE job_locks SET last_run_at = clock_timestamp() WHERE job_name = 'RECONCILIATION'");

        return getRun(runId);
    }

    // ---------- Check 1: everything balances ----------
    // Reported only if no unbalanced transfer explains it (root cause is reported once, in Check 2).
    private List<ReconciliationIssue> checkEverythingBalances(boolean reportIfUnbalanced) {
        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0)  AS total_debits,
                       COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0) AS total_credits
                FROM ledger_entries
                """);
        long debits = ((Number) totals.get("total_debits")).longValue();
        long credits = ((Number) totals.get("total_credits")).longValue();
        if (debits != credits && reportIfUnbalanced) {
            return List.of(new ReconciliationIssue("LEDGER_BALANCED", "LEDGER", null,
                    "credits=" + debits, "credits=" + credits));
        }
        return List.of();
    }

    // ---------- Check 2: every transfer balances ----------
    private List<ReconciliationIssue> checkEveryTransferBalances() {
        return jdbc.query("""
                SELECT transfer_id,
                       COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0)  AS debits,
                       COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0) AS credits
                FROM ledger_entries
                GROUP BY transfer_id
                HAVING COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0)
                    <> COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
                ORDER BY transfer_id
                """,
                (rs, rowNum) -> new ReconciliationIssue("TRANSFER_BALANCED", "TRANSFER", rs.getLong("transfer_id"),
                        "credits=" + rs.getLong("debits"), "credits=" + rs.getLong("credits")));
    }

    // ---------- Check 3: stored balances match the ledger ----------
    private List<ReconciliationIssue> checkStoredBalances() {
        return jdbc.query("WITH " + UNBALANCED_TRANSFERS_CTE + """
                , computed AS (
                    SELECT a.id, a.balance AS stored,
                           COALESCE(SUM(CASE WHEN le.direction = 'CREDIT' THEN le.amount ELSE -le.amount END), 0) AS computed
                    FROM accounts a
                    LEFT JOIN ledger_entries le ON le.account_id = a.id
                    GROUP BY a.id, a.balance
                )
                SELECT id, computed, stored
                FROM computed
                WHERE stored <> computed
                  AND id NOT IN (SELECT account_id FROM affected_accounts)
                ORDER BY id
                """,
                (rs, rowNum) -> new ReconciliationIssue("STORED_BALANCE_MATCHES", "ACCOUNT", rs.getLong("id"),
                        String.valueOf(rs.getLong("computed")), String.valueOf(rs.getLong("stored"))));
    }

    // ---------- Check 4: no user wallet below zero ----------
    private List<ReconciliationIssue> checkNoOverdrafts() {
        return jdbc.query("WITH " + UNBALANCED_TRANSFERS_CTE + """
                SELECT a.id,
                       COALESCE(SUM(CASE WHEN le.direction = 'CREDIT' THEN le.amount ELSE -le.amount END), 0) AS computed
                FROM accounts a
                LEFT JOIN ledger_entries le ON le.account_id = a.id
                WHERE a.account_type = 'USER_WALLET'
                  AND a.id NOT IN (SELECT account_id FROM affected_accounts)
                GROUP BY a.id
                HAVING COALESCE(SUM(CASE WHEN le.direction = 'CREDIT' THEN le.amount ELSE -le.amount END), 0) < 0
                ORDER BY a.id
                """,
                (rs, rowNum) -> new ReconciliationIssue("NO_OVERDRAFT", "ACCOUNT", rs.getLong("id"),
                        ">=0", String.valueOf(rs.getLong("computed"))));
    }

    // ---------- Check 5: nothing orphaned ----------
    private List<ReconciliationIssue> checkNoOrphans() {
        List<ReconciliationIssue> issues = new ArrayList<>(jdbc.query("""
                SELECT t.id FROM transfers t
                WHERE t.status IN ('COMPLETED', 'REVERSED')
                  AND NOT EXISTS (SELECT 1 FROM ledger_entries le WHERE le.transfer_id = t.id)
                ORDER BY t.id
                """,
                (rs, rowNum) -> new ReconciliationIssue("NO_ORPHANS", "TRANSFER", rs.getLong("id"),
                        "has ledger entries", "no ledger entries")));
        issues.addAll(jdbc.query("""
                SELECT le.id, le.transfer_id FROM ledger_entries le
                LEFT JOIN transfers t ON t.id = le.transfer_id
                WHERE t.id IS NULL
                ORDER BY le.id
                """,
                (rs, rowNum) -> new ReconciliationIssue("NO_ORPHANS", "LEDGER_ENTRY", rs.getLong("id"),
                        "transfer " + rs.getLong("transfer_id") + " exists", "transfer missing")));
        return issues;
    }

    /** PostgreSQL reports a failed FOR UPDATE NOWAIT with SQLSTATE 55P03 (lock_not_available). */
    private static boolean isLockNotAvailable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && "55P03".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    // ---------- Read a saved run ----------
    public ReconciliationRunResponse getRun(long runId) {
        List<ReconciliationRunResponse.Issue> issues = jdbc.query("""
                SELECT id, check_name, entity_type, entity_id, expected_value, actual_value
                FROM reconciliation_issues WHERE reconciliation_run_id = ? ORDER BY id
                """,
                (rs, rowNum) -> new ReconciliationRunResponse.Issue(
                        rs.getLong("id"), rs.getString("check_name"), rs.getString("entity_type"),
                        rs.getObject("entity_id", Long.class), rs.getString("expected_value"),
                        rs.getString("actual_value")),
                runId);

        List<ReconciliationRunResponse> runs = jdbc.query("""
                SELECT id, started_at, ended_at, status, checks_run, issues_found
                FROM reconciliation_runs WHERE id = ?
                """,
                (rs, rowNum) -> new ReconciliationRunResponse(
                        rs.getLong("id"), rs.getObject("started_at", OffsetDateTime.class),
                        rs.getObject("ended_at", OffsetDateTime.class), rs.getString("status"),
                        rs.getInt("checks_run"), rs.getInt("issues_found"), issues),
                runId);

        if (runs.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "Reconciliation run " + runId + " not found");
        }
        return runs.get(0);
    }
}