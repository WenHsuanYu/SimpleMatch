package com.simplematch.accountservice.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AccountPositionIdentityTest {
  private static final AccountId ACCOUNT_ID =
      AccountId.parse("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c13");

  @DisplayName("provisioned positions retain canonical symbol boundary values")
  @ParameterizedTest
  @ValueSource(strings = {"A", "2330", "ABCDEFGHIJKL"})
  void provisionsCanonicalSymbol(String symbol) {
    assertThat(AccountPosition.provisioned(ACCOUNT_ID, symbol, 1).symbol()).isEqualTo(symbol);
  }

  @DisplayName("provisioned positions reject noncanonical symbols before persistence")
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "abc", "2330!", " ABC", "ABC ", "ABCDEFGHIJKLM"})
  void rejectsNoncanonicalSymbol(String symbol) {
    assertThatThrownBy(() -> AccountPosition.provisioned(ACCOUNT_ID, symbol, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("symbol must contain 1 to 12 uppercase letters or digits");
  }
}
