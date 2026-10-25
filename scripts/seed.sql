-- =========================================================
-- Seed data: 1,000 users, 2,000 wallets, :transfers transfers (default 100,000).
-- Run once on a migrated database:
--   docker exec -i wallet-postgres psql -U wallet -d wallet -v transfers=100000 < scripts/seed.sql
-- =========================================================

\set ON_ERROR_STOP on
\if :{?transfers}
\else
\set transfers 100000
\endif
\timing on

BEGIN;

SELECT setseed(0.42); -- same "random" data on every run

-- 1. 1,000 users
INSERT INTO users (name, email, phone, kyc_status)
SELECT (ARRAY['Asha','Rahul','Priya','Vikram','Neha','Arjun','Sneha','Rohan','Kavya','Aditya'])[1 + g % 10]
           || ' ' ||
       (ARRAY['Sharma','Patel','Iyer','Khan','Reddy','Gupta','Nair','Joshi','Das','Mehta'])[1 + (g / 10) % 10],
       'seed.user' || g || '@example.com',
       '7' || lpad(g::text, 9, '0'),
       CASE WHEN g % 20 = 0 THEN 'PENDING' ELSE 'VERIFIED' END
FROM generate_series(1, 1000) g;

-- 2. 2,000 wallets (Main + Savings per user), numbered 1..2000 for random picking
CREATE TEMP TABLE seed_wallets (idx SERIAL PRIMARY KEY, account_id BIGINT NOT NULL) ON COMMIT DROP;

WITH w AS (
INSERT INTO accounts (user_id, account_type, name, created_at)
SELECT u.id, 'USER_WALLET', n.name, now() - interval '120 days'
FROM users u
    CROSS JOIN (VALUES ('Main'), ('Savings')) AS n(name)
WHERE u.email LIKE 'seed.user%@example.com'
    RETURNING id
    )
INSERT INTO seed_wallets (account_id)
SELECT id FROM w ORDER BY id;

-- 3. One top-up of Rs 10 lakh (100,000,000 paise) per wallet, about 100 days ago
WITH t AS (
INSERT INTO transfers (type, status, amount, note, from_account_id, to_account_id, created_at)
SELECT 'TOPUP', 'COMPLETED', 100000000, 'Seed top-up',
       (SELECT id FROM accounts WHERE account_type = 'SYSTEM' AND name = 'CASH_IN'),
       sw.account_id,
       now() - interval '100 days' + sw.idx * interval '1 second'
FROM seed_wallets sw
    RETURNING id, amount, from_account_id, to_account_id, created_at
    )
INSERT INTO ledger_entries (transfer_id, account_id, direction, amount, created_at)
SELECT id, from_account_id, 'DEBIT', amount, created_at FROM t
UNION ALL
SELECT id, to_account_id, 'CREDIT', amount, created_at FROM t;

-- 4. The remaining transfers: 90% peer transfers, 10% withdrawals, Rs 1 to Rs 500, last 90 days
WITH picks AS (
    SELECT g,
           1 + floor(random() * 2000)::int     AS from_idx,
        1 + floor(random() * 1999)::int     AS step,      -- 1..1999, so the receiver is never the sender
        random()                            AS kind,
           100 + floor(random() * 49900)::bigint AS amount,
        now() - random() * interval '90 days' AS created_at
FROM generate_series(1, :transfers - 2000) g
    ),
    planned AS (
SELECT p.g, p.amount, p.created_at,
    f.account_id AS from_account,
    CASE WHEN p.kind < 0.1
    THEN (SELECT id FROM accounts WHERE account_type = 'SYSTEM' AND name = 'CASH_OUT')
    ELSE r.account_id END AS to_account,
    CASE WHEN p.kind < 0.1 THEN 'WITHDRAWAL' ELSE 'PEER_TRANSFER' END AS type
FROM picks p
    JOIN seed_wallets f ON f.idx = p.from_idx
    JOIN seed_wallets r ON r.idx = 1 + (p.from_idx - 1 + p.step) % 2000
    ),
    t AS (
INSERT INTO transfers (type, status, amount, note, from_account_id, to_account_id, created_at)
SELECT type, 'COMPLETED', amount,
    CASE WHEN type = 'WITHDRAWAL' THEN 'ATM withdrawal'
    ELSE (ARRAY['Dinner','Rent share','Groceries','Movie tickets','Cab fare','Gift','Electricity bill','Coffee'])[1 + g % 8]
    END,
    from_account, to_account, created_at
FROM planned
    RETURNING id, amount, from_account_id, to_account_id, created_at
    )
INSERT INTO ledger_entries (transfer_id, account_id, direction, amount, created_at)
SELECT id, from_account_id, 'DEBIT', amount, created_at FROM t
UNION ALL
SELECT id, to_account_id, 'CREDIT', amount, created_at FROM t;

-- 5. Audit row for every transfer that doesn't have one
INSERT INTO transfer_status_history (transfer_id, from_status, to_status, changed_by, changed_at)
SELECT t.id, NULL, 'COMPLETED', 'SEED', t.created_at
FROM transfers t
WHERE NOT EXISTS (SELECT 1 FROM transfer_status_history h WHERE h.transfer_id = t.id);

-- 6. Stored balances = balances computed from the ledger
UPDATE accounts a
SET balance = c.computed
    FROM (
    SELECT account_id, SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END) AS computed
    FROM ledger_entries
    GROUP BY account_id
) c
WHERE a.id = c.account_id;

COMMIT;

-- Refresh planner statistics after a bulk load, so EXPLAIN shows realistic plans
ANALYZE;

-- Summary
SELECT (SELECT COUNT(*) FROM users)                                       AS users,
       (SELECT COUNT(*) FROM accounts WHERE account_type = 'USER_WALLET') AS wallets,
       (SELECT COUNT(*) FROM transfers)                                   AS transfers,
       (SELECT COUNT(*) FROM ledger_entries)                              AS ledger_entries;