#!/usr/bin/env python3
"""Check the Lab observer's public bounded-wait seam without deployment or clock mocks."""

import sys
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from lib.security_lab import wait_for


class LifecycleDeadlineContract(unittest.TestCase):
    def test_successful_observation_after_the_deadline_is_not_a_pass(self):
        def late_success():
            time.sleep(0.04)
            return True
        with self.assertRaisesRegex(RuntimeError, "Timed out"):
            wait_for("expired observation", late_success, 0.01)


if __name__ == "__main__":
    unittest.main()
