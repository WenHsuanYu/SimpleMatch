#!/usr/bin/env python3
"""Run-owned Lab deployment approval; never a runtime grants/configuration source."""

import argparse
import fcntl
import hashlib
import json
import os
import re
import stat
import sys
import tempfile
import time
import uuid
from pathlib import Path


PREFIX = "simplematch.query-service.security"


def private(path, directory=False):
    """Reject redirected, foreign-owned or shared authority and fixture material."""
    info = path.lstat()
    if path.is_symlink() or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise ValueError("material is not owner-private")
    if not (stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)):
        raise ValueError("material has an unexpected type")


def bundle(path):
    """Inspect only the controlled fixture CLI's serialized format, not Spring precedence."""
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
            or properties[f"{PREFIX}.schema-version"] != "1"):
        raise ValueError("invalid controlled fixture shape")
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
            raise ValueError("client material does not match the approved verifier mapping")
    verifiers = {value for key, value in properties.items()
                 if key.startswith(PREFIX + ".credentials[") and key.endswith(".sha256")}
    if not 0 < version <= 9_223_372_036_854_775_807 or len(verifiers) != 5:
        raise ValueError("invalid fixture policy")
    return {"version": version, "digest": hashlib.sha256(raw).hexdigest(),
            "identitiesDigest": hashlib.sha256((path / "fixture-identities.json").read_bytes()).hexdigest(),
            "credentialsDigest": hashlib.sha256(credential_bytes).hexdigest(),
            "path": str(path.resolve()), "verifiers": verifiers}


