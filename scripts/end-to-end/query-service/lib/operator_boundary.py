#!/usr/bin/env python3
"""Prove Query read and rebuild operator credentials remain independent over real Lab HTTP."""

import argparse
import http.client
import json
from pathlib import Path
import sys

from query_http import fixture, private_json
from read_matrix import request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture-directory", type=Path, required=True)
    parser.add_argument("--operator-token-file", type=Path, required=True)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        identities, credentials = fixture(arguments.fixture_directory)
        operator = private_json(arguments.operator_token_file)["token"]
        if not isinstance(operator, str) or not operator or arguments.output.exists():
            raise ValueError("Use private operator material and a new evidence file")
        outcomes = []
        for alias, credential in credentials.items():
            code, _, _ = request(arguments.base_url, "/internal/query/rebuild", {
                "Authorization": "Bearer " + credential["token"],
                "X-SimpleMatch-Query-Token": credential["token"]}, "POST")
            if code != 401:
                raise ValueError("A Query credential crossed the operator trust boundary")
            outcomes.append({"principal": alias, "operation": "rebuild", "status": code})
        for headers in ({"Authorization": "Bearer " + operator}, {"X-SimpleMatch-Query-Token": operator}):
            for alias, account in identities["accounts"].items():
                code, body, _ = request(arguments.base_url, "/api/v1/accounts/" + account + "/summary", headers)
                if code != 401 or body:
                    raise ValueError("An operator credential acquired Query read authority")
                outcomes.append({"credential": "operator", "account": alias, "status": code})
        with arguments.output.open("x") as output:
            json.dump({"status": "PASS", "observations": outcomes}, output)
            output.write("\n")
    except (OSError, ValueError, KeyError, TypeError, http.client.HTTPException):
        print("Query/operator credential separation verification failed.", file=sys.stderr)
        return 1
    print("Query/operator credential separation PASS (synthetic Lab only).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
