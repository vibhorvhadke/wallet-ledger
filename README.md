# Payments-Grade Wallet Ledger

## Milestone 1 — Design questions

### 1. Do we store each account's balance, compute it from ledger entries, or both?

**Both.** `accounts.balance` is updated in the same transaction as the ledger entries. The ledger entries remain the source of truth, and the nightly reconciliation job checks that the two match.

| Option | Benefit | Cost |
|---|---|---|
| Store only | Fast balance reads | Can drift from reality with no way to prove what it should be; no history |
| Compute only | Always correct by definition | `SUM` over a growing table slows every balance read and overdraft check; no single row to lock or to hold a "never negative" `CHECK` |
| **Both (chosen)** | Fast reads; a row to lock with `SELECT … FOR UPDATE`; database `CHECK (balance >= 0)` works; ledger still proves correctness | One extra row update per account per transfer; busy wallets become a hot row; needs a reconciliation job to catch drift |

### 2. Why store user wallets and system accounts in the same table?

- Every ledger entry points to exactly one account, so a single foreign key (`ledger_entries.account_id`) covers both. Two tables would need two nullable foreign keys or a reference with no foreign key at all.
- Top-up, peer transfer and withdrawal use the same code path: lock two account rows, write a debit and a credit.
- Balance, statement and reconciliation queries work the same way for every account.
- The differences (owner, can go negative) are handled by `account_type` and `CHECK` constraints.

### 3. Which columns hold data we don't control, and why is none of them a primary key?

**Columns we don't control:** `users.email`, `users.phone`, `users.name`, and `users.kyc_status` (set by the KYC (Know Your Customer) process).

**Why none of them is a primary key:**
- **They change.** Users update their email or phone; a primary key must never change, because every foreign key pointing to it would have to change too.
- **They get reused.** Telecom operators recycle phone numbers to new people.
- **Formats vary.** `+91 98xxxxxx` and `98xxxxxx` are the same number but different strings.
- **Privacy.** Primary keys appear in URLs (Uniform Resource Locators), logs and foreign keys; spreading email or phone everywhere leaks personal data.
- **Size.** A `BIGINT` key is 8 bytes; long strings make every index and foreign key larger.

So each table uses a system-generated `BIGINT` key, and email and phone get `UNIQUE` constraints instead.

## Milestone 2 — Concurrency

### Step 1: Isolation levels

The two-session lab (Read Committed, Repeatable Read, Serializable, Repeatable Read with a join) is in [docs/isolation.md](docs/isolation.md).

### Step 2: Breaking it first (no locks)

`UnsafeTransferService` reads balances without locks, checks funds in Java, then writes values computed in Java.
`UnsafeTransferLabTest` tops up a wallet with ₹500 and fires 100 concurrent ₹10 transfers.

| Result | Got | Correct |
|---|---|---|
| Transfers succeeded | 100 | 50 |
| Transfers rejected | 0 | 50 |
| Sender stored balance (paise) | 32000 | 0 |
| Sender ledger balance (paise) | -50000 | 0 |
| Receiver stored balance (paise) | 19000 | 50000 |
| Receiver ledger balance (paise) | 100000 | 50000 |

**Overdraft:** all 100 transfers passed the funds check because they read the balance before others had written; the ledger shows the sender at -₹500.
**Lost updates:** concurrent writes overwrote each other, so stored balances no longer match the ledger.
**The `CHECK (balance >= 0)` did not catch it:** each thread wrote a positive value computed from a stale read, so the stored column never went negative.

### Step 3: Fixing it with row locks

`TransferService.transfer()` runs in one transaction:

1. Lock both account rows with `SELECT … FOR UPDATE`, always in ascending `account_id` order, so two transfers between the same pair can never wait on each other in a cycle (deadlock).
2. Re-read the balance while holding the lock, and reject with `InsufficientFundsException` if there isn't enough money (system accounts may go negative).
3. Insert the transfer, its debit and credit entries, the first status-history row, and update stored balances with `balance = balance ± amount`.
4. Commit when the method returns; any exception rolls everything back.