def persist(directory, record):
    """Replace and fsync one coherent record while its caller owns the process lock."""
    descriptor, temporary = tempfile.mkstemp(prefix=".approval-", dir=directory)
    try:
        with os.fdopen(descriptor, "w") as stream:
            json.dump(record, stream, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, directory / "current.json")
        descriptor = os.open(directory, os.O_DIRECTORY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def current(directory):
    """A missing record is blocked recovery, including after runner restart."""
    private(directory / "current.json")
    record = json.loads((directory / "current.json").read_text())
    if record["schemaVersion"] != 1:
        raise ValueError("unsupported approval format")
    return record


def public(record):
    """Return only nonsecret approval identity, never credentials or registry contents."""
    return {key: record[key] for key in
            ("runId", "namespace", "namespaceUid", "version", "digest", "image", "phase",
             "activationId", "verificationId")}


def create(arguments):
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", arguments.image or ""):
        raise ValueError("image must have a validated immutable identity")
    if not re.fullmatch(r"query-security-[a-z0-9-]+", arguments.namespace or ""):
        raise ValueError("invalid isolated namespace")
    uuid.UUID(arguments.run_id)
    uuid.UUID(arguments.namespace_uid)
    candidate = bundle(arguments.bundle)
    if candidate["version"] != 1 or not 0 < arguments.drain_seconds <= 20:
        raise ValueError("invalid initial Lab approval")
    return {"schemaVersion": 1, "runId": arguments.run_id,
            "namespace": arguments.namespace, "namespaceUid": arguments.namespace_uid,
            "image": arguments.image, "version": 1, "digest": candidate["digest"],
            "identitiesDigest": candidate["identitiesDigest"], "bundle": candidate["path"],
            "credentialsDigest": candidate["credentialsDigest"],
            "phase": "APPROVED", "drainSeconds": arguments.drain_seconds,
            "activationId": str(uuid.uuid4()), "verificationId": str(uuid.uuid4()),
            "phaseStartedNs": time.monotonic_ns(),
            "clockId": Path("/proc/sys/kernel/random/boot_id").read_text().strip(), "events": []}


def approve(record, arguments):
    candidate = bundle(arguments.bundle)
    prior = bundle(Path(record["bundle"]))
    if record["phase"] not in ("APPROVED", "RESUMED"):
        raise ValueError("finish or recover the current activation first")
    if candidate["identitiesDigest"] != record["identitiesDigest"]:
        raise ValueError("identities cannot be replaced in this run")
    if (arguments.expect_current_digest != record["digest"] or prior["digest"] != record["digest"]
            or prior["credentialsDigest"] != record["credentialsDigest"]):
        raise ValueError("current approval changed or was lost")
    if candidate["version"] != record["version"] + 1 or not prior["verifiers"].isdisjoint(candidate["verifiers"]):
        raise ValueError("approval requires a next version and zero credential overlap")
    record.update(version=candidate["version"], digest=candidate["digest"],
                  bundle=candidate["path"], credentialsDigest=candidate["credentialsDigest"],
                  activationId=str(uuid.uuid4()), verificationId=None, phase="SUBMITTED")
    event(record, "Tsubmit")


def check(record, arguments):
    candidate = bundle(arguments.bundle)
    if (record["phase"] == "DESTROYED" or candidate["identitiesDigest"] != record["identitiesDigest"]
            or candidate["version"] != record["version"] or candidate["digest"] != record["digest"]
            or candidate["credentialsDigest"] != record["credentialsDigest"]
            or arguments.image != record["image"]):
        raise ValueError("policy or image is not currently approved")
    for key, value in (("runId", arguments.run_id), ("namespace", arguments.namespace),
                       ("namespaceUid", arguments.namespace_uid)):
        if value is not None and value != record[key]:
            raise ValueError("deployment scope does not match this approval")


def event(record, name, proof=None):
    record["events"].append({"name": name, "policyVersion": record["version"],
        "activationId": record["activationId"],
        "epochNs": proof["observedAtEpochNs"] if proof else time.time_ns(),
        "monotonicNs": proof["observedAtMonotonicNs"] if proof else time.monotonic_ns()})
    record["phaseStartedNs"] = record["events"][-1]["monotonicNs"]


def evidence(record, arguments, name):
    private(arguments.evidence_file)
    proof = json.loads(arguments.evidence_file.read_text())
    for key in ("runId", "namespace", "namespaceUid", "image", "activationId", "verificationId"):
        if proof[key] != record[key]:
            raise ValueError("evidence is from another scope or activation")
    if (proof["event"] != name or proof["policyVersion"] != record["version"]
            or proof["policyDigest"] != record["digest"] or proof["eligibleReplicas"] != 2
            or not record["phaseStartedNs"] <= proof["observedAtMonotonicNs"] <= time.monotonic_ns()
            or proof["observedAtEpochNs"] > time.time_ns()):
        raise ValueError("evidence is stale or does not describe the approved policy")
    return proof


def elapsed(record, name):
    if record["clockId"] != Path("/proc/sys/kernel/random/boot_id").read_text().strip():
        raise ValueError("clock changed; controlled recovery is required")
    previous = next(item for item in reversed(record["events"])
                    if item["name"] == name and item["policyVersion"] == record["version"])
    return (time.monotonic_ns() - previous["monotonicNs"]) / 1_000_000_000


def mark(record, arguments):
    proof = evidence(record, arguments, arguments.event)
    transitions = {"Tblock": ("SUBMITTED", "BLOCKED"), "Tdrained": ("BLOCKED", "DRAINED"),
                   "Trevoked": ("DRAINED", "REVOKED"), "Tresume": ("VERIFYING", "RESUMED")}
    before, after = transitions[arguments.event]
    if record["phase"] != before or proof["eligibleReplicas"] != 2:
        raise ValueError("invalid lifecycle transition")
    required = {"Tblock": ("newRequestsRejected", "existingConnectionsRejected", "directPathsRejected"),
                "Tdrained": ("oldProcessesStopped",), "Trevoked": ("oldProcessesStopped",),
                "Tresume": ("allReplicasVerified", "oldCredentialsRejected", "catchupComplete")}
    if any(proof.get(key) is not True for key in required[arguments.event]):
        raise ValueError("required lifecycle observation is absent")
    if arguments.event == "Tdrained" and elapsed(record, "Tblock") > record["drainSeconds"]:
        raise ValueError("drain deadline exceeded; do not resume")
    if arguments.event == "Trevoked" and elapsed(record, "Tsubmit") > 90:
        raise ValueError("stop verification deadline exceeded")
    if arguments.event == "Tresume" and (proof["policyVersion"] != record["version"]
                                        or proof["policyDigest"] != record["digest"]):
        raise ValueError("runtime policy is not the approved snapshot")
    record["phase"] = after
    event(record, arguments.event, proof)
    record["events"][-1]["evidenceDigest"] = hashlib.sha256(arguments.evidence_file.read_bytes()).hexdigest()


def perform(record, arguments):
    operation = arguments.operation
    if operation == "approve":
        approve(record, arguments)
    elif operation == "check":
        check(record, arguments)
    elif operation == "mark":
        mark(record, arguments)
    elif operation == "verify":
        if record["phase"] not in ("REVOKED", "REVOKED_UNAVAILABLE"):
            raise ValueError("old instances must be revoked first")
        record["phase"] = "VERIFYING"
        record["verificationId"] = str(uuid.uuid4())
        record["phaseStartedNs"] = time.monotonic_ns()
    elif operation == "failure":
        if record["phase"] not in ("REVOKED", "VERIFYING"):
            raise ValueError("revocation must already be established")
        record["phase"] = "REVOKED_UNAVAILABLE"
        record["verificationId"] = None
        event(record, "ActivationFailed")
    elif operation == "activate":
        if record["version"] != 1 or record["phase"] != "APPROVED":
            raise ValueError("only the fresh initial approval may activate")
        proof = evidence(record, arguments, "InitialActivated")
        if proof.get("allReplicasVerified") is not True:
            raise ValueError("initial replicas not verified")
        record["phase"] = "RESUMED"
        event(record, "InitialActivated", proof)
    elif operation == "collectors":
        if record["phase"] != "RESUMED":
            raise ValueError("collector material is withheld until Resume")
        candidate = bundle(Path(record["bundle"]))
        if (candidate["digest"] != record["digest"]
                or candidate["credentialsDigest"] != record["credentialsDigest"]
                or candidate["identitiesDigest"] != record["identitiesDigest"]):
            raise ValueError("approved client material changed")
        private(arguments.output_file.parent, True)
        with arguments.output_file.open("x") as output:
            output.write((Path(record["bundle"]) / "credentials.json").read_text())
    elif operation == "destroyed":
        proof = evidence(record, arguments, "Destroyed")
        if any(proof.get(key) is not True for key in ("namespaceGone", "oldProcessesStopped", "dataGone")):
            raise ValueError("full run destruction is unproven")
        record["phase"] = "DESTROYED"
        event(record, "Destroyed", proof)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("create", "approve", "check", "status", "mark",
                                             "verify", "failure", "activate", "collectors", "destroyed"))
    parser.add_argument("directory", type=Path)
    parser.add_argument("--bundle", type=Path)
    parser.add_argument("--image")
    parser.add_argument("--run-id")
    parser.add_argument("--namespace")
    parser.add_argument("--namespace-uid")
    parser.add_argument("--expect-current-digest")
    parser.add_argument("--event", choices=("Tblock", "Tdrained", "Trevoked", "Tresume"))
    parser.add_argument("--evidence-file", type=Path)
    parser.add_argument("--output-file", type=Path)
    parser.add_argument("--drain-seconds", type=float, default=20,
                        help="may tighten, never extend, the 20-second Local Lab maximum")
    arguments = parser.parse_args()
    os.umask(0o077)
    try:
        directory = arguments.directory
        if arguments.operation == "create":
            directory.mkdir(mode=0o700)
        private(directory, True)
        lock = directory / ".lock"
        if arguments.operation != "create":
            private(lock)
        with lock.open("a+") as stream:
            fcntl.flock(stream, fcntl.LOCK_EX)
            if arguments.operation == "create":
                record = create(arguments)
            else:
                record = current(directory)
                perform(record, arguments)
            if arguments.operation not in ("status", "check", "collectors"):
                persist(directory, record)
            print(json.dumps(public(record)))
        return 0
    except (OSError, ValueError, KeyError, TypeError, AttributeError, StopIteration):
        print("Query Lab approval rejected; keep protected reads unavailable.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
