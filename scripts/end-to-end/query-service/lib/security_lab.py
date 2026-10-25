"""Concrete, run-owned Kubernetes/CRI observations for the synthetic Query lifecycle test."""

import base64
import copy
import hashlib
import http.client
import json
import os
import secrets
import socket
import subprocess
import sys
import time
import uuid
from pathlib import Path


LABEL = "simplematch.io/query-security-run"


def rendered_objects(repository, context):
    """Render and parse offline using the repository's existing Ruby YAML dependency."""
    rendered = subprocess.check_output(["kubectl", "kustomize", str(repository / "deploy/k8s/overlays/local"),
        "--load-restrictor", "LoadRestrictionsNone"], text=True, timeout=30)
    return json.loads(subprocess.check_output(["ruby", "-ryaml", "-rjson", "-e",
        "puts JSON.generate(YAML.load_stream(STDIN.read).compact)"],
        input=rendered, text=True, timeout=30))


def wait_for(description, observation, seconds=120):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = observation()
        if value and time.monotonic() <= deadline:
            return value
        time.sleep(0.2)
    raise RuntimeError("Timed out: " + description)


def validate_objects(objects):
    """Reject unresolved resources, keys and artifact paths in the effective Lab manifest."""
    resources = {(item["kind"], item["metadata"]["name"]): item for item in objects}

    def reference(kind, source, name_key="name", keys=()):
        if source.get("optional", False):
            return
        item = resources.get((kind, source[name_key]))
        if item is None:
            raise RuntimeError("Lab manifest has an unresolved " + kind + " reference")
        available = set(item.get("data", {})) | set(item.get("stringData", {}))
        if not set(keys).issubset(available):
            raise RuntimeError("Lab manifest has a missing " + kind + " key")

    for item in objects:
        if item["kind"] not in ("Deployment", "StatefulSet", "Job"):
            continue
        spec = item["spec"]["template"]["spec"]
        account = spec.get("serviceAccountName", "default")
        if account != "default" and ("ServiceAccount", account) not in resources:
            raise RuntimeError("Lab manifest has an unresolved ServiceAccount")
        volumes = {v["name"]: v for v in spec.get("volumes", [])}
        for volume in volumes.values():
            for field, kind, name in (("secret", "Secret", "secretName"), ("configMap", "ConfigMap", "name")):
                if field in volume:
                    source = volume[field]
                    reference(kind, source, name, [entry["key"] for entry in source.get("items", [])])
        for container in spec.get("containers", []) + spec.get("initContainers", []):
            mounts = container.get("volumeMounts", [])
            if any(mount["name"] not in volumes for mount in mounts):
                raise RuntimeError("Lab manifest has an unresolved volume mount")
            for value in container.get("envFrom", []):
                for field, kind in (("secretRef", "Secret"), ("configMapRef", "ConfigMap")):
                    if field in value:
                        reference(kind, value[field])
            for value in container.get("env", []):
                for field, kind in (("secretKeyRef", "Secret"), ("configMapKeyRef", "ConfigMap")):
                    source = value.get("valueFrom", {}).get(field)
                    if source:
                        reference(kind, source, keys=[source["key"]])
                if value["name"].endswith(("MARKET_REFERENCE_ARTIFACT_LOCATION", "MARKET_REFERENCE_CHECKSUM_LOCATION")):
                    uri = value.get("value", "")
                    if not uri.startswith("file:/"):
                        raise RuntimeError("Lab manifest artifact location requires a file: URI")
                    path = Path(uri.removeprefix("file:"))
                    resolved = False
                    for mount in mounts:
                        root = Path(mount["mountPath"])
                        if not path.is_relative_to(root):
                            continue
                        source = volumes[mount["name"]].get("configMap")
                        if source:
                            reference("ConfigMap", source, keys=[str(path.relative_to(root))])
                            resolved = True
                    if not resolved:
                        raise RuntimeError("Lab manifest artifact URI does not resolve to its ConfigMap mount")


