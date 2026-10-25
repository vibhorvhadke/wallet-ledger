package com.wallet.wallet_ledger.transfer;

import com.wallet.wallet_ledger.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/** Read-only queries for transfers (kept separate from the money path in TransferService). */
@Service
public class TransferQueryService {

    private final JdbcTemplate jdbc;

    public TransferQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TransferDetailsResponse getTransfer(long transferId) {
        List<TransferDetailsResponse.Entry> entries = jdbc.query("""
                SELECT id, account_id, direction, amount, created_at
                FROM ledger_entries WHERE transfer_id = ? ORDER BY id
                """,
                (rs, rowNum) -> new TransferDetailsResponse.Entry(
                        rs.getLong("id"), rs.getLong("account_id"), rs.getString("direction"),
                        rs.getLong("amount"), rs.getObject("created_at", OffsetDateTime.class)),
                transferId);

        List<TransferDetailsResponse.StatusChange> history = jdbc.query("""
                SELECT from_status, to_status, changed_by, changed_at
                FROM transfer_status_history WHERE transfer_id = ? ORDER BY changed_at, id
                """,
                (rs, rowNum) -> new TransferDetailsResponse.StatusChange(
                        rs.getString("from_status"), rs.getString("to_status"),
                        rs.getString("changed_by"), rs.getObject("changed_at", OffsetDateTime.class)),
                transferId);

        List<TransferDetailsResponse> found = jdbc.query("""
                SELECT id, type, status, amount, note, reversal_of_transfer_id, created_at
                FROM transfers WHERE id = ?
                """,
                (rs, rowNum) -> new TransferDetailsResponse(
                        rs.getLong("id"), rs.getString("type"), rs.getString("status"),
                        rs.getLong("amount"), rs.getString("note"),
                        rs.getObject("reversal_of_transfer_id", Long.class),
                        rs.getObject("created_at", OffsetDateTime.class),
                        entries, history),
                transferId);

        if (found.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer " + transferId + " not found");
        }
        return found.get(0);
    }
}