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