class Lab:
    """Own one namespace; never mutate the canonical cluster or another run's resources."""

    def __init__(self, repository, directory, context, docker_context, image):
        self.repo, self.root = Path(repository), Path(directory)
        self.root.mkdir(mode=0o700)
        self.private = self.root / "private"
        self.private.mkdir(mode=0o700)
        self.context, self.docker_context, self.image = context, docker_context, image
        self.run_id = str(uuid.uuid4())
        self.namespace = "query-security-" + uuid.UUID(self.run_id).hex[:12]
        self.uid = None
        self.authority = self.private / "authority"
        self.forwards, self.containers = [], []
        self.probes = []
        self.counter = 0
        self.password = secrets.token_hex(32)
        self.expected = {}
        self.receipts = []

    def command(self, arguments, data=None, timeout=30, check=True, environment=None):
        result = subprocess.run(arguments, input=data, capture_output=True, text=True,
                                timeout=timeout, env=environment)
        if check and result.returncode:
            self.counter += 1
            # Raw diagnostics stay private; the public verdict never embeds command output.
            path = self.private / f"command-{self.counter}.log"
            path.write_text(result.stdout + result.stderr)
            raise RuntimeError(f"{Path(arguments[0]).name} failed; private diagnostic {path.name}")
        return result

    def kube(self, *arguments, **options):
        return self.command(["kubectl", "--context", self.context,
                             "--request-timeout=15s", "-n", self.namespace, *arguments], **options)

    def docker(self, *arguments, **options):
        return self.command(["docker", "--context", self.docker_context, *arguments], **options)

    def probe(self, node, image, command):
        """Persist an exact-owned raw containerd probe before its potentially ambiguous launch."""
        probe = {"node": node, "id": "qs193-" + uuid.uuid4().hex, "absent": False}
        existing = self.docker("exec", node, "ctr", "-n", "k8s.io", "containers", "info", probe["id"],
                               timeout=10, check=False)
        if not self.not_found(existing):
            raise RuntimeError("Probe identity is not positively unused")
        self.probes.append(probe)
        self.save("image-probes.json", self.probes)
        try:
            self.docker("exec", node, "ctr", "-n", "k8s.io", "run", "--rm", "--net-host",
                        "--label", LABEL + "=" + self.run_id,
                        "--snapshotter-label", LABEL + "=" + self.run_id,
                        image, probe["id"], *command, timeout=20)
        finally:
            self.clean_probes()

    @staticmethod
    def not_found(result):
        return result.returncode != 0 and any(value in result.stderr.lower() for value in ("not found", "notfound"))

    def clean_probes(self):
        """Clean raw probes independently of namespace existence and verify exact-ID absence."""
        for probe in self.probes:
            if probe["absent"]:
                continue
            arguments = ("exec", probe["node"], "ctr", "-n", "k8s.io")
            detail = self.docker(*arguments, "containers", "info", probe["id"], timeout=10, check=False)
            tasks = self.docker(*arguments, "tasks", "ls", "-q", timeout=10).stdout.split()
            if detail.returncode == 0:
                if json.loads(detail.stdout)["Labels"].get(LABEL) != self.run_id:
                    raise RuntimeError("Refusing cleanup of an unowned image probe")
                if probe["id"] in tasks:
                    self.docker(*arguments, "tasks", "delete", "--force", probe["id"], timeout=10)
                self.docker(*arguments, "containers", "delete", probe["id"], timeout=10)
            elif not self.not_found(detail) or probe["id"] in tasks:
                raise RuntimeError("Image probe cleanup lacks positive container ownership/absence")
            snapshot = self.docker(*arguments, "snapshots", "info", probe["id"], timeout=10, check=False)
            if snapshot.returncode == 0:
                if json.loads(snapshot.stdout).get("Labels", {}).get(LABEL) != self.run_id:
                    raise RuntimeError("Refusing cleanup of an unowned image probe snapshot")
                self.docker(*arguments, "snapshots", "remove", probe["id"], timeout=10)
                snapshot = self.docker(*arguments, "snapshots", "info", probe["id"], timeout=10, check=False)
            remaining = self.docker(*arguments, "containers", "info", probe["id"], timeout=10, check=False)
            tasks = self.docker(*arguments, "tasks", "ls", "-q", timeout=10).stdout.split()
            if not self.not_found(snapshot) or not self.not_found(remaining) or probe["id"] in tasks:
                raise RuntimeError("Image probe task/container/snapshot absence is unproven")
            probe["absent"] = True
            self.save("image-probes.json", self.probes)

    def save(self, name, value):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, indent=2) + "\n")
        return path

    def namespace_state(self):
        value = self.kube("get", "namespace", self.namespace, "--ignore-not-found", "-o", "json").stdout
        return json.loads(value) if value.strip() else None

    def guard(self):
        namespace = self.namespace_state()
        if not namespace or namespace["metadata"]["uid"] != self.uid or namespace["metadata"]["labels"].get(LABEL) != self.run_id:
            raise RuntimeError("Namespace ownership changed")

    def apply(self, objects):
        self.guard()
        for item in objects:
            item.setdefault("metadata", {})["namespace"] = self.namespace
            item["metadata"].setdefault("labels", {})[LABEL] = self.run_id
        self.kube("apply", "-f", "-", data=json.dumps({"apiVersion": "v1", "kind": "List", "items": objects}))

    def approval(self, operation, *arguments, check=True):
        result = self.command([sys.executable, str(self.repo / "scripts/end-to-end/query-service/policy-authority.py"),
                               operation, str(self.authority), *map(str, arguments)], check=check)
        return json.loads(result.stdout) if result.returncode == 0 else None

    def bundle(self, generation, profile="initial"):
        target = self.private / f"generation-{generation}"
        arguments = [sys.executable, str(self.repo / "scripts/end-to-end/query-service/provision-security-fixtures.py"),
                     str(target), "--policy-version", str(generation)]
        if generation > 1:
            arguments += ["--previous-directory", str(self.private / f"generation-{generation-1}"), "--profile", profile]
        self.command(arguments)
        return target

    def proof(self, name, **facts):
        status = self.approval("status")
        value = {key: status[key] for key in ("runId", "namespace", "namespaceUid", "image",
                                              "activationId", "verificationId")}
        value.update(event=name, policyVersion=status["version"], policyDigest=status["digest"],
                     observedAtEpochNs=time.time_ns(), observedAtMonotonicNs=time.monotonic_ns(),
                     eligibleReplicas=2, **facts)
        return self.save(f"v{status['version']}-{name}-{len(list(self.root.glob('*.json')))}.json", value)

    def create(self):
        active = json.loads(self.kube("get", "namespaces", "-l", LABEL, "-o", "json").stdout)["items"]
        if active:
            raise RuntimeError("A Query security run still exists; do not initialize another V1")
        self.image_id = json.loads(self.docker("image", "inspect", self.image).stdout)[0]["Id"]
        value = {"apiVersion": "v1", "kind": "Namespace", "metadata": {"name": self.namespace,
                 "labels": {LABEL: self.run_id, "simplematch.io/lifecycle": "disposable-certification"}}}
        self.save("owner-intent.json", {"runId": self.run_id, "namespace": self.namespace,
                                       "context": self.context, "dockerContext": self.docker_context})
        try:
            created = json.loads(self.kube("create", "-f", "-", "-o", "json", data=json.dumps(value)).stdout)
            self.record_owner(created)
        except (RuntimeError, subprocess.TimeoutExpired, ValueError, KeyError):
            # A successful API mutation can lose its response. Recover identity only for cleanup.
            try:
                observed = self.namespace_state()
                if observed:
                    self.record_owner(observed)
            except (RuntimeError, subprocess.TimeoutExpired, ValueError, KeyError):
                self.save("ownership-recovery.json", {"status": "BLOCKED", "newV1Allowed": False})
            raise
        first = self.bundle(1)
        self.approval("create", "--run-id", self.run_id, "--namespace", self.namespace,
                      "--namespace-uid", self.uid, "--image", self.image_id, "--bundle", first)
        return first

    def record_owner(self, namespace):
        metadata = namespace["metadata"]
        uid = metadata["uid"]
        if (metadata["name"] != self.namespace or metadata["labels"].get(LABEL) != self.run_id
                or str(uuid.UUID(uid)) != uid):
            raise RuntimeError("Namespace response does not establish this run's ownership")
        self.uid = uid
        self.save("owner.json", {"runId": self.run_id, "namespace": self.namespace,
                                 "namespaceUid": self.uid, "context": self.context,
                                 "dockerContext": self.docker_context})

    def render(self):
        items = rendered_objects(self.repo, self.context)
        keep = []
        for item in items:
            kind, name = item["kind"], item["metadata"]["name"]
            if kind == "Deployment" and name == "query-service":
                self.query_template = copy.deepcopy(item)
            elif ((name in ("postgres", "kafka", "kafka-headless", "kafka-kraft-config", "kafka-topic-provisioning")
                   and kind in ("Service", "StatefulSet", "ConfigMap", "PodDisruptionBudget", "Job"))
                  or kind == "NetworkPolicy" or (kind == "ServiceAccount" and name == "query-service")
                  or (kind == "Service" and name == "query-service")):
                if kind == "StatefulSet":
                    claims = item["spec"].pop("volumeClaimTemplates", [])
                    item["spec"].pop("persistentVolumeClaimRetentionPolicy", None)
                    volumes = item["spec"]["template"]["spec"].setdefault("volumes", [])
                    volumes.extend({"name": claim["metadata"]["name"], "emptyDir": {}} for claim in claims)
                keep.append(item)
        if not hasattr(self, "query_template") or len([i for i in keep if i["kind"] == "StatefulSet"]) != 2:
            raise RuntimeError("Focused manifest did not resolve the existing platform resources")
        return keep

    def platform_objects(self):
        """Prepare the exact focused platform objects without applying resources."""
        objects = self.render()
        objects.append({"apiVersion": "v1", "kind": "Secret", "metadata": {"name": "simplematch-postgres-secrets"},
                        "immutable": True, "stringData": {"postgres_password": self.password}})
        artifact = (self.repo / "shared-java/market-reference-contract/src/test/resources/market-reference-fixtures/market_reference.json").read_text()
        checksum = hashlib.sha256(artifact.encode()).hexdigest()
        objects.append({"apiVersion": "v1", "kind": "ConfigMap", "metadata": {"name": "matching-daily-artifact"},
                        "immutable": True, "data": {"market_reference.json": artifact,
                        "market_reference.sha256": checksum + "\n"}})
        self.artifact_id = "2026-08-11:" + checksum
        return objects

    def platform(self):
        self.apply(self.platform_objects())
        for workload in ("statefulset/postgres", "statefulset/kafka"):
            def ready():
                state = json.loads(self.kube("get", workload, "-o", "json").stdout)
                return state["status"].get("readyReplicas", 0) == state["spec"]["replicas"]
            wait_for("existing " + workload + " ready replicas", ready, 180)
        # Preserve the existing provisioning Job's bounded startup contract, not the later catch-up budget.
        self.job("kafka-topic-provisioning", 600)
        self.pg_port = self.forward("pod/postgres-0", 5432)
        environment = dict(os.environ, SIMPLEMATCH_POSTGRES_DSN=
                           f"postgresql://simplematch:{self.password}@127.0.0.1:{self.pg_port}/simplematch")
        with (self.private / "flyway.log").open("w") as log:
            completed = subprocess.run([str(self.repo / "gradlew"), "-q", "queryServiceFlywayMigrate"],
                cwd=self.repo, env=environment, stdout=log, stderr=subprocess.STDOUT, timeout=180)
        if completed.returncode:
            raise RuntimeError("Query Flyway migration failed; reads remain unavailable")
        history = self.sql("SELECT json_build_object('version',version,'type',type,'success',success) "
                           "FROM query_service.flyway_schema_history ORDER BY installed_rank")
        self.save("flyway-history.json", history)
        migrations = [row for row in history if row["version"] is not None]
        if [row["version"] for row in migrations] != ["1", "2", "3"] or not all(row["success"] for row in history):
            raise RuntimeError("Query migration history is incomplete")

    def plan(self):
        """Validate the same platform and Query configuration used later, before any deployment."""
        objects = self.platform_objects()
        objects += [
            {"kind": "Secret", "metadata": {"name": "query-lab-dsn"}, "stringData": {"dsn": "preflight-only"}},
            {"kind": "Secret", "metadata": {"name": "query-policy-preflight"},
             "stringData": {"application.properties": "preflight-only"}},
            self.deployment("query-policy-preflight", "preflight-only")]
        validate_objects(objects)
        return [{"kind": item["kind"], "name": item["metadata"]["name"]} for item in objects]

    def job(self, name, seconds):
        """Keep failed Job diagnostics before teardown; do not wait out a known terminal failure."""
        def complete():
            status = json.loads(self.kube("get", "job/" + name, "-o", "json").stdout).get("status", {})
            if any(c["type"] == "Failed" and c["status"] == "True" for c in status.get("conditions", [])):
                log = self.kube("logs", "job/" + name, "--all-containers=true", "--tail=100", check=False)
                (self.private / (name + "-failure.log")).write_text(log.stdout + log.stderr)
                raise RuntimeError("Owned fixture/platform Job failed: " + name)
            return status.get("succeeded", 0) == 1
        wait_for("owned Job " + name, complete, seconds)

    def sql(self, statement):
        result = self.kube("exec", "postgres-0", "--", "psql", "-U", "simplematch", "-d", "simplematch",
                           "-At", "-v", "ON_ERROR_STOP=1", "-c", statement)
        return [json.loads(line) for line in result.stdout.splitlines() if line]

    def forward(self, target, remote):
        with socket.socket() as server:
            server.bind(("127.0.0.1", 0))
            port = server.getsockname()[1]
        log = (self.private / f"forward-{port}.log").open("w")
        process = subprocess.Popen(["kubectl", "--context", self.context, "-n", self.namespace,
                                    "port-forward", "--address=127.0.0.1", target, f"{port}:{remote}"],
                                   stdout=log, stderr=subprocess.STDOUT)
        self.forwards.append((process, log))
        def connected():
            if process.poll() is not None:
                raise RuntimeError("Port-forward exited before serving")
            try:
                with socket.create_connection(("127.0.0.1", port), timeout=0.1):
                    return True
            except OSError:
                return False
        wait_for("port-forward", connected, 15)
        return port

    def pods(self):
        return json.loads(self.kube("get", "pods", "-l", "app.kubernetes.io/name=query-service",
                                    "-o", "json").stdout)["items"]

    def task(self, pod, name="query-service", verification=True):
        status = next(s for s in pod["status"]["containerStatuses"] if s["name"] == name)
        if verification and status["restartCount"] != 0:
            raise RuntimeError("Unexpected Query restart before lifecycle capture")
        container = status["containerID"].removeprefix("containerd://")
        node = pod["spec"]["nodeName"]
        detail = json.loads(self.docker("exec", node, "crictl", "inspect", container).stdout)
        if detail["status"]["labels"].get("io.kubernetes.pod.uid") != pod["metadata"]["uid"]:
            raise RuntimeError("CRI task is not the observed Query Pod")
        if not verification and detail["status"]["state"] == "CONTAINER_EXITED":
            return None  # Positive CRI exit is not a failed /proc/0 observation.
        if detail["status"]["state"] != "CONTAINER_RUNNING":
            raise RuntimeError("CRI has not established the expected running task")
        if verification and name == "query-service":
            image = json.loads(self.docker("exec", node, "crictl", "inspecti", detail["status"]["imageRef"]).stdout)
            if image["status"]["id"] != self.image_config_id:
                raise RuntimeError("Running Query container uses an unapproved image configuration")
        pid = detail["info"]["pid"]
        start = self.docker("exec", node, "cat", f"/proc/{pid}/stat").stdout.split()[21]
        result = {"pod": pod["metadata"]["name"], "uid": pod["metadata"]["uid"], "node": node, "name": name,
                  "container": container, "pid": pid, "start": start, "ip": pod["status"]["podIP"]}
        self.containers.append(result)
        self.save("observed-tasks.json", self.containers)
        return result

    def running_tasks(self, container_name=None):
        """Include orphaned/unready tasks; Service and Pod readiness do not establish fencing."""
        nodes = json.loads(self.kube("get", "nodes", "-l", "simplematch.io/node-pool=local-resilience", "-o", "json").stdout)["items"]
        tasks = []
        for node in nodes:
            name = node["metadata"]["name"]
            containers = json.loads(self.docker("exec", name, "crictl", "ps", "-o", "json").stdout)["containers"]
            tasks += [{"node": name, "container": c["id"], "uid": c["labels"]["io.kubernetes.pod.uid"]}
                      for c in containers if c["labels"].get("io.kubernetes.pod.namespace") == self.namespace
                      and (container_name is None or c["metadata"]["name"] == container_name)]
        return tasks

    def stopped(self, task):
        result = self.docker("exec", task["node"], "crictl", "inspect", task["container"], check=False)
        if result.returncode and "not found" not in result.stderr.lower() and "notfound" not in result.stderr.lower():
            raise RuntimeError("CRI observation failed; task absence is unproven")
        detail = json.loads(result.stdout) if result.returncode == 0 else None
        if detail and detail["status"]["state"] == "CONTAINER_RUNNING":
            return False
        tasks = self.docker("exec", task["node"], "ctr", "-n", "k8s.io", "tasks", "ls", "-q").stdout.split()
        if task["container"] in tasks:
            return False
        process = self.docker("exec", task["node"], "cat", f"/proc/{task['pid']}/stat", check=False)
        if process.returncode:
            if "No such file or directory" not in process.stderr:
                raise RuntimeError("Process observation failed; process absence is unproven")
            return True
        return process.stdout.split()[21] != task["start"]

    def signal(self, task, name):
        if self.stopped(task):
            return
        detail = json.loads(self.docker("exec", task["node"], "crictl", "inspect", task["container"]).stdout)
        if detail["status"]["labels"].get("io.kubernetes.pod.uid") != task["uid"]:
            raise RuntimeError("Refusing to signal a changed container identity")
        process = self.docker("exec", task["node"], "cat", f"/proc/{task['pid']}/stat", check=False)
        if process.returncode and "No such file or directory" in process.stderr:
            return  # The caller still verifies full CRI/task absence before declaring fencing.
        if process.returncode or process.stdout.split()[21] != task["start"]:
            raise RuntimeError("Refusing to signal a reused PID")
        self.docker("exec", task["node"], "/bin/kill", "-" + name, str(task["pid"]))

    def deployment(self, secret, digest, partial=False):
        """Prepare the exact Query pod configuration for preflight and activation."""
        deployment = copy.deepcopy(self.query_template)
        deployment["spec"]["replicas"] = 2
        pod = deployment["spec"]["template"]
        pod["metadata"]["labels"][LABEL] = self.run_id
        pod["metadata"].setdefault("annotations", {})["simplematch.io/query-policy-digest"] = digest
        spec = pod["spec"]
        spec["automountServiceAccountToken"] = False
        # This focused runner completes and verifies Flyway before applying Query.
        spec.pop("initContainers", None)
        if partial:
            spec.setdefault("nodeSelector", {})["simplematch.io/worker-slot"] = "1"
            spec["affinity"] = {"podAntiAffinity": {"requiredDuringSchedulingIgnoredDuringExecution": [{
                "labelSelector": {"matchLabels": {"app.kubernetes.io/name": "query-service"}},
                "topologyKey": "kubernetes.io/hostname"}]}}
        container = next(c for c in spec["containers"] if c["name"] == "query-service")
        container["image"], container["imagePullPolicy"] = self.image, "Never"
        environment = {
            "SPRING_PROFILES_ACTIVE": "local", "SIMPLEMATCH_KUBERNETES_ENABLED": "false",
            "SPRING_CLOUD_KUBERNETES_ENABLED": "false", "OTEL_SDK_DISABLED": "true",
            "SIMPLEMATCH_KUBERNETES_CONFIG_IMPORT": "optional:classpath:/query-lab-none.properties",
            "SPRING_CONFIG_ADDITIONAL_LOCATION": "file:/etc/query-policy/application.properties",
            "SIMPLEMATCH_KAFKA_BROKERS": "kafka:9092", "SIMPLEMATCH_QUERY_SERVICE_HTTP_PORT": "8086",
            "SIMPLEMATCH_QUERY_SERVICE_MATCHING_EVENTS_ENABLED": "true",
            "SIMPLEMATCH_QUERY_SERVICE_ACCOUNT_LIFECYCLE_ENABLED": "true",
            "SIMPLEMATCH_QUERY_SERVICE_REDIS_ENABLED": "false",
            "SIMPLEMATCH_QUERY_SERVICE_REBUILD_HTTP_ENABLED": "false",
            "SIMPLEMATCH_QUERY_SERVICE_MARKET_REFERENCE_INSTALL_ON_STARTUP": "true",
            "SIMPLEMATCH_QUERY_SERVICE_MARKET_REFERENCE_TRADING_DAY": "2026-08-11",
            "SIMPLEMATCH_QUERY_SERVICE_MARKET_REFERENCE_ARTIFACT_LOCATION": "file:/etc/simplematch/market-reference/market_reference.json",
            "SIMPLEMATCH_QUERY_SERVICE_MARKET_REFERENCE_CHECKSUM_LOCATION": "file:/etc/simplematch/market-reference/market_reference.sha256",
            "SERVER_SHUTDOWN": "graceful", "SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE": "10s"}
        container["env"] = [{"name": name, "value": value} for name, value in environment.items()]
        container["env"].append({"name": "SIMPLEMATCH_POSTGRES_DSN", "valueFrom":
                                  {"secretKeyRef": {"name": "query-lab-dsn", "key": "dsn"}}})
        container["volumeMounts"].append({"name": "query-policy", "mountPath": "/etc/query-policy", "readOnly": True})
        spec["volumes"].append({"name": "query-policy", "secret": {"secretName": secret}})
        return deployment

    def deploy(self, bundle, partial=False):
        self.guard()
        approval = self.approval("check", "--bundle", bundle, "--image", self.image_id,
                                 "--run-id", self.run_id, "--namespace", self.namespace,
                                 "--namespace-uid", self.uid)
        if approval["phase"] != ("APPROVED" if approval["version"] == 1 else "VERIFYING"):
            raise RuntimeError("Policy eligibility alone does not authorize deployment in this lifecycle state")
        policy = (bundle / "application.properties").read_text()
        secret = "query-policy-" + approval["digest"][:16]
        self.apply([{"apiVersion": "v1", "kind": "Secret", "metadata": {"name": secret},
                     "immutable": True, "stringData": {"application.properties": policy}}])
        deployment = self.deployment(secret, approval["digest"], partial)
        spec = deployment["spec"]["template"]["spec"]
        for volume in spec["volumes"]:
            for kind, reference in (("secret", "secretName"), ("configMap", "name")):
                source = volume.get(kind)
                if source and not source.get("optional", False):
                    self.kube("get", kind.lower(), source[reference], "-o", "name")
        self.recovery_started = time.monotonic()
        self.recovery_epoch_ns = time.time_ns()
        self.apply([{"apiVersion": "v1", "kind": "Secret", "metadata": {"name": "query-lab-dsn"},
                     "immutable": True, "stringData": {"dsn": f"postgresql://simplematch:{self.password}@postgres:5432/simplematch"}},
                    deployment])

    def ready(self, count=2):
        def observed():
            pods = self.pods()
            live = [p for p in pods if not p["metadata"].get("deletionTimestamp") and
                    any(c["type"] == "Ready" and c["status"] == "True" for c in p["status"].get("conditions", []))]
            if len(live) != count:
                return None
            if count == 2:
                deployment = json.loads(self.kube("get", "deployment/query-service", "-o", "json").stdout)
                status = deployment["status"]
                if (len(pods) != 2 or status.get("observedGeneration", 0) < deployment["metadata"]["generation"]
                        or status.get("updatedReplicas") != 2 or status.get("replicas") != 2):
                    return None
            return live
        return wait_for("each eligible Query replica", observed)

    def endpoints(self, pods):
        return [{**self.task(p), "port": self.forward("pod/" + p["metadata"]["name"], 8086)} for p in pods]

    def http(self, endpoint, path, token, connection=None):
        owned = connection is None
        connection = connection or http.client.HTTPConnection("127.0.0.1", endpoint["port"], timeout=3)
        try:
            connection.request("GET", path, headers={"Authorization": "Bearer " + token})
            response = connection.getresponse()
            body = response.read()
            return response.status, body, response.getheader("X-SimpleMatch-Query-Policy-Version")
        except (OSError, http.client.HTTPException):
            connection.close()
            raise
        finally:
            if owned:
                connection.close()

    def direct_status(self, endpoint, token):
        configuration = f'url = "http://{endpoint["ip"]}:8086/api/v1/accounts/not-a-uuid/summary"\nheader = "Authorization: Bearer {token}"\n'
        result = self.docker("exec", "-i", endpoint["node"], "curl", "-sS", "--max-time", "1",
                              "--write-out", "\n%{http_code}", "--config", "-", data=configuration,
                              timeout=3, check=False)
        if result.returncode == 7:
            return 0  # Observed connection refusal, never a timeout.
        if result.returncode:
            raise RuntimeError("Direct Pod observation failed without proving rejection")
        return int(result.stdout.splitlines()[-1])

    def direct_rejected(self, endpoint, token):
        return self.direct_status(endpoint, token) in (0, 503)

    def matrix(self, endpoints, bundle, version, disabled=False):
        credentials = json.loads((bundle / "credentials.json").read_text())
        identities = json.loads((bundle / "fixture-identities.json").read_text())
        allowed = {"client-a": {"X"}, "client-b": {"Y"}, "client-c": {"X", "Y"} if version == 1 else {"X"},
                   "operations": set(), "client-d": set()}
        observations = []
        for endpoint in endpoints:
            pod = json.loads(self.kube("get", "pod", endpoint["pod"], "-o", "json").stdout)
            status = self.approval("status")
            if pod["metadata"]["annotations"]["simplematch.io/query-policy-digest"] != status["digest"]:
                raise RuntimeError("Pod policy digest is not the approved deployment")
            # Read the observed mounted bytes on the owner node; the shell-less image needs no debug tools.
            path = f"/var/lib/kubelet/pods/{endpoint['uid']}/volumes/kubernetes.io~secret/query-policy/application.properties"
            mounted = self.docker("exec", endpoint["node"], "cat", path).stdout.encode()
            if hashlib.sha256(mounted).hexdigest() != status["digest"]:
                raise RuntimeError("Mounted policy bytes differ from the approved immutable snapshot")
            container = next(c for c in pod["spec"]["containers"] if c["name"] == "query-service")
            if container["image"] != self.image or any("SECURITY_" in e["name"] for e in container["env"]):
                raise RuntimeError("Unexpected image or competing security environment source")
            actual_image = json.loads(self.docker("exec", endpoint["node"], "crictl", "inspecti", self.image).stdout)
            if actual_image["status"]["id"] != self.image_config_id:
                raise RuntimeError("Runtime image no longer matches this run's approved image")
            for alias, grants in allowed.items():
                token = credentials[alias]["token"]
                for account in ("X", "Y"):
                    expected = 401 if disabled and alias == "client-d" else (200 if account in grants else 404)
                    code, body, active_version = self.http(endpoint,
                        "/api/v1/accounts/" + identities["accounts"][account] + "/summary", token)
                    if code != expected or (expected != 401 and active_version != str(version)):
                        raise RuntimeError("Account matrix or active policy version mismatch")
                    if code == 200 and (json.loads(body)["data"]["accountId"] != identities["accounts"][account]
                                        or json.loads(body)["data"]["reservedNotionalUnits"] != self.expected[account]):
                        raise RuntimeError("Account projection does not match the captured source facts")
                    if code in (401, 404) and body:
                        raise RuntimeError("Denied resource exposed an error body")
                    observations.append({"pod": endpoint["pod"], "principal": alias, "account": account, "status": code})
                code, _, _ = self.http(endpoint, "/api/v1/freshness", token)
                if code != (401 if disabled and alias == "client-d" else (200 if alias == "operations" else 403)):
                    raise RuntimeError("Operations permission changed")
                code, body, _ = self.http(endpoint, "/api/v1/market-reference/2026-08-11/XTAI/2330", token)
                if code != (401 if disabled and alias == "client-d" else 200):
                    raise RuntimeError("Independent Market permission changed")
                if code == 200 and json.loads(body)["data"]["artifactId"] != self.artifact_id:
                    raise RuntimeError("Historical artifact identity changed")
        return observations

    def publish(self, bundle, batch):
        package = "com/simplematch/queryservice/security"
        bytecode = self.repo / f"services/query-service/build/classes/java/test/{package}/QueryLifecycleFixtureMain.class"
        identities = (bundle / "fixture-identities.json").read_text()
        name = "query-fixture-" + batch
        self.apply([{"apiVersion": "v1", "kind": "ConfigMap", "metadata": {"name": name}, "immutable": True,
                     "data": {"fixture-identities.json": identities},
                     "binaryData": {"QueryLifecycleFixtureMain.class": base64.b64encode(bytecode.read_bytes()).decode()}},
                    {"apiVersion": "batch/v1", "kind": "Job", "metadata": {"name": name}, "spec": {
                      "backoffLimit": 0, "activeDeadlineSeconds": 90, "template": {"metadata": {"labels": {LABEL: self.run_id}},
                      "spec": {"restartPolicy": "Never", "automountServiceAccountToken": False,
                       "nodeSelector": {"simplematch.io/node-pool": "local-resilience"},
                       "containers": [{"name": "fixture", "image": self.image, "imagePullPolicy": "Never",
                         "command": ["/layers/paketo-buildpacks_bellsoft-liberica/jre/bin/java", "-cp",
                           "/workspace/BOOT-INF/classes:/workspace/BOOT-INF/lib/*:/fixture",
                           "com.simplematch.queryservice.security.QueryLifecycleFixtureMain",
                           "/fixture/fixture-identities.json", batch],
                         "volumeMounts": [{"name": "fixture", "mountPath": "/fixture", "readOnly": True}]}],
                       "volumes": [{"name": "fixture", "configMap": {"name": name, "items": [
                           {"key": "fixture-identities.json", "path": "fixture-identities.json"},
                           {"key": "QueryLifecycleFixtureMain.class", "path": package + "/QueryLifecycleFixtureMain.class"}]}}]}}}}])
        self.job(name, 90)
        logs = self.kube("logs", "job/" + name).stdout
        receipt = next(json.loads(line.removeprefix("QUERY_SECURITY_FIXTURE=")) for line in logs.splitlines()
                       if line.startswith("QUERY_SECURITY_FIXTURE="))
        self.receipts += receipt["records"]
        self.expected.update({row["account"]: row["notional"] for row in receipt["records"]})
        self.save("receipts-" + batch + ".json", receipt)
        self.capture_boundary()

    def capture_boundary(self):
        """Capture source progress after publication and before resuming Query consumers."""
        output = self.kube("exec", "kafka-0", "-c", "kafka", "--", "/opt/kafka/bin/kafka-get-offsets.sh",
                           "--bootstrap-server", "kafka:9092", "--topic", "account.lifecycle", "--time", "-1").stdout
        self.boundary = {int(line.split(":")[1]): int(line.split(":")[2]) for line in output.splitlines()
                    if line.startswith("account.lifecycle:")}
        if len(self.boundary) != 15:
            raise RuntimeError("Captured Kafka boundary is incomplete")
        self.save("boundary-" + str(len(self.receipts)) + ".json", {"topic": "account.lifecycle",
                  "boundary": self.boundary, "observedAtEpochNs": time.time_ns()})

    def catchup(self):
        boundary, started = self.boundary, self.recovery_started
        def caught():
            checkpoints = self.sql("SELECT json_build_object('partition',partition_id,'offset',last_processed_offset,"
                "'state',recovery_state) FROM query_service.projection_checkpoint WHERE source_topic='account.lifecycle'")
            actual = {row["partition"]: row for row in checkpoints}
            return all((end == 0 and partition not in actual) or
                       (partition in actual and actual[partition]["offset"] == end-1 and actual[partition]["state"] == "READY")
                       for partition, end in boundary.items())
        wait_for("durable checkpoints reaching the captured Kafka boundary", caught,
                 max(0, 120 - (time.monotonic() - started)))
        inbox = self.sql("SELECT json_build_object('eventId',event_id::text,'partition',partition_id,'offset',offset_value,"
                         "'payloadSha256',encode(payload_sha256,'hex')) FROM query_service.projection_inbox WHERE source_topic='account.lifecycle'")
        expected = [{key: row[key] for key in ("eventId", "partition", "offset", "payloadSha256")} for row in self.receipts]
        if sorted(inbox, key=lambda row: row["eventId"]) != sorted(expected, key=lambda row: row["eventId"]):
            raise RuntimeError("Inbox does not prove every acknowledged fixture input")
        result = {"boundary": boundary, "inputCount": len(inbox), "elapsedSeconds": time.monotonic()-started,
                  "queryRestartEpochNs": self.recovery_epoch_ns,
                  "noRebuildOrOffsetReset": True}
        if result["elapsedSeconds"] > 120:
            raise RuntimeError("Catch-up evidence exceeded its 120-second observation budget")
        self.save("catchup-" + str(len(self.receipts)) + ".json", result)
        return result

    def destroy(self):
        for process, log in self.forwards:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            log.close()
        if self.uid is None:
            return
        state = self.namespace_state()
        capture_errors = []
        if state:
            self.guard()
            pods = json.loads(self.kube("get", "pods", "-o", "json").stdout)["items"]
            (self.private / "teardown-pods.json").write_text(json.dumps(pods))
            try:
                events = self.kube("get", "events", "-o", "json", timeout=5, check=False)
                (self.private / "teardown-events.json").write_text(events.stdout + events.stderr)
            except subprocess.TimeoutExpired:
                (self.private / "teardown-events.json").write_text("Event diagnostic unavailable; teardown continues.")
            paths = {(pod["spec"]["nodeName"], pod["metadata"]["uid"]) for pod in pods if pod["spec"].get("nodeName")}
            paths.update((task["node"], task["uid"]) for task in self.containers)
            self.save("owned-data-paths.json", sorted(paths))
            try:
                for pod in pods:
                    for container in pod["status"].get("containerStatuses", []):
                        if "running" in container.get("state", {}):
                            try:
                                self.task(pod, container["name"], verification=False)
                            except (RuntimeError, KeyError, subprocess.TimeoutExpired) as error:
                                capture_errors.append({"podUid": pod["metadata"]["uid"],
                                    "container": container["name"], "error": type(error).__name__})
            finally:
                self.save("teardown-capture-errors.json", capture_errors)
                self.kube("delete", "namespace", self.namespace, "--wait=false", "--ignore-not-found")
        else:
            paths = json.loads((self.root / "owned-data-paths.json").read_text())
            capture_errors = json.loads((self.root / "teardown-capture-errors.json").read_text())
        wait_for("owned namespace destruction", lambda: self.namespace_state() is None, 90)
        wait_for("all owned application/platform tasks actually stopped", lambda: all(self.stopped(task) for task in self.containers), 90)
        if self.running_tasks():
            raise RuntimeError("Namespace deletion left an owned task running")
        def data_gone():
            return all(self.docker("exec", node, "test", "!", "-e", f"/var/lib/kubelet/pods/{uid}",
                                   check=False).returncode == 0 for node, uid in paths)
        wait_for("owned emptyDir data cleanup", data_gone, 90)
        if self.authority.exists():
            proof = self.proof("Destroyed", namespaceGone=True, oldProcessesStopped=True, dataGone=True)
            self.approval("destroyed", "--evidence-file", proof)
        self.save("teardown.json", {"namespaceUid": self.uid, "namespaceGone": True,
                                    "oldProcessesStopped": True, "dataGone": True,
                                    "captureFailures": len(capture_errors)})
        if capture_errors:
            raise RuntimeError("Owned teardown completed with capture failures; retain this run as FAIL")