**Stampede test** (`TransferStampedeTest`, `@RepeatedTest(20)`): ₹500 in one wallet, 100 concurrent ₹10 transfers.

| Result | Unsafe (Step 2) | Row locks (Step 3) |
|---|---|---|
| Transfers succeeded | 100 | 50 |
| Transfers rejected | 0 | 50 |
| Sender stored balance (paise) | 32000 | 0 |
| Sender ledger balance (paise) | -50000 | 0 |
| Total debits = total credits | Yes, but balances wrong | Yes |
| Runs passed | — | 20 / 20 |

### Which isolation level, and why

The transfer runs at **Read Committed** (the PostgreSQL default) with row locks.

- **Why it is enough:** the only decision that matters (is there enough money?) is made on rows we have locked. When a transfer waits on `FOR UPDATE` and then gets the lock, PostgreSQL gives it the **latest committed** version of the row, so it always checks the real current balance. No other transaction can change those rows until we commit.
- **Why not Repeatable Read:** a transfer that waited for the lock would fail with a serialization error instead of simply continuing with the new balance, so the application would have to retry.
- **Why not Serializable:** it would also be correct, but on a busy wallet most concurrent transfers would be aborted with SQLSTATE 40001 and need retry loops. That means wasted work, lower throughput, and extra latency under exactly the load we care about, plus overhead for tracking read dependencies. Row locks make transfers on the same wallet **wait in line** instead of failing and retrying.
- **Trade-off:** a very busy wallet becomes a hot row, and transfers on it run one at a time. Transfers on different wallets still run in parallel.

## Milestone 3 — Never charge twice

Every POST requires an `Idempotency-Key` header (UUID). Missing → 400 `MISSING_HEADER`.

**How it works** (`IdempotencyService`, one transaction):

1. Hash the request body with SHA-256.
2. Insert the key first: `INSERT … ON CONFLICT ON CONSTRAINT uq_idempotency_keys_user_key DO NOTHING RETURNING id`.
3. If inserted → run the transfer, save the response code and body on the key row, commit. The key, the transfer and the saved response commit together or not at all.
4. If the key already exists → same hash and completed: return the saved status and body exactly; same hash and in progress: 409; different hash: 422 `IDEMPOTENCY_KEY_REUSED`.

**Concurrent duplicates:** if two requests with the same key arrive together, the second `INSERT` waits on the unique index until the first commits, then finds the completed row and replays it. The unique constraint guarantees only one transfer.

**Scope:** keys are unique per user (the owner of the sending wallet). Requests with no user yet (`POST /users`, admin endpoints) share one NULL scope via `UNIQUE NULLS NOT DISTINCT`.

**Failed requests:** if the transfer fails (e.g. insufficient funds), the whole transaction rolls back, including the key row. A retry runs again and gets the same error; no money moves either time.

**Byte-identical replays:** the response is read back from the stored `JSONB` (`RETURNING response_body::text`), so the first response and every replay are identical.

**Acceptance tests** (`IdempotencyAcceptanceTest`, through real HTTP):

| Test | Result |
|---|---|
| Same request 5 times in a row | 1 transfer, 5 identical 201 responses ✅ |
| Same key 10 times in parallel | 1 transfer, 10 identical responses ✅ |
| Same key, different amount | 201 then 422, no extra money moved ✅ |
| Missing key | 400, no money moved ✅ |

## Milestone 4 — Check the books every night

`ReconciliationService.run()` runs 5 checks as SQL queries:

