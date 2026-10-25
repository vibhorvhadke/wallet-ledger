package com.wallet.wallet_ledger.qa;

import java.time.LocalDate;
import java.util.List;

/** Everything here comes from our code and the database; nothing is copied from the LLM's text. */
public record StatementAnswer(boolean answered, Integer questionType, String answer,
                              Long value, String unit, LocalDate from, LocalDate to,
                              String counterparty, List<String> matchedCounterparties,
                              List<Long> transferIds, boolean transferIdsTruncated) {

    static StatementAnswer unsupported(String message) {
        return new StatementAnswer(false, null, message, null, null, null, null, null, List.of(), List.of(), false);
    }
}