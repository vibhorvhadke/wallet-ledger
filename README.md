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