| Check | What it finds |
|---|---|
| Everything balances | Sum of all debits ≠ sum of all credits |
| Every transfer balances | Transfers whose debits ≠ credits (`GROUP BY … HAVING`) |
| Stored balances are right | `accounts.balance` ≠ balance computed from ledger entries |
| No overdrafts | User wallets with a computed balance below zero |
| Nothing is orphaned | Completed or reversed transfers with no entries (HELD and REJECTED move no money, so they're skipped); entries with no transfer |

**Design:**
- **One `REPEATABLE READ` transaction:** all checks see the same snapshot, so transfers made during the run can't make checks disagree.
- **One run at a time:** the run starts with `SELECT … FOR UPDATE NOWAIT` on its `job_locks` row. If another run holds it, PostgreSQL fails immediately with SQLSTATE 55P03, returned as 409 `RECONCILIATION_ALREADY_RUNNING`.
- **Report, never fix:** saves one `reconciliation_runs` row (start, end, status, counts) and one `reconciliation_issues` row per problem.
- **Scheduled** nightly at 2 AM India time (`@Scheduled`), plus `POST /admin/reconciliation/run` and `GET /admin/reconciliation/runs/{id}`.

**Root cause is reported once:** one corrupted entry would otherwise trigger three checks (global total, the transfer, and each account's balance). The global check is reported only if no unbalanced transfer explains it, and accounts belonging to an unbalanced transfer are reported through that transfer, not again in the balance checks. The transfer's accounts are read from `transfers.from_account_id` / `to_account_id` (migration V5), so this still works if an entry was deleted.

**Acceptance test** (`ReconciliationAcceptanceTest`): seed 10,000 transfers, run the job (PASSED, 0 issues), then corrupt the data directly:

| Corruption | Reported as |
|---|---|
| One credit's amount changed 100 → 150 | `TRANSFER_BALANCED` on that transfer |
| One credit deleted | `TRANSFER_BALANCED` on that transfer |
| One stored balance +777 | `STORED_BALANCE_MATCHES` on that account |

Result: exactly these 3 issues, nothing else. ✅

### Performance (1,000,000 ledger entries)

Seeded with `scripts/seed.sql -v transfers=500000` (500,000 transfers, 1,000,000 entries), then `EXPLAIN (ANALYZE, BUFFERS)` on each check:

| Check | Plan | Time |
|---|---|---|
| 1. Everything balances | Parallel Seq Scan + aggregate | 57 ms |
| 2. Every transfer balances | Index Scan on `idx_ledger_entries_transfer` + GroupAggregate | 542 ms |
| 3. Stored balances right | Nested loop: accounts → `idx_ledger_entries_account_created` | 1,108 ms |
| 4. No overdrafts | Same as 3, plus the unbalanced-transfers CTE | 1,243 ms |
| 5a. Transfers with no entries | Parallel Hash Right Anti Join (seq scans) | 215 ms |
| 5b. Entries with no transfer | Parallel Hash Anti Join (seq scans) | 230 ms |
| **Total** | | **≈ 3.4 s** |

**Which checks can afford a full scan?** All of them. Every check must examine every row (a total, a balance per transfer, a balance per account, or "has no partner"), so no index can avoid reading the whole ledger. For a nightly job, a few seconds is fine. **No index was added for reconciliation**: the planner reuses indexes that already exist for API queries (`(transfer_id)` for `GET /transfers/{id}`, `(account_id, created_at, id)` for statements) where they help it skip a sort, and uses parallel sequential scans with hash anti joins for the orphan checks, which is cheaper than 500,000 index lookups.

**Observations:**
- In Check 3, the `NOT IN (affected_accounts)` part was `never executed`: with clean data no account mismatches, so the root-cause exclusion costs nothing.
- The planner over-estimates unbalanced transfers (estimated 489,762, actual 0) because it can't predict a `HAVING` result. That inflated cost triggers JIT compilation (165–265 ms of overhead).
- **What I'd do next:** Checks 3 and 4 recompute the unbalanced transfers that Check 2 already found. Passing Check 2's result to them would avoid reading the ledger a second time (about 450 ms saved in Check 4). If the ledger grows to hundreds of millions of rows, checks could run incrementally (only entries since the last run, plus stored running totals).

## Statement pagination

`GET /wallets/{id}/statement` orders by `created_at DESC, id DESC` (`id` breaks ties so the order is stable) and uses the index `(account_id, created_at DESC, id DESC)`.

Measured on a wallet with 150,457 entries (1.3 million entries in total):

| Query | Plan | Rows produced | Buffers | Temp files | Time |
|---|---|---|---|---|---|
| `LIMIT 20 OFFSET 0` | Index Scan + nested loop | 20 | 106 | No | 0.49 ms |
| `LIMIT 20 OFFSET 100000` | Bitmap scan of all 150k entries + hash join + external merge sort | 100,020 (100,000 discarded) | 12,323 | ≈13 MB | 155 ms |
| Cursor: `(created_at, id) < (…)` `LIMIT 20` | Index Scan with row comparison + nested loop | 20 | 103 | No | 0.2 ms |

**Why OFFSET gets slow:** the database cannot jump to row 100,001; it must produce, join and sort every earlier row and then throw them away, so cost grows with the page number. At this depth the planner abandons the index entirely and sorts all 150,000 rows, spilling to disk. OFFSET also shows duplicates or skips rows if transfers arrive while the user is paging.

**Cursor pagination:** the client passes the `(created_at, id)` of the last row it saw. The row comparison matches the index order exactly, so PostgreSQL seeks straight to that position and reads only 20 rows. Cost is constant at any depth, and new rows don't shift pages.

**API:** `GET /wallets/{id}/statement?limit=20` returns `next_cursor` when more rows exist. Pass it back as `?cursor=…` to get the next page; `next_cursor` is `null` on the last page. The cursor is the last row's `(created_at, id)`, Base64 URL-encoded so clients treat it as opaque. The original `offset` parameter still works, but `offset` and `cursor` cannot be combined (400). The service fetches `limit + 1` rows to know whether a next page exists. `StatementPaginationTest` walks a 45-entry wallet with both methods and checks they return the same rows in the same order, with no gaps or duplicates.

## Milestone 5 — Fraud rules

### Part A: Rules inside the transfer

Thresholds live in `risk_rules`, not code. `RiskService` evaluates the active rules inside the transfer transaction, **after** the sender's row is locked (so the velocity count can't race) and **after** the funds check.

| Rule | Fires when | Action |
|---|---|---|
| `VELOCITY` | More than 5 outgoing transfers from one wallet in 10 minutes | Hold |
| `AMOUNT_SPIKE` | Amount over 5× the wallet's 30-day average completed transfer | Hold |
| `NEW_PAYEE_LARGE_AMOUNT` | First completed transfer to this recipient, above ₹10,000 | Flag, allow |

- **Held** → transfer saved as `HELD`, **no ledger entries, no balance change**, API returns **202**. Each fired rule gets a `risk_flags` row; the audit log records `null → HELD`.
- Rules apply to money leaving a user wallet (peer transfers, withdrawals). Top-ups and reversals skip them; the new-payee rule applies only to peer transfers. Held transfers still count toward velocity.
- **Admin decisions** (`POST /admin/risk/{id}/decision`): `RELEASE` locks the transfer row, then both accounts, **re-checks funds**, writes the entries and sets `COMPLETED`; `REJECT` sets `REJECTED` with no money ever moving. Both are recorded in the audit log by `ADMIN`. `GET /admin/risk/queue` lists held transfers (oldest first) and recently flagged ones.
- Tests: `FraudRulesTest` (each rule, release, reject, decide only once).

### Index per rule and time added to a transfer

Measured with `EXPLAIN ANALYZE` on 650,000 transfers (sender with ≈250 transfers):

| Rule | Index | Before (no index) | After |
|---|---|---|---|
| Velocity | `idx_transfers_from_created (from_account_id, created_at)` | 35 ms, parallel seq scan | 0.68 ms, index scan |
| Amount spike | `idx_transfers_from_created (from_account_id, created_at)` | 30 ms, parallel seq scan | 2.05 ms, bitmap index scan (114 rows) |
| New payee | `idx_transfers_from_to (from_account_id, to_account_id)` | 24 ms, parallel seq scan | 2.12 ms, index scan |
| **Added per transfer** | | **≈ 90 ms** | **≈ 5 ms** |

Without indexes, each rule read the whole `transfers` table, so the cost grew with total volume and was paid **while the sender's row was locked**. With the indexes, cost depends only on one wallet's history. Trade-off: each new transfer updates two more indexes, which is worth it because both are read on every transfer.