#!/usr/bin/env python3
"""Execute the collector's fixture selection against an owned, Flyway-migrated PostgreSQL."""

import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from urllib.parse import urlsplit
import uuid


REPOSITORY = Path(__file__).resolve().parents[4]
MODULE = REPOSITORY / "scripts/end-to-end/query-service/lib/evidence.sh"
PROVISIONER = REPOSITORY / "scripts/end-to-end/query-service/provision-security-fixtures.py"


class CollectorPostgresContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        database = os.environ["QUERY_SERVICE_JDBC_TEST_DATABASE"]
        url = os.environ["QUERY_SERVICE_JDBC_TEST_URL"]
        if not re.fullmatch(r"simplematch_query_jdbc_[0-9a-f]+", database) or not url.startswith("jdbc:postgresql://"):
            raise ValueError("Expected the existing runner-owned Query scratch contract")
        target = urlsplit(url.removeprefix("jdbc:"))
        if target.path != "/" + database:
            raise ValueError("Scratch database URL mismatch")
        cls.environment = os.environ | {
            "PGHOST": target.hostname, "PGPORT": str(target.port or 5432),
            "PGDATABASE": database, "PGUSER": os.environ["QUERY_SERVICE_JDBC_TEST_USER"],
            "PGPASSWORD": os.environ["QUERY_SERVICE_JDBC_TEST_PASSWORD"],
        }
        cls.psql = ["psql", "-XqAt", "-v", "ON_ERROR_STOP=1"]
        owned = cls.sql("SELECT shobj_description(oid, 'pg_database') FROM pg_database "
                        "WHERE datname=current_database();").strip()
        if owned != "simplematch-query-postgres-jdbc-test":
            raise ValueError("Scratch ownership marker missing; refusing fixture writes")
        versions = cls.sql('SELECT version FROM query_service.flyway_schema_history '
                           "WHERE success AND type='SQL' ORDER BY installed_rank;").splitlines()
        if versions != ["1", "2", "3"]:
            raise ValueError("Expected current Query Flyway migrations before fixture writes")

    @classmethod
    def sql(cls, statement):
        result = subprocess.run(cls.psql, input=statement, env=cls.environment,
                                capture_output=True, text=True, timeout=15)
        if result.returncode:
            raise AssertionError("Owned PostgreSQL fixture command failed: " + result.stderr)
        return result.stdout

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="query-collector-postgres-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bundle = self.root / "bundle"
        subprocess.run([sys.executable, str(PROVISIONER), str(self.bundle)],
                       capture_output=True, check=True)
        self.accounts = json.loads((self.bundle / "fixture-identities.json").read_text())["accounts"]
        self.orders = {alias: str(uuid.uuid4()) for alias in ("X", "Y", "other")}
        self.accounts["other"] = str(uuid.uuid4())
        self.day = "2199-01-02"
        self.symbol = "Q" + uuid.uuid4().hex[:8].upper()
        self.fixture = {
            "fixtureType": "PUBLIC_FIX_CROSS", "tradingDay": self.day,
            "venueMic": "XTAI", "symbol": self.symbol,
            "buy": {"accountId": self.accounts["X"], "orderId": self.orders["X"]},
            "sell": {"accountId": self.accounts["Y"], "orderId": self.orders["Y"]},
        }
        (self.root / "matching-fixture").mkdir()
        self.write_fixture()
        self.addCleanup(self.remove_rows)
        self.sql(f"INSERT INTO query_service.active_market_reference VALUES "
                 f"('{self.day}','collector-fixture','XTAI','{self.symbol}',"
                 "'RULE',200,100,300,0,100);")
        for index, alias in enumerate(("X", "Y", "other")):
            account, order = self.accounts[alias], self.orders[alias]
            self.sql(f"""
                INSERT INTO query_service.account_summary_read_model VALUES
                  ('{account}','ACCOUNT_LIFECYCLE_STATE_RESERVED',100,0,'','','{uuid.uuid4()}',100);
                INSERT INTO query_service.order_read_model VALUES
                  ('{order}','{account}','XTAI','{self.symbol}','SIDE_BUY','FILLED',0,'event',0,0,100);
                INSERT INTO query_service.execution_read_model VALUES
                  ('{uuid.uuid4()}','{order}','{account}','XTAI','{self.symbol}','SIDE_BUY',
                   100,200,100,0,200,'event',0,0,{100 + index});
                """)

    def write_fixture(self):
        (self.root / "matching-fixture/fixture.json").write_text(json.dumps(self.fixture))

    def remove_rows(self):
        accounts = ",".join("'" + value + "'" for value in self.accounts.values())
        self.sql(f"DELETE FROM query_service.execution_read_model WHERE account_id IN ({accounts});"
                 f"DELETE FROM query_service.order_read_model WHERE account_id IN ({accounts});"
                 f"DELETE FROM query_service.account_summary_read_model WHERE account_id IN ({accounts});"
                 f"DELETE FROM query_service.active_market_reference WHERE trading_day='{self.day}' "
                 f"AND symbol='{self.symbol}';")

    def select(self):
        # Adapt only the external database transport; execute the production selection SQL unchanged.
        command = '''source "$MODULE"
query_postgres_json() { psql -XqAt -v ON_ERROR_STOP=1 -c "$1" >"$2"; }
select_query_fixture
'''
        return subprocess.run(["bash", "-euc", command], capture_output=True, text=True, timeout=15,
                              env=self.environment | {"MODULE": str(MODULE), "evidence_dir": str(self.root),
                                  "query_fixture_directory": str(self.bundle)})

    def test_selection_uses_this_runs_approved_account_and_order_not_newest_unrelated_execution(self):
        result = self.select()
        self.assertEqual(result.returncode, 0, result.stderr)
        selected = json.loads((self.root / "selected-fixture.json").read_text())
        self.assertEqual(selected["accountId"], self.accounts["X"])
        self.assertEqual(selected["orderId"], self.orders["X"])

    def test_missing_expected_order_does_not_select_another_accounts_execution(self):
        self.sql(f"DELETE FROM query_service.execution_read_model WHERE order_id='{self.orders['X']}';"
                 f"DELETE FROM query_service.order_read_model WHERE order_id='{self.orders['X']}';")
        self.assertNotEqual(self.select().returncode, 0)

    def test_mismatched_parent_account_is_not_a_fixture(self):
        self.sql(f"UPDATE query_service.order_read_model SET account_id='{self.accounts['Y']}' "
                 f"WHERE order_id='{self.orders['X']}';")
        self.assertNotEqual(self.select().returncode, 0)

    def test_an_unapproved_account_in_the_fixture_manifest_is_rejected(self):
        self.fixture["buy"]["accountId"] = self.accounts["other"]
        self.write_fixture()
        self.assertNotEqual(self.select().returncode, 0)


if __name__ == "__main__":
    unittest.main()
