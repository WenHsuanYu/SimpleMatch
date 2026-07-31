# Separate Account Authority aggregate roots

Status: accepted

Account Authority models Reservation, Account limit, and Account position as separate aggregate
roots. Reservation lifecycle operations coordinate the reservation root with the relevant limit or
position root in one local transaction; no single Account root owns every position and reservation.
This keeps each invariant local while preserving concurrency at the account-day and account-symbol
scopes. Cross-service effects remain outbox/inbox work rather than distributed transactions.
