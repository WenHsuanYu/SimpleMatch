"""Regression checks for the Native advisory CI evidence contract."""

import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import clang_tidy_ci as advisory


class WarningInventoryTests(unittest.TestCase):
    @staticmethod
    def full_scan():
        return [
            "[1/1] Run report-only clang-tidy on handwritten Native C++ sources",
            *[f"[{i:2d}/28][1.0s] clang-tidy source.cpp" for i in range(1, 29)],
        ]

    def test_repeated_owned_header_warning_is_one_site(self):
        line = (
            "/workspace/matching-engine/include/example.hpp:17:3: warning: "
            "example diagnostic [misc-include-cleaner]"
        )
        counts, sites = advisory.parse_warnings(self.full_scan() + [line, line])
        self.assertEqual(counts["misc-include-cleaner"], 2)
        self.assertEqual(sites, {("matching-engine/include/example.hpp", 17,
                                  "misc-include-cleaner")})

    def test_complete_scan_without_warnings_is_clean(self):
        counts, sites = advisory.parse_warnings(self.full_scan())
        self.assertFalse(counts)
        self.assertFalse(sites)

    def test_incomplete_scan_fails_closed(self):
        with self.assertRaisesRegex(RuntimeError, "Incomplete handwritten-TU scan"):
            advisory.parse_warnings(self.full_scan()[:-1])

    def test_unparsed_warning_fails_closed(self):
        with self.assertRaisesRegex(RuntimeError, "Unparsed clang-tidy warning"):
            advisory.parse_warnings(self.full_scan() + [
                "/external/path.cpp:9:4: warning: unknown format [unmatched-check]"
            ])


class VerdictTests(unittest.TestCase):
    def test_invalid_summary_shapes_keep_failure_receipt(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "native-tidy-artifact"
            output.mkdir()
            env = self.settings(folder, "success")
            for summary in ("null", "[]", '{"status": []}', '{"status": 123}', "{"):
                (output / "summary.json").write_text(summary)
                with patch.dict(os.environ, env):
                    with self.assertRaisesRegex(RuntimeError, "summary is invalid"):
                        advisory.verdict()
                receipt = json.loads((output / "verdict.json").read_text())
                self.assertEqual(receipt["status"], "FAIL")

    def test_success_without_summary_is_a_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            env = self.settings(folder, "success")
            with patch.dict(os.environ, env):
                with self.assertRaisesRegex(RuntimeError, "summary is invalid"):
                    advisory.verdict()
            receipt = json.loads((Path(folder) / "native-tidy-artifact/verdict.json").read_text())
            self.assertEqual(receipt["status"], "FAIL")

    def test_success_requires_a_valid_summary(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "native-tidy-artifact"
            output.mkdir()
            (output / "summary.json").write_text(
                '{"status": "PASS_WITH_ADVISORY_WARNINGS"}\n'
            )
            env = self.settings(folder, "success")
            with patch.dict(os.environ, env):
                advisory.verdict()
            receipt = json.loads((output / "verdict.json").read_text())
            self.assertEqual(receipt["status"], "PASS_WITH_ADVISORY_WARNINGS")
            self.assertTrue(receipt["inventory_available"])

    def test_failed_step_keeps_a_failure_receipt(self):
        with tempfile.TemporaryDirectory() as folder:
            env = self.settings(folder, "failure")
            with patch.dict(os.environ, env):
                advisory.verdict()
            receipt = json.loads((Path(folder) / "native-tidy-artifact/verdict.json").read_text())
            self.assertEqual(receipt["status"], "FAIL")
            self.assertFalse(receipt["inventory_available"])

    def test_skipped_stages_are_not_mislabeled_pass(self):
        with tempfile.TemporaryDirectory() as folder:
            env = self.settings(folder, "skipped")
            with patch.dict(os.environ, env):
                advisory.verdict()
            receipt = json.loads((Path(folder) / "native-tidy-artifact/verdict.json").read_text())
            self.assertEqual(receipt["status"], "UNRUN")

    @staticmethod
    def settings(folder, outcome):
        return {
            "RUNNER_TEMP": folder,
            "GITHUB_SHA": "test-checkout-sha",
            "GITHUB_RUN_ID": "test-run",
            "GITHUB_RUN_ATTEMPT": "1",
            "LLVM_VERSION": "21.1.6",
            "LLVM_RUN_TIDY_SHA256": "test-pin",
            **{f"{stage.upper()}_OUTCOME": outcome for stage in advisory.STAGES},
        }


if __name__ == "__main__":
    unittest.main()
