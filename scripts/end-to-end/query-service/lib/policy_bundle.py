"""Read-only validation of owner-private synthetic Query policy fixture bundles.

The certification input checker uses the read-only bundle inspector.
The fixture-generator contract also checks credential rotation between
synthetic policy generations. This module is not a policy approval service.
"""

import hashlib
import json
import os
import re
import stat
import uuid


PREFIX = "simplematch.query-service.security"


def private(path, directory=False):
    """Reject redirected, foreign-owned or shared fixture material."""
    info = path.lstat()
    if path.is_symlink() or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise ValueError("material is not owner-private")
    if not (stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)):
        raise ValueError("material has an unexpected type")


def inspect_bundle(path):
    """Inspect only the controlled fixture CLI format, not Spring precedence."""
    private(path, True)
    for name in ("application.properties", "fixture-identities.json", "credentials.json"):
        private(path / name)
    raw = (path / "application.properties").read_bytes()
    entries = [line.split("=", 1) for line in raw.decode().splitlines() if line and not line.startswith("#")]
    properties = dict(entries)
    if len(properties) != len(entries):
        raise ValueError("duplicate fixture property")
    version = int(properties[f"{PREFIX}.policy-version"])
    identities = json.loads((path / "fixture-identities.json").read_text())
    credential_bytes = (path / "credentials.json").read_bytes()
    credentials = json.loads(credential_bytes)
    aliases = ("client-a", "client-b", "client-c", "operations", "client-d")
    if (set(credentials) != set(aliases) or set(identities["principals"]) != set(aliases)
            or set(identities["accounts"]) != {"X", "Y"}
            or properties[f"{PREFIX}.schema-version"] != "1"):
        raise ValueError("invalid controlled fixture shape")
    identifiers = [*identities["accounts"].values(), *identities["principals"].values()]
    if len(set(identifiers)) != 7 or any(str(uuid.UUID(value)) != value for value in identifiers):
        raise ValueError("invalid controlled fixture identities")
    expected = {f"{PREFIX}.schema-version": "1", f"{PREFIX}.policy-version": str(version)}
    for index, alias in enumerate(aliases):
        credential = credentials[alias]
        principal = identities["principals"][alias]
        credential_prefix = f"{PREFIX}.credentials[{index}]"
        if (credential["principalId"] != principal
                or properties[f"{PREFIX}.principals[{index}].id"] != principal
                or properties[credential_prefix + ".principal-id"] != principal
                or properties[credential_prefix + ".id"] != credential["credentialId"]
                or properties[credential_prefix + ".expires-at"] != credential["expiresAt"]
                or not re.fullmatch(r"[A-Za-z0-9_-]{43}", credential["token"])
                or hashlib.sha256(credential["token"].encode("ascii")).hexdigest()
                != properties[credential_prefix + ".sha256"]):
            raise ValueError("client material does not match the verifier mapping")
        if str(uuid.UUID(credential["credentialId"])) != credential["credentialId"]:
            raise ValueError("invalid fixture credential identity")
        principal_prefix = f"{PREFIX}.principals[{index}]"
        status = properties[principal_prefix + ".status"]
        if status not in ({"ACTIVE", "DISABLED"} if alias == "client-d" else {"ACTIVE"}):
            raise ValueError("unsupported synthetic principal status")
        grants = {"client-a": ("X",), "client-b": ("Y",), "client-c": ("X",),
                  "operations": (), "client-d": ()}[alias]
        if alias == "client-c" and properties.get(principal_prefix + ".account-grants[1]") == identities["accounts"]["Y"]:
            grants = ("X", "Y")
        expected.update({principal_prefix + ".id": principal,
                         principal_prefix + ".status": status,
                         principal_prefix + ".operations-read": str(alias == "operations").lower(),
                         credential_prefix + ".id": credential["credentialId"],
                         credential_prefix + ".principal-id": principal,
                         credential_prefix + ".sha256": hashlib.sha256(credential["token"].encode("ascii")).hexdigest(),
                         credential_prefix + ".enabled": "true",
                         credential_prefix + ".expires-at": credential["expiresAt"],
                         **{principal_prefix + f".account-grants[{position}]": identities["accounts"][account]
                            for position, account in enumerate(grants)}})
    verifiers = {value for key, value in properties.items()
                 if key.startswith(PREFIX + ".credentials[") and key.endswith(".sha256")}
    if not 0 < version <= 9_223_372_036_854_775_807 or len(verifiers) != 5 or properties != expected:
        raise ValueError("invalid fixture policy")
    return {"version": version, "digest": hashlib.sha256(raw).hexdigest(),
            "identitiesDigest": hashlib.sha256((path / "fixture-identities.json").read_bytes()).hexdigest(),
            "credentialsDigest": hashlib.sha256(credential_bytes).hexdigest(),
            "path": str(path.resolve()), "verifiers": verifiers}


def inspect_unchanged(path, expected):
    """Fail closed if the previously inspected private policy or credentials changed."""
    observed = inspect_bundle(path)
    if any(observed[key] != expected[key] for key in
           ("version", "digest", "identitiesDigest", "credentialsDigest")):
        raise ValueError("Query policy fixture changed after inspection")
    return observed


def inspect_replacement(previous, successor, expected):
    """Validate the next startup-only fixture without creating approval state."""
    before = inspect_unchanged(previous, expected)
    after = inspect_bundle(successor)
    if (after["version"] != before["version"] + 1
            or after["identitiesDigest"] != before["identitiesDigest"]
            or not before["verifiers"].isdisjoint(after["verifiers"])):
        raise ValueError("Query policy replacement must advance, retain identities and rotate credentials")
    return after
