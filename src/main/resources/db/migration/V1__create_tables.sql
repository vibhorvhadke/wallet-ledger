-- =========================================================
-- V1: Tables and constraints for the wallet ledger
-- Money is stored as whole paise in BIGINT columns.
-- =========================================================

CREATE TABLE users (
                       id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                       name        VARCHAR(100) NOT NULL,
                       email       VARCHAR(255) NOT NULL,
                       phone       VARCHAR(15)  NOT NULL,
                       kyc_status  VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
                       created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
                       CONSTRAINT uq_users_email UNIQUE (email),
                       CONSTRAINT uq_users_phone UNIQUE (phone),
                       CONSTRAINT chk_users_kyc_status CHECK (kyc_status IN ('PENDING', 'VERIFIED', 'REJECTED'))
);

CREATE TABLE accounts (
                          id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                          user_id       BIGINT REFERENCES users (id),
                          account_type  VARCHAR(20) NOT NULL,
                          name          VARCHAR(50) NOT NULL,
                          currency      CHAR(3)     NOT NULL DEFAULT 'INR',
                          balance       BIGINT      NOT NULL DEFAULT 0,
                          created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                          CONSTRAINT chk_accounts_type CHECK (account_type IN ('USER_WALLET', 'SYSTEM')),
                          CONSTRAINT chk_accounts_owner CHECK (
                              (account_type = 'USER_WALLET' AND user_id IS NOT NULL)
                                  OR (account_type = 'SYSTEM' AND user_id IS NULL)
                              ),
                          CONSTRAINT chk_accounts_currency CHECK (currency = 'INR'),
                          CONSTRAINT chk_accounts_balance_non_negative CHECK (account_type = 'SYSTEM' OR balance >= 0)
);

-- Exactly one CASH_IN, CASH_OUT and FEE_REVENUE system account
CREATE UNIQUE INDEX uq_accounts_system_name ON accounts (name) WHERE account_type = 'SYSTEM';

CREATE TABLE transfers (
                           id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                           type                     VARCHAR(20)  NOT NULL,
                           status                   VARCHAR(20)  NOT NULL,
                           amount                   BIGINT       NOT NULL,
                           note                     VARCHAR(255),
                           reversal_of_transfer_id  BIGINT REFERENCES transfers (id),
                           created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
                           CONSTRAINT chk_transfers_amount_positive CHECK (amount > 0),
                           CONSTRAINT chk_transfers_type CHECK (type IN ('TOPUP', 'PEER_TRANSFER', 'WITHDRAWAL', 'FEE', 'REVERSAL')),
                           CONSTRAINT chk_transfers_status CHECK (status IN ('COMPLETED', 'HELD', 'REJECTED', 'REVERSED')),
                           CONSTRAINT uq_transfers_reversal_of UNIQUE (reversal_of_transfer_id),
                           CONSTRAINT chk_transfers_reversal_link CHECK ((type = 'REVERSAL') = (reversal_of_transfer_id IS NOT NULL))
);

CREATE TABLE ledger_entries (
                                id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                transfer_id  BIGINT      NOT NULL REFERENCES transfers (id),
                                account_id   BIGINT      NOT NULL REFERENCES accounts (id),
                                direction    VARCHAR(6)  NOT NULL,
                                amount       BIGINT      NOT NULL,
                                created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                                CONSTRAINT chk_ledger_entries_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
                                CONSTRAINT chk_ledger_entries_amount_positive CHECK (amount > 0)
);

CREATE TABLE transfer_status_history (
                                         id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                         transfer_id  BIGINT       NOT NULL REFERENCES transfers (id),
                                         from_status  VARCHAR(20),
                                         to_status    VARCHAR(20)  NOT NULL,
                                         changed_by   VARCHAR(100) NOT NULL,
                                         changed_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
                                         CONSTRAINT chk_status_history_from CHECK (from_status IN ('COMPLETED', 'HELD', 'REJECTED', 'REVERSED')),
                                         CONSTRAINT chk_status_history_to   CHECK (to_status   IN ('COMPLETED', 'HELD', 'REJECTED', 'REVERSED'))
);

CREATE TABLE idempotency_keys (
                                  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                  user_id          BIGINT REFERENCES users (id),
                                  idempotency_key  UUID        NOT NULL,
                                  request_hash     CHAR(64)    NOT NULL,
                                  status           VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
                                  response_code    INT,
                                  response_body    JSONB,
                                  transfer_id      BIGINT REFERENCES transfers (id),
                                  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
                                  CONSTRAINT chk_idempotency_keys_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
                                  CONSTRAINT uq_idempotency_keys_user_key UNIQUE NULLS NOT DISTINCT (user_id, idempotency_key)
);

CREATE TABLE risk_rules (
                            id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                            code               VARCHAR(50)  NOT NULL,
                            description        VARCHAR(255) NOT NULL,
                            action             VARCHAR(20)  NOT NULL,
                            threshold_count    INT,
                            window_minutes     INT,
                            amount_multiplier  NUMERIC(5, 2),
                            lookback_days      INT,
                            threshold_amount   BIGINT,
                            is_active          BOOLEAN      NOT NULL DEFAULT TRUE,
                            CONSTRAINT uq_risk_rules_code UNIQUE (code),
                            CONSTRAINT chk_risk_rules_action CHECK (action IN ('HOLD', 'FLAG_ALLOW'))
    );

CREATE TABLE risk_flags (
                            id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                            transfer_id      BIGINT      NOT NULL REFERENCES transfers (id),
                            risk_rule_id     BIGINT      NOT NULL REFERENCES risk_rules (id),
                            action_taken     VARCHAR(20) NOT NULL,
                            llm_explanation  TEXT,
                            llm_label        VARCHAR(20),
                            created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
                            explained_at     TIMESTAMPTZ,
                            CONSTRAINT uq_risk_flags_transfer_rule UNIQUE (transfer_id, risk_rule_id),
                            CONSTRAINT chk_risk_flags_action CHECK (action_taken IN ('HOLD', 'FLAG_ALLOW')),
                            CONSTRAINT chk_risk_flags_label CHECK (llm_label IN ('likely_ok', 'review', 'likely_fraud'))
);

CREATE TABLE reconciliation_runs (
                                     id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                     started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                                     ended_at      TIMESTAMPTZ,
                                     status        VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
                                     checks_run    INT         NOT NULL DEFAULT 0,
                                     issues_found  INT         NOT NULL DEFAULT 0,
                                     CONSTRAINT chk_reconciliation_runs_status CHECK (status IN ('RUNNING', 'PASSED', 'FAILED'))
);

CREATE TABLE reconciliation_issues (
                                       id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                       reconciliation_run_id  BIGINT       NOT NULL REFERENCES reconciliation_runs (id),
                                       check_name             VARCHAR(50)  NOT NULL,
                                       entity_type            VARCHAR(20)  NOT NULL,
                                       entity_id              BIGINT,
                                       expected_value         VARCHAR(100) NOT NULL,
                                       actual_value           VARCHAR(100) NOT NULL,
                                       CONSTRAINT chk_reconciliation_issues_entity CHECK (entity_type IN ('LEDGER', 'TRANSFER', 'ACCOUNT', 'LEDGER_ENTRY'))
);

CREATE TABLE job_locks (
                           id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                           job_name     VARCHAR(50) NOT NULL,
                           last_run_at  TIMESTAMPTZ,
                           CONSTRAINT uq_job_locks_job_name UNIQUE (job_name)
);