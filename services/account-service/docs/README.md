# Account service specification

`account-service` is the authoritative owner of account limits, positions, and order reservations. It is a synchronous
internal service; it does not own order admission, matching order, or execution delivery.

## Owned responsibilities

- Expose limits and position snapshots for an account.
- Create, release, and apply fills to account reservations idempotently.
- Persist the authoritative account, position, and reservation state in the
  `account_service` schema.

## Boundary

The service exposes the account RPCs defined in
[`account_service.proto`](../../../proto/account_service.proto). Callers supply a stable `request_id` for every mutating
operation. A repeated request must not create a second reservation or apply a fill twice.

`risk-service` may use this service to evaluate or reserve account capacity. The service does not decide whether an
order is admitted to matching and does not publish the system-wide event contract; those responsibilities stay with
their owning services and the cross-cutting documentation.

## Source of truth

This page is the target specification entry point for account-owned behavior. Keep account-specific decisions here. Keep
shared transport, event, and platform rules in `services/docs/` so they remain canonical across services.
