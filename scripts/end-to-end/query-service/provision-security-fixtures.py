#!/usr/bin/env python3
"""Provision private, synthetic Query credentials before a controlled Local Lab deployment."""

import argparse
import hashlib
import json
import os
import secrets
import stat
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


def fixture_bundle(policy_version, identities=None, profile="initial"):
    """Build one coherent five-principal policy and separate private client material."""
    accounts = (identities["accounts"] if identities else
                {alias: str(uuid.uuid4()) for alias in ("X", "Y")})
    grants = {
        "client-a": ("X",),
        "client-b": ("Y",),
        "client-c": ("X", "Y") if profile == "initial" else ("X",),
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
        principal_id = identities["principals"][alias] if identities else str(uuid.uuid4())
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
                f"{principal_prefix}.status="
                + ("DISABLED" if profile == "d-disabled" and alias == "client-d" else "ACTIVE"),
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


def previous_identities(directory, policy_version, profile):
    """Read only this CLI's owner-private fixture format; it is not a policy authority."""
    for path in (directory, *(directory / name for name in
                             ("fixture-identities.json", "application.properties", "credentials.json"))):
        info = path.lstat()
        expected_type = stat.S_ISDIR if path == directory else stat.S_ISREG
        if (path.is_symlink() or info.st_uid != os.getuid() or info.st_mode & 0o077
                or not expected_type(info.st_mode)):
            raise ValueError("previous fixture must remain owner-private")
    identities = json.loads((directory / "fixture-identities.json").read_text())
    if set(identities["accounts"]) != {"X", "Y"} or set(identities["principals"]) != {
        "client-a", "client-b", "client-c", "operations", "client-d"
    }:
        raise ValueError("previous fixture identities are invalid")
    values = [*identities["accounts"].values(), *identities["principals"].values()]
    if len(set(values)) != 7 or any(str(uuid.UUID(value)) != value for value in values):
        raise ValueError("previous fixture identities are invalid")
    lines = (directory / "application.properties").read_text().splitlines()
    properties = dict(line.split("=", 1) for line in lines)
    if len(properties) != len(lines) or policy_version != int(
        properties[f"{POLICY_PREFIX}.policy-version"]
    ) + 1:
        raise ValueError("rotation requires the next explicit policy version")
    c_grants = {value for key, value in properties.items()
                if key.startswith(f"{POLICY_PREFIX}.principals[2].account-grants[")}
    expected = {identities["accounts"]["X"]}
    if profile == "c-y-revoked":
        expected.add(identities["accounts"]["Y"])
    if c_grants != expected:
        raise ValueError("rotation profile does not match the previous fixture")
    return identities


def main():
    """Create a new owner-private bundle without logging credential or verifier contents."""
    parser = argparse.ArgumentParser(
        description=(
            "Generate synthetic Local Lab Query credentials with a one-hour lifetime. "
            "Distribute client material privately and provision the same application.properties "
            "policy to every Query replica before deployment. Initial bundles start a new "
            "disposable run; explicit rotation profiles retain that run's identities. "
            "This command generates material only; it neither approves policy nor deploys resources."
        )
    )
    parser.add_argument("output_directory", type=Path, help="new private output directory")
    parser.add_argument(
        "--policy-version", type=positive_policy_version, default=1,
        help="approved positive policy version (default: 1); not a policy approval mechanism",
    )
    parser.add_argument("--previous-directory", type=Path,
                        help="private bundle from this run; preserves Principal and Account IDs")
    parser.add_argument("--profile", choices=("initial", "c-y-revoked", "d-disabled"),
                        default="initial", help="explicit synthetic fixture transition, not approval")
    arguments = parser.parse_args()
    os.umask(0o077)
    try:
        if (arguments.previous_directory is None) != (arguments.profile == "initial"):
            raise ValueError("rotation requires a previous bundle and a noninitial profile")
        identities = (previous_identities(arguments.previous_directory,
                                         arguments.policy_version, arguments.profile)
                      if arguments.previous_directory else None)
        bundle = fixture_bundle(arguments.policy_version, identities, arguments.profile)
        arguments.output_directory.mkdir(mode=0o700)
        for name, content in bundle.items():
            with (arguments.output_directory / name).open("x", encoding="utf-8") as output:
                output.write(content)
    except (OSError, ValueError, KeyError, TypeError):
        print(
            "Cannot provision fixtures: use a new writable output directory.", file=sys.stderr
        )
        return 1
    print(f"Created private Query security fixture bundle: {arguments.output_directory}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
