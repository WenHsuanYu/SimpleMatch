#!/usr/bin/env python3
"""Read secured Query HTTP responses using pre-provisioned synthetic Local Lab identities."""

import argparse
import http.client
import json
import os
from pathlib import Path
import re
import signal
import stat
import sys
from urllib.parse import urlsplit
import uuid


ALIASES = {"client-a", "client-b", "client-c", "operations", "client-d"}
RESOURCE_ID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
MAX_FIXTURE_RESPONSE_BYTES = 16 * 1024 * 1024


def private_json(path, directory=False):
    """Consume only the provisioner's owner-private files, never publish their contents."""
    info = path.lstat()
    kind = stat.S_ISDIR if directory else stat.S_ISREG
    if path.is_symlink() or info.st_uid != os.getuid() or info.st_mode & 0o077 or not kind(info.st_mode):
        raise ValueError("Fixture material must remain owner-private")
    return None if directory else json.loads(path.read_text())


def fixture(directory):
    """Load client material only; Query remains the identity and authorization enforcer."""
    private_json(directory, directory=True)
    identities = private_json(directory / "fixture-identities.json")
    credentials = private_json(directory / "credentials.json")
    if (set(credentials) != ALIASES or set(identities["principals"]) != ALIASES
            or set(identities["accounts"]) != {"X", "Y"}):
        raise ValueError("Expected the approved synthetic matrix")
    values = [*identities["accounts"].values(), *identities["principals"].values()]
    if len(set(values)) != 7 or any(str(uuid.UUID(value)) != value for value in values):
        raise ValueError("Invalid synthetic identities")
    for alias, credential in credentials.items():
        if (credential["principalId"] != identities["principals"][alias]
                or not re.fullmatch(r"[A-Za-z0-9_-]{43}", credential["token"])):
            raise ValueError("Invalid synthetic credential mapping")
    return identities, credentials


def route(path, account, identities):
    """Select a least-privilege fixture credential; this never grants resource authority."""
    if path == "/api/v1/freshness":
        return "operations", "freshness"
    if re.fullmatch(r"/api/v1/market-reference/\d{4}-\d{2}-\d{2}/[A-Z0-9]{4}/[A-Za-z0-9_.-]+", path):
        return "client-d", "market"
    aliases = {value: alias for alias, value in identities["accounts"].items()}
    if account not in aliases:
        raise ValueError("Business reads require an approved fixture account")
    principal = "client-a" if aliases[account] == "X" else "client-b"
    if re.fullmatch(r"/api/v1/accounts/" + RESOURCE_ID + r"/summary", path):
        if path.split("/")[4] != account:
            raise ValueError("Account selector differs from the explicit fixture")
        return principal, "account"
    if re.fullmatch(r"/api/v1/orders/" + RESOURCE_ID + r"/executions", path):
        return principal, "executions"
    if re.fullmatch(r"/api/v1/orders/" + RESOURCE_ID, path):
        return principal, "order"
    raise ValueError("Route is outside the approved read collector")


def validate(body, kind, path, account):
    """Reject old envelopes and mismatched business identities instead of retaining them."""
    if kind == "freshness":
        if not isinstance(body, dict) or set(body) != {"partitions"} or not isinstance(body["partitions"], list):
            raise ValueError("Expected full operations checkpoints")
        required = {"sourceTopic", "partition", "lastProcessedOffset", "recoveryState", "updatedAtUnixMs"}
        if any(not isinstance(row, dict) or set(row) != required for row in body["partitions"]):
            raise ValueError("Invalid operations checkpoint")
        return
    if (not isinstance(body, dict) or set(body) != {"data", "freshness"}
            or not isinstance(body["freshness"], dict)
            or set(body["freshness"]) != {"projectionState"}
            or body["freshness"]["projectionState"] not in {"UNKNOWN", "RECOVERY_REQUIRED", "NO_GAP_RECORDED"}):
        raise ValueError("Expected the minimal business envelope")
    data = body["data"]
    if kind == "executions":
        if not isinstance(data, list):
            raise ValueError("Expected an execution collection")
        rows = data
    else:
        rows = [data]
    if any(not isinstance(row, dict) for row in rows):
        raise ValueError("Expected typed resource objects")
    if kind != "market" and any(row.get("accountId") != account for row in rows):
        raise ValueError("Unexpected fixture account in response")
    if kind in {"order", "executions"} and any(row.get("orderId") != path.split("/")[4] for row in rows):
        raise ValueError("Unexpected order in response")
    if kind == "market" and [data.get(key) for key in ("tradingDay", "venueMic", "symbol")] != path.split("/")[4:]:
        raise ValueError("Unexpected market identity in response")


def read(arguments, identities, credentials):
    """Use direct loopback HTTP with a whole-request POSIX deadline and no token argv/logs."""
    url = urlsplit(arguments.base_url)
    if (url.scheme != "http" or url.hostname != "127.0.0.1" or not url.port
            or url.username or url.password or url.path not in {"", "/"} or url.query or url.fragment):
        raise ValueError("Only the isolated loopback Local Lab transport is supported")
    principal, kind = route(arguments.path, arguments.account_id, identities)
    connection = http.client.HTTPConnection("127.0.0.1", url.port, timeout=arguments.timeout_seconds)
    response = None

    def expired(_signum, _frame):
        raise TimeoutError("Query request deadline exceeded")

    previous_handler = signal.signal(signal.SIGALRM, expired)
    signal.setitimer(signal.ITIMER_REAL, arguments.timeout_seconds)
    try:
        connection.request("GET", arguments.path, headers={"Authorization": "Bearer " + credentials[principal]["token"]})
        response = connection.getresponse()
        directives = {part.strip().lower() for part in response.getheader("Cache-Control", "").split(",")}
        if response.status != 200 or "no-store" not in directives:
            raise ValueError("Query rejected the secured read or omitted no-store")
        raw = response.read(MAX_FIXTURE_RESPONSE_BYTES + 1)
        if len(raw) > MAX_FIXTURE_RESPONSE_BYTES:
            raise ValueError("Fixture response exceeds the collector budget")
        body = json.loads(raw)
        validate(body, kind, arguments.path, arguments.account_id)
        arguments.output.write_text(json.dumps(body) + "\n")
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous_handler)
        if response is not None:
            response.close()
        connection.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture-directory", type=Path, required=True)
    parser.add_argument("--validate-only", action="store_true")
    parser.add_argument("--base-url")
    parser.add_argument("--path")
    parser.add_argument("--account-id")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--timeout-seconds", type=float, default=20)
    arguments = parser.parse_args()
    try:
        identities, credentials = fixture(arguments.fixture_directory)
        if not arguments.validate_only:
            if not arguments.base_url or not arguments.path or not arguments.output or not 0 < arguments.timeout_seconds <= 300:
                raise ValueError("Missing or invalid read arguments")
            read(arguments, identities, credentials)
            print("200")
        return 0
    except (OSError, ValueError, KeyError, TypeError, http.client.HTTPException):
        print("Query Lab read rejected; check private fixture, route and response contracts.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
