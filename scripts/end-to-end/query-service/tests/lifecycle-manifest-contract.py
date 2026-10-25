#!/usr/bin/env python3
"""Exercise the effective manifest validation seam without applying Kubernetes resources."""

import copy
import sys
import unittest
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from lib.security_lab import validate_objects


class LifecycleManifestContract(unittest.TestCase):
    def fixture(self):
        return [
            {"kind": "ConfigMap", "metadata": {"name": "artifact"}, "data": {"market.json": "{}"}},
            {"kind": "Secret", "metadata": {"name": "database"}, "stringData": {"dsn": "synthetic"}},
            {"kind": "ServiceAccount", "metadata": {"name": "query-service"}},
            {"kind": "Deployment", "metadata": {"name": "query-service"}, "spec": {"template": {"spec": {
                "serviceAccountName": "query-service", "volumes": [
                    {"name": "artifact", "configMap": {"name": "artifact"}}],
                "containers": [{"name": "query-service", "volumeMounts": [
                    {"name": "artifact", "mountPath": "/etc/market"}], "env": [
                    {"name": "SIMPLEMATCH_QUERY_SERVICE_MARKET_REFERENCE_ARTIFACT_LOCATION", "value": "file:/etc/market/market.json"},
                    {"name": "DATABASE", "valueFrom": {"secretKeyRef": {"name": "database", "key": "dsn"}}}]}]}}}}]

    def test_valid_names_keys_and_file_uri_resolve_without_api_access(self):
        validate_objects(self.fixture())

    def test_missing_resource_key_and_unmounted_or_bare_artifact_uri_are_rejected(self):
        for failure in ("resource", "key", "mount", "uri"):
            objects = copy.deepcopy(self.fixture())
            if failure == "resource":
                objects.pop(0)
            elif failure == "key":
                objects[1]["stringData"].clear()
            elif failure == "mount":
                objects[-1]["spec"]["template"]["spec"]["containers"][0]["volumeMounts"].clear()
            else:
                objects[-1]["spec"]["template"]["spec"]["containers"][0]["env"][0]["value"] = "/etc/market/market.json"
            with self.subTest(failure=failure), self.assertRaisesRegex(RuntimeError, "manifest"):
                validate_objects(objects)


if __name__ == "__main__":
    unittest.main()
