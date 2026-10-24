## Case 1: Read Committed

```
-- Session A
wallet=# BEGIN;
BEGIN
wallet=*# SHOW transaction_isolation;
 transaction_isolation
-----------------------
 read committed
(1 row)

wallet=*# SELECT balance FROM lab_wallets WHERE id = 1;
 balance
---------
   50000
(1 row)

-- Session B
wallet=# UPDATE lab_wallets SET balance = balance - 10000 WHERE id = 1;
UPDATE 1

-- Session A
wallet=*# SELECT balance FROM lab_wallets WHERE id = 1;
 balance
---------
   40000
(1 row)

wallet=*# COMMIT;
COMMIT
```

**Result:** Session A saw 50000, then 40000 inside the same transaction.

**Why:** Under Read Committed, each statement takes a fresh snapshot of committed data. Session B's commit happened between A's two reads, so the second read saw it. This is a **non-repeatable read**.


## Case 2: Repeatable Read

```
-- Session A
wallet=# BEGIN ISOLATION LEVEL REPEATABLE READ;
BEGIN
wallet=*# SHOW transaction_isolation;
 transaction_isolation
-----------------------
 repeatable read
(1 row)

wallet=*# SELECT balance FROM lab_wallets WHERE id = 1;
 balance
---------
   50000
(1 row)

-- Session B
wallet=# UPDATE lab_wallets SET balance = balance - 10000 WHERE id = 1;
UPDATE 1

-- Session A
wallet=*# SELECT balance FROM lab_wallets WHERE id = 1;
 balance
---------
   50000
(1 row)

wallet=*# COMMIT;
COMMIT
wallet=# SELECT balance FROM lab_wallets WHERE id = 1;
 balance
---------
   40000
(1 row)
```

**Result:** Inside the transaction, Session A saw 50000 both times. After COMMIT, it saw 40000.

**Why:** Under Repeatable Read, the transaction takes one snapshot at its first query and uses it until the end. Changes committed by other sessions after that point are invisible until the transaction ends.

## Case 3: Serializable

```
-- Session A
wallet=# BEGIN ISOLATION LEVEL SERIALIZABLE;
BEGIN
wallet=*# SELECT balance FROM lab_wallets WHERE id = 1;
 balance
---------
   50000
(1 row)

-- Session B
wallet=# BEGIN ISOLATION LEVEL SERIALIZABLE;
BEGIN
wallet=*# SELECT balance FROM lab_wallets WHERE id = 1;
 balance
---------
   50000
(1 row)

-- Session A (application computed 50000 - 10000)
wallet=*# UPDATE lab_wallets SET balance = 40000 WHERE id = 1;
UPDATE 1

-- Session B (same calculation; waits for A's row lock)
wallet=*# UPDATE lab_wallets SET balance = 40000 WHERE id = 1;

-- Session A
wallet=*# COMMIT;
COMMIT

-- Session B (error appears as soon as A commits)
ERROR:  could not serialize access due to concurrent update
wallet=!# ROLLBACK;
ROLLBACK
wallet=# SELECT balance FROM lab_wallets WHERE id = 1;
 balance
---------
   40000
(1 row)
```

**Result:** Session B's write was rejected with SQLSTATE 40001 (`serialization_failure`). The final balance is 40000, from Session A only.

**Why:** Under Serializable, PostgreSQL aborts a transaction if committing it would give a result that no one-at-a-time order of the two transactions could produce. Without this, both sessions would "withdraw" ₹100 but only one deduction would be saved (a lost update). The rejected transaction must be retried by the application.

## Case 4: Repeatable Read with a join

```
-- Session A
wallet=# BEGIN ISOLATION LEVEL REPEATABLE READ;
BEGIN
wallet=*# SELECT u.name, w.balance FROM lab_users u JOIN lab_wallets w ON w.user_id = u.id ORDER BY u.id;
 name  | balance
-------+---------
 Asha  |   50000
 Rahul |   50000
(2 rows)

-- Session B
wallet=# UPDATE lab_users SET name = 'Asha K' WHERE id = 1;
UPDATE 1
wallet=# UPDATE lab_wallets SET balance = balance - 10000 WHERE id = 1;
UPDATE 1
wallet=# INSERT INTO lab_users VALUES (3, 'Priya');
INSERT 0 1
wallet=# INSERT INTO lab_wallets VALUES (3, 3, 70000);
INSERT 0 1

-- Session A
wallet=*# SELECT u.name, w.balance FROM lab_users u JOIN lab_wallets w ON w.user_id = u.id ORDER BY u.id;
 name  | balance
-------+---------
 Asha  |   50000
 Rahul |   50000
(2 rows)

wallet=*# COMMIT;
COMMIT
wallet=# SELECT u.name, w.balance FROM lab_users u JOIN lab_wallets w ON w.user_id = u.id ORDER BY u.id;
  name  | balance
--------+---------
 Asha K |   40000
 Rahul  |   50000
 Priya  |   70000
(3 rows)
```

**Result:** Inside the transaction, the join returned the same 2 rows, with no name change, no balance change and no new row. After COMMIT, all of Session B's changes appeared.

**Why:** The Repeatable Read snapshot covers every table the transaction reads, not just one. In PostgreSQL it also hides rows inserted after the snapshot (phantoms), which is stricter than the SQL standard requires for Repeatable Read.

## Summary

| Level | Re-reading a changed row | New rows from others | Two sessions writing based on the same read |
|---|---|---|---|
| Read Committed | Sees the new value | Visible | Second write overwrites the first (lost update) |
| Repeatable Read | Sees the old value (snapshot) | Hidden | Second write fails with a serialization error |
| Serializable | Sees the old value (snapshot) | Hidden | Second write fails with a serialization error; also catches conflicts across different rows |