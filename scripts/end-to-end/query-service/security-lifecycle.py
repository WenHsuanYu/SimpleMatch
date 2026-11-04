#!/usr/bin/env python3
"""Focused Local Lab proof of Query policy revocation, fencing and no-reset recovery."""

import argparse
import concurrent.futures
import hashlib
import http.client
import json
import os
import shutil
import signal
import subprocess
import sys
import tarfile
import tempfile
import time
import traceback
import uuid
from pathlib import Path

from lib.policy_bundle import inspect_bundle, inspect_replacement, inspect_unchanged
from lib.security_lab import LABEL, Lab, wait_for


REPOSITORY = Path(__file__).resolve().parents[3]


def control_plane(context):
    """Observe the canonical control plane rather than inferring health from Node Ready."""
    pods = json.loads(subprocess.check_output(["kubectl", "--context", context, "--request-timeout=15s",
        "-n", "kube-system", "get", "pods", "-l",
        "component in (etcd,kube-apiserver,kube-controller-manager,kube-scheduler)", "-o", "json"],
        text=True, timeout=20))["items"]
    if len(pods) != 4 or any(not s["ready"] for p in pods for s in p["status"]["containerStatuses"]):
        raise RuntimeError("Canonical control-plane components are not ready")
    return {p["metadata"]["uid"]: [s["restartCount"] for s in p["status"]["containerStatuses"]] for p in pods}


def preflight(context, docker_context):
    """Read existing cluster identities before any build, deployment or fault."""
    before = control_plane(context)
    if shutil.disk_usage(REPOSITORY).free < 40 * 1024**3:
        raise RuntimeError("Host usable space is below the existing 40 GiB deployment preflight floor")
    subprocess.check_output(["kubectl", "--context", context, "--request-timeout=15s",
        "get", "--raw=/readyz?verbose"], text=True, timeout=20)
    with tempfile.TemporaryDirectory(prefix="query-lab-preflight-") as temporary:
        manifest = Lab(REPOSITORY, Path(temporary) / "plan", context, docker_context, "preflight-only").plan()
    nodes = json.loads(subprocess.check_output(
        ["kubectl", "--context", context, "--request-timeout=15s", "get", "nodes", "-o", "json"],
        text=True, timeout=20))["items"]
    workers = [node for node in nodes if node["metadata"]["labels"].get("simplematch.io/node-pool") == "local-resilience"]
    if len(workers) != 3 or any(not any(c["type"] == "Ready" and c["status"] == "True"
                                       for c in node["status"]["conditions"]) for node in nodes):
        raise RuntimeError("Expected the healthy canonical three-worker Lab topology")
    if {node["metadata"]["labels"].get("simplematch.io/worker-slot") for node in workers} != {"0", "1", "2"}:
        raise RuntimeError("Expected unique eligible worker slots 0, 1 and 2")
    active = json.loads(subprocess.check_output(["kubectl", "--context", context, "--request-timeout=15s",
        "get", "namespaces", "-l", LABEL, "-o", "json"], text=True, timeout=20))["items"]
    if active:
        raise RuntimeError("A previous Query security run has not been destroyed")
    observed = []
    for node in workers:
        name = node["metadata"]["name"]
        detail = json.loads(subprocess.check_output(["docker", "--context", docker_context,
                                                     "inspect", name], text=True, timeout=20))[0]
        if detail["Config"]["Labels"].get("io.x-k8s.kind.cluster") != context.removeprefix("kind-"):
            raise RuntimeError("Docker daemon and Kubernetes context refer to different clusters")
        containers = json.loads(subprocess.check_output(["docker", "--context", docker_context, "exec",
            name, "crictl", "ps", "-o", "json"], text=True, timeout=20))["containers"]
        if any(c["labels"].get("io.kubernetes.pod.namespace", "").startswith("query-security-") for c in containers):
            raise RuntimeError("An old Query Lab process still exists on a worker")
        for resource in ("tasks", "containers", "snapshots"):
            listing = ["ls"] if resource == "snapshots" else ["ls", "-q"]
            raw = subprocess.check_output(["docker", "--context", docker_context, "exec", name,
                "ctr", "-n", "k8s.io", resource, *listing], text=True, timeout=20).split()
            if any(identity.startswith("qs193-") for identity in raw):
                raise RuntimeError("An old raw Query image probe remains; inspect its retained ownership record")
        observed.append({"node": name, "nodeUid": node["metadata"]["uid"], "dockerId": detail["Id"]})
        subprocess.run(["docker", "--context", docker_context, "exec", name, "test", "-x", "/bin/kill"],
                       capture_output=True, check=True, timeout=10)
        subprocess.run(["docker", "--context", docker_context, "exec", name, "curl", "--version"],
                       capture_output=True, check=True, timeout=10)
    time.sleep(5)
    if control_plane(context) != before:
        raise RuntimeError("Control-plane restart counters changed during the stability window")
    return {"context": context, "dockerContext": docker_context, "workers": observed,
            "manifestResources": manifest,
            "freshNamespaceRequired": True, "controlPlaneStable": True,
            "storage": "run-owned emptyDir; no PVC or storage-failover claim"}


