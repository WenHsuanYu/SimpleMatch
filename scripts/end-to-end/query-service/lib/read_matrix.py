#!/usr/bin/env python3
"""Verify the approved synthetic Query read matrix; never establish server-side authority."""

import argparse
import http.client
import json
from pathlib import Path
import signal
import sys
from urllib.parse import urlsplit
import uuid

if __package__:
    from .query_http import MAX_FIXTURE_RESPONSE_BYTES, fixture, private_json, route, validate
else:
    from query_http import MAX_FIXTURE_RESPONSE_BYTES, fixture, private_json, route, validate


def resource_facts(resources, identities):
    """Accept only explicit run-owned parent identities and known execution-presence facts."""
    orders = resources["orders"]
    if not isinstance(orders, list) or {row["account"] for row in orders} != {"X", "Y"}:
        raise ValueError("Both approved Accounts require explicit Order fixtures")
    identifiers = [row["orderId"] for row in orders] + [resources["missingOrderId"]]
    if (len(set(identifiers)) != len(identifiers)
            or any(str(uuid.UUID(value)) != value for value in identifiers)
            or any(row["account"] not in identities["accounts"] or type(row["hasExecutions"]) is not bool
                   for row in orders)):
        raise ValueError("Invalid run-owned Order fixture")
    market = resources["market"]
    market_path = "/api/v1/market-reference/" + "/".join(
        market[key] for key in ("tradingDay", "venueMic", "symbol"))
    route(market_path, None, identities)
    if not isinstance(market["artifactId"], str) or not market["artifactId"]:
        raise ValueError("Market fixture requires the approved artifact identity")
    return market_path


def verify(read, identities, credentials, resources, version, profile="initial"):
    """Observe every permitted/denied read through HTTP, independently of cached content."""
    market_path = resource_facts(resources, identities)
    allowed = {"client-a": {"X"}, "client-b": {"Y"},
               "client-c": {"X", "Y"} if profile == "initial" else {"X"},
               "operations": set(), "client-d": set()}
    observations = []

    def check(alias, path, expected, kind, account=None, execution_presence=None):
        status, raw, active_version = read(path, credentials[alias]["token"])
        if status != expected or (status != 401 and active_version != str(version)):
            raise ValueError("Read status or effective policy version differs from the approved matrix")
        if status in (401, 404) and raw:
            raise ValueError("Denied resource disclosed an error body")
        if status == 200:
            body = json.loads(raw)
            account_id = identities["accounts"][account] if account else None
            validate(body, kind, path, account_id)
            if kind == "executions" and bool(body["data"]) != execution_presence:
                raise ValueError("Execution presence differs from the known parent fixture")
            if kind == "market" and body["data"]["artifactId"] != resources["market"]["artifactId"]:
                raise ValueError("Market artifact differs from the approved fixture")
        observations.append({"principal": alias, "account": account, "resource": kind, "status": status})

    for alias, grants in allowed.items():
        disabled = profile == "d-disabled" and alias == "client-d"
        for account in ("X", "Y"):
            expected = 401 if disabled else (200 if account in grants else 404)
            check(alias, "/api/v1/accounts/" + identities["accounts"][account] + "/summary",
                  expected, "account", account)
            for order in (row for row in resources["orders"] if row["account"] == account):
                path = "/api/v1/orders/" + order["orderId"]
                check(alias, path, expected, "order", account)
                check(alias, path + "/executions", expected, "executions", account, order["hasExecutions"])
        for suffix in ("", "/executions"):
            check(alias, "/api/v1/orders/" + resources["missingOrderId"] + suffix,
                  401 if disabled else 404, "missing-parent")
        check(alias, "/api/v1/freshness", 401 if disabled else (200 if alias == "operations" else 403),
              "freshness")
        check(alias, market_path, 401 if disabled else 200, "market")
    return observations


def request(base_url, path, headers, method="GET"):
    """Exchange one bounded request over the isolated Lab loopback transport."""
    url = urlsplit(base_url)
    if (url.scheme != "http" or url.hostname != "127.0.0.1" or not url.port or url.username or url.password
            or url.path not in ("", "/") or url.query or url.fragment):
        raise ValueError("Only isolated loopback Lab HTTP is supported")
    connection = http.client.HTTPConnection("127.0.0.1", url.port, timeout=3)
    response = None

    def expired(_signum, _frame):
        raise TimeoutError("Lab request deadline exceeded")

    previous = signal.signal(signal.SIGALRM, expired)
    signal.setitimer(signal.ITIMER_REAL, 3)
    try:
        connection.request(method, path, headers=headers)
        response = connection.getresponse()
        body = response.read(MAX_FIXTURE_RESPONSE_BYTES + 1)
        if len(body) > MAX_FIXTURE_RESPONSE_BYTES:
            raise ValueError("Lab response exceeds its budget")
        if response.status == 200 and "no-store" not in {
                part.strip().lower() for part in response.getheader("Cache-Control", "").split(",")}:
            raise ValueError("Protected response omitted no-store")
        return response.status, body, response.getheader("X-SimpleMatch-Query-Policy-Version")
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous)
        if response:
            response.close()
        connection.close()


def reader(base_url):
    """Read protected data without exposing bearer credentials to child-process arguments."""
    return lambda path, token: request(base_url, path, {"Authorization": "Bearer " + token})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture-directory", required=True, type=Path)
    parser.add_argument("--resources", required=True, type=Path)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--policy-version", required=True, type=int)
    parser.add_argument("--profile", choices=("initial", "c-y-revoked", "d-disabled"), required=True)
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args()
    try:
        if arguments.policy_version < 1 or arguments.output.exists():
            raise ValueError("Use an explicit policy version and a new evidence file")
        identities, credentials = fixture(arguments.fixture_directory)
        resources = private_json(arguments.resources)
        observations = verify(reader(arguments.base_url), identities, credentials, resources,
                              arguments.policy_version, arguments.profile)
        with arguments.output.open("x") as output:
            json.dump({"status": "PASS", "policyVersion": arguments.policy_version,
                       "observations": observations}, output)
            output.write("\n")
    except (OSError, ValueError, KeyError, TypeError, http.client.HTTPException):
        print("Query read matrix rejected the observed status, identity or response contract.", file=sys.stderr)
        return 1
    print("Query read matrix PASS (synthetic Lab only).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
