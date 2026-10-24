package com.wallet.wallet_ledger;

import org.springframework.boot.SpringApplication;

public class TestWalletLedgerApplication {

	public static void main(String[] args) {
		SpringApplication.from(WalletLedgerApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