def images(lab, cluster):
    """Import only three pinned images, then execute actual bits on every eligible worker."""
    references = (lab.image, "postgres:18.4", "apache/kafka:4.3.1")
    observations = []
    for index, reference in enumerate(references):
        expected = json.loads(lab.docker("image", "inspect", reference).stdout)[0]
        if expected["Os"] != "linux" or expected["Architecture"] != "amd64":
            raise RuntimeError("Image platform does not match the observed Lab workers")
        # Export only the installed child platform: a local OCI index may include absent children.
        archive = lab.private / f"image-{index}.tar"
        lab.docker("image", "save", "--platform", "linux/amd64", "--output", str(archive), reference, timeout=120)
        with tarfile.open(archive) as package:
            manifest = json.load(package.extractfile("manifest.json"))
            if len(manifest) != 1:
                raise RuntimeError("Expected exactly one exported platform")
            config = package.extractfile(manifest[0]["Config"]).read()
            config_id = "sha256:" + hashlib.sha256(config).hexdigest()
            if json.loads(config)["architecture"] != "amd64" or json.loads(config)["os"] != "linux":
                raise RuntimeError("Exported child platform differs from the Lab workers")
            if reference == lab.image:
                lab.image_config_id = config_id
        if json.loads(lab.docker("image", "inspect", reference).stdout)[0]["Id"] != expected["Id"]:
            raise RuntimeError("Source image changed while exporting its platform")
        for worker in cluster["workers"]:
            node = worker["node"]
            usage = lab.docker("exec", node, "df", "-P", "/var/lib/containerd").stdout.splitlines()[-1].split()
            if int(usage[4].rstrip("%")) >= 75:
                raise RuntimeError("Worker image filesystem lacks headroom below kubelet GC threshold")
            with archive.open("rb") as source:
                imported = subprocess.run(["docker", "--context", lab.docker_context, "exec", "-i", node,
                    "ctr", "-n", "k8s.io", "images", "import", "--platform", "linux/amd64", "-"],
                    stdin=source, capture_output=True, timeout=120)
            if imported.returncode:
                (lab.private / f"import-{index}-{node}.log").write_bytes(imported.stdout + imported.stderr)
                raise RuntimeError("Platform-specific image import failed; preserve import diagnostics")
            found = json.loads(lab.docker("exec", node, "crictl", "inspecti", reference).stdout)
            if found["status"]["id"] != config_id:
                raise RuntimeError("CRI image config identity differs from the pinned Docker config")
            observed = next(tag for tag in found["status"]["repoTags"] if tag.endswith(reference))
            command = (["/layers/paketo-buildpacks_bellsoft-liberica/jre/bin/java", "-version"]
                       if reference == lab.image else ["/bin/sh", "-c", "true"])
            lab.probe(node, observed, command)
            observations.append({"node": node, "reference": observed, "dockerSourceId": expected["Id"],
                                 "configId": config_id, "executionPassed": True})
        archive.unlink()  # Exact run-owned transport scratch; identities/execution evidence remain.
    lab.save("image-execution.json", observations)


def reject_old(lab, endpoints, old_bundle):
    credentials = json.loads((old_bundle / "credentials.json").read_text())
    observations = []
    for endpoint in endpoints:
        for alias, credential in credentials.items():
            code, _, _ = lab.http(endpoint, "/api/v1/freshness", credential["token"])
            if code != 401:
                raise RuntimeError("An old read/operations credential still establishes a Principal")
            observations.append({"pod": endpoint["pod"], "principal": alias, "status": code})
    return observations


