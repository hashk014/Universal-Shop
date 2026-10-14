"""Run with python -m unittest discover -s tools -p test_migrate_economy.py -v."""
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from uuid import uuid4
import zipfile

import migrate_economy as migration


class MigrationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.data = self.root / "UniversalShop"
        self.data.mkdir()
        (self.data / "audit").mkdir()
        (self.data / "shops.yml").write_bytes(b"products:\n  stone:\n    material: STONE\n")
        (self.data / "languages").mkdir()
        (self.data / "languages/fr.yml").write_bytes("name: Économie\n".encode("utf-8"))
        self.fixture()

    def tearDown(self):
        self.temporary.cleanup()

    def fixture(self, mode="INTERNAL", provider=None, extra="", sequence=0, identity=None):
        provider_line = "economy-provider: " + json.dumps(provider) + "\n" if provider else ""
        identity_line = "  last-audit-id: " + identity + "\n" if identity else ""
        payload = ("schema: 1\neconomy-mode: " + mode + "\n" + provider_line
            + "wallets:\n  original-player: 987654321\n"
            + "custom-item:\n  ==: org.bukkit.inventory.ItemStack\n  type: BOOK\n  meta:\n"
            + "    ==: ItemMeta\n    meta-type: UNSPECIFIC\n    display-name: 'Économie # originale'\n"
            + "    lore:\n    - 'do not rewrite : this # text'\n"
            + "storage:\n  sequence: " + str(sequence) + "\n  legacy-imported: true\n"
            + "  checkpoint-format: 1\n" + identity_line + extra).encode("utf-8")
        self.original_payload = payload
        self.original_state = migration.reseal(payload)
        self.original_config = ("# untouched configuration\r\neconomy: " + mode
            + " # preserve this comment\r\nstarting-balance: 100.00\r\nmarket:\r\n  enabled: true\r\n").encode("utf-8")
        (self.data / "state.yml").write_bytes(self.original_state)
        (self.data / "config.yml").write_bytes(self.original_config)

    def run_migration(self, source="INTERNAL", target="VAULT", provider="Essentials Economy", confirm=migration.ACKNOWLEDGEMENT, dry_run=False):
        return migration.migrate(self.data, source, target, provider, confirm, dry_run)

    def assert_unmodified(self):
        self.assertEqual(self.original_state, (self.data / "state.yml").read_bytes())
        self.assertEqual(self.original_config, (self.data / "config.yml").read_bytes())

    def test_exact_metadata_wallet_and_comments_survive_with_restorable_backup(self):
        result = self.run_migration()
        after_payload = migration.verify_checkpoint((self.data / "state.yml").read_bytes())
        expected = self.original_payload.replace(b"economy-mode: INTERNAL\n", b"economy-mode: VAULT\n")
        expected += b'economy-provider: "Essentials Economy"\n'
        self.assertEqual(expected, after_payload)
        self.assertEqual(self.original_config.replace(b"economy: INTERNAL", b"economy: VAULT"),
                         (self.data / "config.yml").read_bytes())
        receipt = json.loads(Path(result["receipt"]).read_text(encoding="utf-8"))
        self.assertEqual("Essentials Economy", receipt["toProvider"])
        self.assertEqual(migration.digest((self.data / "state.yml").read_bytes()), receipt["after"]["stateFileSha256"])
        self.assertEqual(migration.digest(self.original_payload), receipt["before"]["checkpointPayloadSha256"])
        restored = self.root / "restored"
        with zipfile.ZipFile(result["backup"]) as backup:
            self.assertNotIn("ledger.lock", backup.namelist())
            self.assertNotIn("backups/", backup.namelist())
            self.assertIn("shops.yml", backup.namelist())
            self.assertIn("languages/fr.yml", backup.namelist())
            backup.extractall(restored)
        self.assertEqual(self.original_state, (restored / "state.yml").read_bytes())
        self.assertEqual(self.original_config, (restored / "config.yml").read_bytes())
        self.assertEqual(self.original_payload, migration.verify_checkpoint((restored / "state.yml").read_bytes()))

    def test_vault_to_internal_removes_only_provider_binding(self):
        self.fixture("VAULT", "Essentials Economy", "market: {}\nmarket-credits: {}\n")
        self.run_migration("VAULT", "INTERNAL", None)
        payload = migration.verify_checkpoint((self.data / "state.yml").read_bytes())
        expected = self.original_payload.replace(b"economy-mode: VAULT", b"economy-mode: INTERNAL")
        expected = expected.replace(b'economy-provider: "Essentials Economy"\n', b"")
        self.assertEqual(expected, payload)

    def test_vault_to_vault_can_rebind_distinct_provider(self):
        self.fixture("VAULT", "Economy's Original")
        self.run_migration("VAULT", "VAULT", "Other Provider")
        state = migration.YamlLines(migration.verify_checkpoint((self.data / "state.yml").read_bytes()))
        self.assertEqual("Other Provider", state.scalar("economy-provider"))

    def test_quoted_provider_names_with_hash_and_apostrophe_roundtrip(self):
        provider = "Trader's Economy # Main"
        self.run_migration(provider=provider)
        state = migration.YamlLines(migration.verify_checkpoint((self.data / "state.yml").read_bytes()))
        self.assertEqual(provider, state.scalar("economy-provider"))

    def test_dry_run_requires_no_acknowledgement_and_writes_no_backup_receipt(self):
        result = self.run_migration(confirm=None, dry_run=True)
        self.assertTrue(result["dryRun"])
        self.assert_unmodified()
        self.assertFalse((self.data / "backups").exists())
        self.assertFalse((self.data / "migrations").exists())

    def test_requires_explicit_completed_external_balance_transfer(self):
        with self.assertRaisesRegex(migration.MigrationError, "BALANCES_TRANSFERRED"):
            self.run_migration(confirm=None)
        self.assert_unmodified()

    def test_source_mode_and_config_must_match(self):
        with self.assertRaisesRegex(migration.MigrationError, "--from"):
            self.run_migration("VAULT", "INTERNAL", None)
        self.assert_unmodified()
        (self.data / "config.yml").write_bytes(self.original_config.replace(b"INTERNAL", b"VAULT"))
        with self.assertRaisesRegex(migration.MigrationError, "--from"):
            self.run_migration()

    def test_provider_required_and_noop_rejected(self):
        with self.assertRaisesRegex(migration.MigrationError, "--provider"):
            self.run_migration(provider=None)
        with self.assertRaisesRegex(migration.MigrationError, "already active"):
            self.run_migration("INTERNAL", "INTERNAL", None)
        self.assert_unmodified()

    def test_checksum_damage_and_missing_or_truncated_footer_refused(self):
        path = self.data / "state.yml"
        for damaged in (self.original_state.replace(b"987654321", b"111111111"),
                        self.original_payload, self.original_state[:-12]):
            with self.subTest(damaged_length=len(damaged)):
                path.write_bytes(damaged)
                with self.assertRaises(migration.MigrationError):
                    self.run_migration()
                self.assertEqual(self.original_config, (self.data / "config.yml").read_bytes())
        self.assertFalse((self.data / "backups").exists())

    def test_pending_and_any_marketplace_escrow_refused(self):
        (self.data / "pending.yml").write_bytes(b"id: unresolved\n")
        with self.assertRaisesRegex(migration.MigrationError, "pending.yml"):
            self.run_migration()
        (self.data / "pending.yml").unlink()
        self.fixture(extra="market:\n  abcdef12:\n    owner: seller\n    quantity: 1\n")
        with self.assertRaisesRegex(migration.MigrationError, "escrow"):
            self.run_migration()
        self.assert_unmodified()

    def test_credits_positive_negative_or_unknown_structure_refused(self):
        for extra in ("market-credits:\n  seller: 50\n", "market-credits:\n  seller: -1\n",
                      "market-credits: {seller: 50}\n", "market-credits: *unsafe\n"):
            with self.subTest(extra=extra):
                self.fixture(extra=extra)
                with self.assertRaises(migration.MigrationError):
                    self.run_migration()
                self.assert_unmodified()

    def test_zero_credits_and_empty_escrow_are_accepted(self):
        self.fixture(extra="market: {}\nmarket-credits:\n  seller: 0\n")
        self.assertTrue(self.run_migration()["completed"])

    def test_uncheckpointed_audit_and_missing_checkpoint_boundary_refused(self):
        identity = str(uuid4())
        segment = self.data / "audit/00000000000000000000"
        segment.mkdir()
        record = segment / ("00000000000000000001-" + identity + ".yml")
        record.write_bytes(b"immutable audit fixture")
        with self.assertRaisesRegex(migration.MigrationError, "Uncheckpointed"):
            self.run_migration()
        record.unlink()
        self.fixture(sequence=1, identity=identity)
        with self.assertRaisesRegex(migration.MigrationError, "regular file"):
            self.run_migration()
        record.write_bytes(b"immutable audit fixture")
        result = self.run_migration()
        with zipfile.ZipFile(result["backup"]) as backup:
            self.assertIn(record.relative_to(self.data).as_posix(), backup.namelist())
        self.assertEqual(b"immutable audit fixture", record.read_bytes())

    def test_duplicate_keys_aliases_and_unknown_scalar_syntax_refused(self):
        for change in (b"economy-mode: INTERNAL\neconomy-mode: VAULT\n", b"economy-mode: *alias\n", b"economy-mode: |\n  INTERNAL\n"):
            with self.subTest(change=change):
                payload = self.original_payload.replace(b"economy-mode: INTERNAL\n", change)
                (self.data / "state.yml").write_bytes(migration.reseal(payload))
                with self.assertRaises(migration.MigrationError):
                    self.run_migration()
        self.assertFalse((self.data / "backups").exists())

    def test_write_failure_keeps_invalid_economy_guard_and_backup(self):
        real_write = migration.write_atomic
        def failing_write(path, content):
            if path == self.data / "state.yml":
                raise OSError("simulated state publication fault")
            return real_write(path, content)
        with patch.object(migration, "write_atomic", side_effect=failing_write):
            with self.assertRaisesRegex(migration.MigrationError, "Keep the server stopped"):
                self.run_migration()
        self.assertEqual(migration.GUARD, migration.YamlLines((self.data / "config.yml").read_bytes()).scalar("economy"))
        self.assertEqual(self.original_state, (self.data / "state.yml").read_bytes())
        self.assertEqual(1, len(list((self.data / "backups").glob("*.zip"))))
        self.assertEqual(1, len(list((self.data / "migrations").glob("*.json"))))

    def test_crash_after_state_publication_keeps_guard_even_for_provider_only_change(self):
        self.fixture("VAULT", "Old Provider")
        class SimulatedPowerCut(BaseException):
            pass
        real_write = migration.write_atomic
        def interrupted_write(path, content):
            real_write(path, content)
            if path == self.data / "state.yml":
                raise SimulatedPowerCut()
        with patch.object(migration, "write_atomic", side_effect=interrupted_write):
            with self.assertRaises(SimulatedPowerCut):
                self.run_migration("VAULT", "VAULT", "New Provider")
        self.assertEqual(migration.GUARD, migration.YamlLines((self.data / "config.yml").read_bytes()).scalar("economy"))
        migrated = migration.YamlLines(migration.verify_checkpoint((self.data / "state.yml").read_bytes()))
        self.assertEqual("New Provider", migrated.scalar("economy-provider"))

    def test_other_process_lease_refuses_live_data(self):
        script = ("import sys; from pathlib import Path; sys.path.insert(0, sys.argv[1]); "
                  "from migrate_economy import LedgerLease; "
                  "lease=LedgerLease(Path(sys.argv[2])); lease.__enter__(); "
                  "print('LOCKED', flush=True); sys.stdin.readline(); lease.__exit__()")
        child = subprocess.Popen([sys.executable, "-c", script, str(Path(migration.__file__).parent), str(self.data)],
                                 stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            self.assertEqual("LOCKED", child.stdout.readline().strip())
            with self.assertRaisesRegex(migration.MigrationError, "in use"):
                self.run_migration()
            self.assert_unmodified()
        finally:
            child.communicate("release\n", timeout=10)
            self.assertEqual(0, child.returncode)

    @unittest.skipUnless(os.environ.get("UNIVERSALSHOP_TEST_JAVA"), "Set UNIVERSALSHOP_TEST_JAVA to a Java 11+ executable for the Java NIO lease probe.")
    def test_real_java_nio_whole_file_lock_overlaps_python_byte_zero(self):
        source = self.root / "LeaseProbe.java"
        source.write_text('''import java.nio.channels.*;
import java.nio.file.*;
public class LeaseProbe {
  public static void main(String[] args) throws Exception {
    try (FileChannel channel = FileChannel.open(Paths.get(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
         FileLock lock = channel.tryLock()) {
      if (lock == null) throw new IllegalStateException("No lock");
      System.out.println("LOCKED"); System.out.flush();
      new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
    }
  }
}
''', encoding="utf-8")
        child = subprocess.Popen([os.environ["UNIVERSALSHOP_TEST_JAVA"], str(source), str(self.data / "ledger.lock")],
                                 stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            self.assertEqual("LOCKED", child.stdout.readline().strip())
            with self.assertRaisesRegex(migration.MigrationError, "in use"):
                self.run_migration()
            self.assert_unmodified()
        finally:
            child.communicate("release\n", timeout=10)
            self.assertEqual(0, child.returncode)

    def test_backup_excludes_previous_backups_lock_and_temporary_files(self):
        (self.data / "backups").mkdir()
        (self.data / "backups/previous.zip").write_bytes(b"old backup")
        (self.data / "unpublished.tmp").write_bytes(b"not committed")
        result = self.run_migration()
        with zipfile.ZipFile(result["backup"]) as backup:
            self.assertFalse(any(name.startswith("backups/") or name.endswith(".tmp") or name == "ledger.lock"
                                 for name in backup.namelist()))

    def test_cli_dry_run_returns_reviewable_plan(self):
        process = subprocess.run([sys.executable, migration.__file__, "--data-folder", str(self.data),
            "--from", "INTERNAL", "--to", "VAULT", "--provider", "Essentials Economy", "--dry-run"],
            text=True, capture_output=True, timeout=10)
        self.assertEqual(0, process.returncode, process.stderr)
        self.assertTrue(json.loads(process.stdout)["dryRun"])
        self.assert_unmodified()


if __name__ == "__main__":
    unittest.main()
