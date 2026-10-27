# Separate Account Authority aggregate roots

Status: accepted

Account Authority models Reservation, Account limit, and Account position as separate aggregate
roots. Reservation lifecycle operations coordinate the reservation root with the relevant limit or
position root in one local transaction. No single Account root owns every position and reservation.
This keeps each invariant local and preserves concurrency at the account-day and account-symbol
scopes. Cross-service effects use the Transactional Outbox and Inbox patterns, not distributed
transactions.