def blocked(lab, endpoints, token, connections):
    """Retain rejection of original sockets; never substitute a newly reopened connection."""
    observations = []
    for index, (endpoint, connection) in enumerate(zip(endpoints, connections)):
        fenced = lab.stopped(endpoint)
        fresh = existing = direct = fenced
        if not fenced:
            try:
                code, _, _ = lab.http(endpoint, "/api/v1/accounts/not-a-uuid/summary", token)
                fresh = code == 503
            except (ConnectionRefusedError, ConnectionResetError, BrokenPipeError, http.client.RemoteDisconnected):
                fresh = True
            existing = connection is None
            if connection is not None:
                try:
                    code, _, _ = lab.http(endpoint, "/api/v1/accounts/not-a-uuid/summary", token, connection)
                    existing = code == 503
                except (ConnectionRefusedError, ConnectionResetError, BrokenPipeError, http.client.RemoteDisconnected):
                    existing = True
                if existing:
                    connection.close()
                    connections[index] = None
            direct = lab.direct_rejected(endpoint, token)
            if not (fresh and existing and direct):
                fenced = lab.stopped(endpoint)  # The generation may have exited during the HTTP observation.
                if fenced:
                    fresh = existing = direct = True
        if fenced and connection is not None:
            connection.close()
            connections[index] = None
        observations.append({"pod": endpoint["pod"], "newConnectionRejected": fresh,
            "existingConnectionRejected": existing, "directPodPathRejected": direct,
            "runtimeFenceVerified": fenced,
            "evidenceKind": "stopped-generation" if fenced else "transport-rejection",
            "fencedContainer": endpoint["container"] if fenced else None})
    return observations if all(row["newConnectionRejected"] and row["existingConnectionRejected"]
                               and row["directPodPathRejected"] for row in observations) else None


def drain(lab, endpoints, seconds=20, force_after=8):
    """Accept verified normal completion, or terminate remaining owned tasks within the budget."""
    started = time.monotonic()
    forced = []
    while not all(lab.stopped(task) for task in endpoints):
        elapsed = time.monotonic() - started
        if elapsed >= seconds:
            raise RuntimeError("20-second drain deadline exceeded; verification cannot extend it")
        if elapsed >= force_after:
            for task in endpoints:
                if task["container"] not in forced and not lab.stopped(task):
                    lab.signal(task, "KILL")
                    forced.append(task["container"])
        time.sleep(0.1)
    remaining = lab.running_tasks("query-service")
    elapsed = time.monotonic() - started
    if elapsed > seconds:
        raise RuntimeError("20-second drain deadline exceeded; verification cannot extend it")
    if remaining:
        raise RuntimeError("An old Query generation remains outside the captured endpoint set")
    return {"forcedContainers": forced, "elapsedSeconds": elapsed, "remainingQueryTasks": remaining}


