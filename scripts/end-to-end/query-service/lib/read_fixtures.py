"""Run-owned Query DML fixtures, independent of Matching/replay certification evidence."""

import hashlib
import uuid


def lifecycle_orders(identities, artifact_id):
    """Prepare explicit parent/collection cases in an already Flyway-migrated disposable database."""
    accounts = identities["accounts"]
    if set(accounts) != {"X", "Y"} or any(str(uuid.UUID(value)) != value for value in accounts.values()):
        raise ValueError("Only the approved synthetic Account pair may be seeded")
    resources = {"orders": [], "missingOrderId": str(uuid.uuid4()), "market": {
        "tradingDay": "2026-08-11", "venueMic": "XTAI", "symbol": "2330", "artifactId": artifact_id}}
    statements = ["BEGIN;"]
    for alias in ("X", "Y"):
        for executed in (True, False):
            order_id = str(uuid.uuid4())
            resources["orders"].append({"account": alias, "orderId": order_id, "hasExecutions": executed})
            event_id = hashlib.sha256(order_id.encode()).hexdigest()
            side = "SIDE_BUY" if alias == "X" else "SIDE_SELL"
            state, leaves = ("PARTIALLY_FILLED", 5) if executed else ("RESTING", 10)
            statements.append("INSERT INTO query_service.order_read_model "
                "(order_id,account_id,venue_mic,symbol,side,state,leaves_quantity_shares,"
                "last_event_id,source_partition_id,source_offset_value,updated_at_unix_ms) "
                f"VALUES ('{order_id}','{accounts[alias]}','XTAI','2330','{side}','{state}',"
                f"{leaves},'{event_id}',0,4,1000);")
            if executed:
                statements.append("INSERT INTO query_service.execution_read_model "
                    "(execution_id,order_id,account_id,venue_mic,symbol,side,fill_quantity_shares,"
                    "fill_price_units,cumulative_quantity_shares,leaves_quantity_shares,average_price_units,"
                    "source_event_id,source_partition_id,source_offset_value,executed_at_unix_ms) "
                    f"VALUES ('{uuid.uuid4()}','{order_id}','{accounts[alias]}','XTAI','2330','{side}',"
                    f"5,100,5,5,100,'{event_id}',0,4,1000);")
    statements.append("COMMIT;")
    return resources, "\n".join(statements)
