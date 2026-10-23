#!/usr/bin/env python3
"""Provision private, synthetic Query credentials before a controlled Local Lab deployment."""

import argparse
import hashlib
import json
import os
import secrets
import sys
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path


POLICY_PREFIX = "simplematch.query-service.security"
CREDENTIAL_LIFETIME = timedelta(hours=1)


def positive_policy_version(value):
    """Keep approved policy versions representable by Query's positive Java long."""
    try:
        version = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("policy version must be a positive integer") from error
    if not 0 < version <= 9_223_372_036_854_775_807:
        raise argparse.ArgumentTypeError("policy version must be a positive signed 64-bit integer")
    return version


def fixture_bundle(policy_version):
    """Build one coherent five-principal policy and separate private client material."""
    accounts = {alias: str(uuid.uuid4()) for alias in ("X", "Y")}
    grants = {
        "client-a": ("X",),
        "client-b": ("Y",),
        "client-c": ("X", "Y"),
        "operations": (),
        "client-d": (),
    }
    expires_at = (
        (datetime.now(timezone.utc) + CREDENTIAL_LIFETIME)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z")
    )
    principals = {}
    credentials = {}
    properties = [
        f"{POLICY_PREFIX}.schema-version=1",
        f"{POLICY_PREFIX}.policy-version={policy_version}",
    ]
    for index, (alias, account_aliases) in enumerate(grants.items()):
        principal_id = str(uuid.uuid4())
        credential_id = str(uuid.uuid4())
        token = secrets.token_urlsafe(32)
        principal_prefix = f"{POLICY_PREFIX}.principals[{index}]"
        credential_prefix = f"{POLICY_PREFIX}.credentials[{index}]"
        principals[alias] = principal_id
        credentials[alias] = {
            "principalId": principal_id,
            "credentialId": credential_id,
            "token": token,
            "expiresAt": expires_at,
        }
        properties.extend(
            [
                f"{principal_prefix}.id={principal_id}",
                f"{principal_prefix}.status=ACTIVE",
                f"{principal_prefix}.operations-read={str(alias == 'operations').lower()}",
            ]
        )
        properties.extend(
            f"{principal_prefix}.account-grants[{position}]={accounts[account]}"
            for position, account in enumerate(account_aliases)
        )
        properties.extend(
            [
                f"{credential_prefix}.id={credential_id}",
                f"{credential_prefix}.principal-id={principal_id}",
                f"{credential_prefix}.sha256={hashlib.sha256(token.encode('ascii')).hexdigest()}",
                f"{credential_prefix}.enabled=true",
                f"{credential_prefix}.expires-at={expires_at}",
            ]
        )
    return {
        "credentials.json": json.dumps(credentials, indent=2) + "\n",
        "fixture-identities.json": json.dumps(
            {"accounts": accounts, "principals": principals}, indent=2
        )
        + "\n",
        "application.properties": "\n".join(properties) + "\n",
    }


def main():
    """Create a new owner-private bundle without logging credential or verifier contents."""
    parser = argparse.ArgumentParser(
        description=(
            "Generate synthetic Local Lab Query credentials with a one-hour lifetime. "
            "Distribute client material privately and provision the same application.properties "
            "policy to every Query replica before deployment. Each bundle starts a new disposable "
            "run; this command does not rotate an existing policy or deploy resources."
        )
    )
    parser.add_argument("output_directory", type=Path, help="new private output directory")
    parser.add_argument(
        "--policy-version", type=positive_policy_version, default=1,
        help="approved positive policy version (default: 1); not a policy approval mechanism",
    )
    arguments = parser.parse_args()
    os.umask(0o077)
    try:
        arguments.output_directory.mkdir(mode=0o700)
        for name, content in fixture_bundle(arguments.policy_version).items():
            with (arguments.output_directory / name).open("x", encoding="utf-8") as output:
                output.write(content)
    except OSError:
        print(
            "Cannot provision fixtures: use a new writable output directory.", file=sys.stderr
        )
        return 1
    print(f"Created private Query security fixture bundle: {arguments.output_directory}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