def stop(lab, endpoints, old_bundle, generation, submitted, slow=False):
    credentials = json.loads((old_bundle / "credentials.json").read_text())
    token = credentials["client-c"]["token"]
    identities = json.loads((old_bundle / "fixture-identities.json").read_text())
    connections = []
    for endpoint in endpoints:
        connection = http.client.HTTPConnection("127.0.0.1", endpoint["port"], timeout=2)
        if lab.http(endpoint, "/api/v1/accounts/" + identities["accounts"]["X"] + "/summary", token, connection)[0] != 200:
            raise RuntimeError("Existing connection was not authenticated before Block")
        if connection.sock is None:
            raise RuntimeError("The baseline did not retain a real keep-alive connection")
        connections.append(connection)
        if lab.direct_status(endpoint, token) != 400:
            raise RuntimeError("Direct Pod path was not available before the revocation test")
    lock = None
    executor = concurrent.futures.ThreadPoolExecutor(max_workers=32)
    futures = []
    admitted_reads = 0
    try:
        running = lab.running_tasks("query-service")
        if {task["container"] for task in running} != {endpoint["container"] for endpoint in endpoints}:
            raise RuntimeError("Query runtime contains an uncaptured serving/terminating generation")
        if slow:
            lock = subprocess.Popen(["kubectl", "--context", lab.context, "-n", lab.namespace,
                "exec", "-i", "postgres-0", "--", "psql", "-U", "simplematch", "-d", "simplematch", "-At"],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            lock.stdin.write("BEGIN; LOCK TABLE query_service.account_summary_read_model IN ACCESS EXCLUSIVE MODE; SELECT 'LOCKED';\n")
            lock.stdin.flush()
            wait_for("owned account lock", lambda: any(row["locked"] for row in lab.sql(
                "SELECT json_build_object('locked',exists(SELECT 1 FROM pg_locks l JOIN pg_class c ON c.oid=l.relation "
                "WHERE c.relname='account_summary_read_model' AND l.mode='AccessExclusiveLock' AND l.granted))")), 5)
            for endpoint in endpoints:
                for _ in range(16):
                    # The client budget deliberately exceeds both the 5s SQL and 20s drain budgets.
                    connection = http.client.HTTPConnection("127.0.0.1", endpoint["port"], timeout=25)
                    connections.append(connection)
                    futures.append(executor.submit(lab.http, endpoint,
                        "/api/v1/accounts/" + identities["accounts"]["Y"] + "/summary", token, connection))
            def admitted():
                count = lab.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock'")[0]
                return count if count >= 2 else None
            admitted_reads = wait_for("real admitted JDBC reads", admitted, 3)
        lab.guard()
        lab.kube("scale", "deployment/query-service", "--replicas=0")
        keep_alive = connections[:len(endpoints)]
        observations = wait_for("all direct/new/keep-alive paths acknowledging Block",
                                lambda: blocked(lab, endpoints, token, keep_alive), 10)
        blocked_at = time.monotonic()
        pending_at_block = sum(not future.done() for future in futures)
        drained = drain(lab, endpoints)
        if time.monotonic() - blocked_at > 20 or time.monotonic() - submitted > 90:
            raise RuntimeError("Old Query generation exceeded the bounded stop verification window")
        return lab.save(f"generation-{generation}-fence.json", {
            "runId": lab.run_id, "namespace": lab.namespace, "namespaceUid": lab.uid,
            "imageConfigId": lab.image_config_id, "retiredPolicyVersion": generation - 1,
            "blockedPaths": observations, "oldTasks": endpoints, "drain": drained,
            "admittedRequests": len(futures), "admittedBlockedReads": admitted_reads,
            "pendingAtBlock": pending_at_block,
            "pendingAtTermination": sum(not f.done() for f in futures),
            "jdbcStatementBudgetSeconds": 5, "httpClientBudgetSeconds": 25,
            "blockedToDrainedSeconds": time.monotonic() - blocked_at,
            "submittedToStoppedSeconds": time.monotonic() - submitted,
            "oldProcessesStopped": True, "observedAtEpochNs": time.time_ns()})
    finally:
        for connection in connections:
            connection.close()
        if lock:
            try:
                lock.communicate("ROLLBACK;\n\\q\n", timeout=5)
            except (BrokenPipeError, subprocess.TimeoutExpired):
                lock.kill()
                lock.communicate()
        executor.shutdown(wait=True, cancel_futures=True)


def lifecycle(lab, previous, generation, profile, slow=False):
    current = lab.bundle(generation, profile)
    candidate = inspect_replacement(previous["bundle"], current, previous["policy"])
    if candidate["version"] != generation:
        raise RuntimeError("Unexpected next Query policy generation")
    submitted = time.monotonic()
    fence = stop(lab, previous["endpoints"], previous["bundle"], generation, submitted, slow)
    # No old generation is restarted after failure; ownership teardown is the only recovery.
    if generation == 2:
        lab.publish(current, "paused")
    lab.deploy(current, candidate)
    endpoints = lab.endpoints(lab.ready())
    catchup = lab.catchup()
    observations = lab.matrix(endpoints, current, generation, candidate, disabled=profile == "d-disabled")
    inspect_unchanged(previous["bundle"], previous["policy"])
    rejections = reject_old(lab, endpoints, previous["bundle"])
    lab.save(f"generation-{generation}-serving.json", {
        "runId": lab.run_id, "namespace": lab.namespace, "namespaceUid": lab.uid,
        "imageConfigId": lab.image_config_id, "policyVersion": generation,
        "policyDigest": candidate["digest"], "fenceEvidence": fence.name,
        "replicaChecks": observations, "retiredCredentials": rejections,
        "catchup": catchup, "observedAtEpochNs": time.time_ns()})
    return {"bundle": current, "policy": candidate, "endpoints": endpoints}


def run(arguments):
    cluster = preflight(arguments.context, arguments.docker_context)
    labs = []
    result = {"status": "FAIL", "scope": "synthetic Query lifecycle only", "runs": []}
    try:
        for fresh in (False, True):
            if fresh:
                preflight(arguments.context, arguments.docker_context)
            lab = Lab(REPOSITORY, arguments.directory / ("fresh-run" if fresh else "revocation-run"),
                      arguments.context, arguments.docker_context, arguments.image)
            labs.append(lab)
            if not fresh:
                images(lab, cluster)
            else:
                lab.image_config_id = labs[0].image_config_id
            first = lab.create()
            first_policy = inspect_bundle(first)
            lab.platform()
            lab.seed_orders(first)
            lab.publish(first, "baseline")
            lab.deploy(first, first_policy)
            endpoints = lab.endpoints(lab.ready())
            initial_catchup = lab.catchup()
            matrix = lab.matrix(endpoints, first, 1, first_policy)
            lab.save("generation-1-serving.json", {
                "runId": lab.run_id, "namespace": lab.namespace, "namespaceUid": lab.uid,
                "imageConfigId": lab.image_config_id, "policyVersion": 1,
                "policyDigest": first_policy["digest"], "replicaChecks": matrix,
                "catchup": initial_catchup, "observedAtEpochNs": time.time_ns()})
            final_policy = first_policy
            if not fresh:
                second = lifecycle(lab, {"bundle": first, "policy": first_policy, "endpoints": endpoints},
                                   2, "c-y-revoked", slow=True)
                final_policy = lifecycle(lab, second, 3, "d-disabled")["policy"]
                identities = json.loads((first / "fixture-identities.json").read_text())
            else:
                new = json.loads((first / "fixture-identities.json").read_text())
                if set(new["principals"].values()) & set(identities["principals"].values()) or set(new["accounts"].values()) & set(identities["accounts"].values()):
                    raise RuntimeError("A fresh run reused retired synthetic identities")
            result["runs"].append({"namespace": lab.namespace, "namespaceUid": lab.uid,
                                   "imageSourceId": lab.image_id, "imageConfigId": lab.image_config_id,
                                   "recordedInputs": len(lab.receipts),
                                   "finalPolicyVersion": final_policy["version"],
                                   "finalPolicyDigest": final_policy["digest"]})
            lab.destroy()
        result["status"] = "PASS"
    except BaseException as error:
        result["failure"] = str(error)
        diagnostics = arguments.directory / "private"
        diagnostics.mkdir(mode=0o700, exist_ok=True)
        (diagnostics / "failure.log").write_text(traceback.format_exc())
        raise
    finally:
        for lab in labs:
            try:
                lab.clean_probes()
            except Exception as error:
                result["status"] = "FAIL"
                result["probeCleanupFailure"] = type(error).__name__
            if lab.uid and not (lab.root / "teardown.json").exists():
                try:
                    lab.destroy()
                except Exception as error:
                    result["status"] = "FAIL"
                    result["cleanupFailure"] = type(error).__name__
        arguments.directory.mkdir(parents=True, exist_ok=True)
        (arguments.directory / "verdict.json").write_text(json.dumps(result, indent=2) + "\n")
    if result["status"] != "PASS":
        raise RuntimeError("Cleanup failed; a new initial run remains forbidden")


def interrupted(*_):
    """Let normal owned-resource cleanup run on process termination."""
    raise KeyboardInterrupt()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("preflight", "run"))
    parser.add_argument("--context", default="kind-simplematch-live")
    parser.add_argument("--docker-context", default="desktop-linux")
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--image")
    arguments = parser.parse_args()
    os.umask(0o077)
    signal.signal(signal.SIGTERM, interrupted)
    try:
        if arguments.operation == "preflight":
            arguments.directory.mkdir(parents=True, exist_ok=True)
            (arguments.directory / "preflight.json").write_text(json.dumps(preflight(
                arguments.context, arguments.docker_context), indent=2) + "\n")
        else:
            run(arguments)
        return 0
    except Exception as error:
        print("Query security lifecycle failed: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
