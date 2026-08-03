package com.simplematch.quickfixgateway.wal;

/** Order and account identities needed to correlate a durable gateway command. */
public record WalOrderReference(
    String orderId, String clOrdId, String origClOrdId, String accountId) {
  /** Requires order correlation identities while allowing account data to remain optional. */
  public WalOrderReference {
    orderId = WalValidation.requiredText(orderId, "order_id");
    clOrdId = WalValidation.requiredText(clOrdId, "cl_ord_id");
    origClOrdId = WalValidation.optionalText(origClOrdId, "orig_cl_ord_id");
    accountId = WalValidation.optionalText(accountId, "account_id");
  }
}
