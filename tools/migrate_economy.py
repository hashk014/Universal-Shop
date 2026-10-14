"""Offline UniversalShop economy binding migration (Python 3.10+, standard library only).

Balances must be transferred separately. This tool never rewrites wallet values, serialized
Bukkit objects, or audit records. Unknown YAML structures are refused conservatively.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
from datetime import datetime, timezone
from uuid import UUID, uuid4
import zipfile

FOOTER = b"# UniversalShop-checkpoint-sha256: "
GUARD = "MIGRATION_PENDING"
ACKNOWLEDGEMENT = "BALANCES_TRANSFERRED"
TOP_KEY = re.compile(r"^([A-Za-z0-9_.-]+):(?:[ \t]+(.*))?$", re.ASCII)
AUDIT_NAME = re.compile(r"([0-9]{20})-([a-fA-F0-9-]{36})\.yml$", re.ASCII)


class MigrationError(RuntimeError):
    pass


class LedgerLease:
    """Byte-zero exclusive lock overlaps Java FileChannel.tryLock()'s whole-file range."""

    def __init__(self, directory: Path):
        self.path = directory / "ledger.lock"
        self.file = None

    def __enter__(self):
        if self.path.is_symlink():
            raise MigrationError("ledger.lock must not be a symbolic link.")
        self.file = self.path.open("a+b")
        try:
            if os.name == "nt":
                import msvcrt
                # Windows byte-range locks may cover bytes beyond EOF. Do not write even
                # a placeholder byte: Java may already hold a whole-file lock on an empty file.
                self.file.seek(0)
                msvcrt.locking(self.file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.lockf(self.file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB, 1, 0, os.SEEK_SET)
        except OSError as error:
            self.file.close()
            self.file = None
            raise MigrationError("Data is in use. Stop the server before migrating.") from error
        return self

    def __exit__(self, *_):
        if self.file is None:
            return
        try:
            if os.name == "nt":
                import msvcrt
                self.file.seek(0)
                msvcrt.locking(self.file.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.lockf(self.file.fileno(), fcntl.LOCK_UN, 1, 0, os.SEEK_SET)
        finally:
            self.file.close()
            self.file = None


def digest(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def _fsync_directory(path: Path):
    if os.name == "nt":
        return  # Python/Java do not expose the equivalent Windows directory fsync barrier.
    descriptor = os.open(path, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def write_atomic(path: Path, content: bytes):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, name = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=path.parent)
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        if path.exists():
            os.chmod(temporary, stat.S_IMODE(path.stat().st_mode))
        os.replace(temporary, path)
        _fsync_directory(path.parent)
    finally:
        temporary.unlink(missing_ok=True)


def verify_checkpoint(source: bytes) -> bytes:
    marker = source.rfind(b"\n" + FOOTER)
    if marker < 0:
        raise MigrationError("Protected checkpoint checksum is missing. Start and normally stop UniversalShop 2.0 first.")
    marker += 1
    payload, footer = source[:marker], source[marker:]
    if re.fullmatch(re.escape(FOOTER) + b"[a-f0-9]{64}\n", footer) is None:
        raise MigrationError("Checkpoint checksum footer is malformed or truncated.")
    if digest(payload).encode("ascii") != footer[len(FOOTER):-1]:
        raise MigrationError("Checkpoint checksum mismatch. Restore a coordinated plugin backup.")
    payload.decode("utf-8", errors="strict")
    return payload


def reseal(payload: bytes) -> bytes:
    if not payload.endswith(b"\n"):
        raise MigrationError("Checkpoint payload must end with a newline.")
    return payload + FOOTER + digest(payload).encode("ascii") + b"\n"


def _parts(raw: str) -> tuple[str, str]:
    """Separate a scalar from an inline comment without treating quoted # as a comment."""
    quote = None
    escaped = False
    index = 0
    while index < len(raw):
        char = raw[index]
        if quote == '"':
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
        elif quote == "'":
            if char == "'":
                if index + 1 < len(raw) and raw[index + 1] == "'":
                    index += 1
                else:
                    quote = None
        elif char in "'\"" and not raw[:index].strip():
            quote = char
        elif char == "#" and (index == 0 or raw[index - 1].isspace()):
            scalar = raw[:index].rstrip()
            return scalar, raw[len(scalar):]
        index += 1
    if quote is not None:
        raise MigrationError("Unterminated YAML scalar.")
    return raw.rstrip(), raw[len(raw.rstrip()):]


def _scalar(raw: str) -> str:
    value, _ = _parts(raw)
    value = value.strip()
    if not value or value[0] in "&*!|>{[" or value in ("null", "~"):
        raise MigrationError("Unsupported YAML scalar in a migration field.")
    if value.startswith('"'):
        try:
            parsed = json.loads(value)
        except ValueError as error:
            raise MigrationError("Unsupported quoted YAML scalar; keep migration fields as simple strings.") from error
        if not isinstance(parsed, str):
            raise MigrationError("Migration field must be a string.")
        return parsed
    if value.startswith("'"):
        if not value.endswith("'"):
            raise MigrationError("Invalid quoted YAML scalar.")
        return value[1:-1].replace("''", "'")
    if any(char in value for char in "\r\n\t"):
        raise MigrationError("Invalid YAML scalar.")
    return value


class YamlLines:
    """Conservative top-level view. Every unrelated byte is retained, including Bukkit tags."""

    def __init__(self, content: bytes):
        self.lines = content.decode("utf-8", errors="strict").splitlines(keepends=True)
        self.fields: dict[str, tuple[int, int, str]] = {}
        positions = []
        for index, line in enumerate(self.lines):
            body = line.rstrip("\r\n")
            if not body.strip() or body.lstrip().startswith("#"):
                continue
            if body.startswith("\t"):
                raise MigrationError("Tab-indented YAML is unsupported.")
            if body[0].isspace():
                continue
            match = TOP_KEY.fullmatch(body)
            if match is None:
                raise MigrationError("Unsupported top-level YAML syntax; no data was changed.")
            key = match.group(1)
            if key in self.fields:
                raise MigrationError("Duplicate top-level YAML key: " + key)
            self.fields[key] = (index, len(self.lines), match.group(2) or "")
            positions.append(key)
        for at, key in enumerate(positions[:-1]):
            start, _, raw = self.fields[key]
            self.fields[key] = (start, self.fields[positions[at + 1]][0], raw)

    def children(self, key: str) -> list[str]:
        if key not in self.fields:
            return []
        start, end, _ = self.fields[key]
        return [line.rstrip("\r\n") for line in self.lines[start + 1:end]
                if line.strip() and not line.lstrip().startswith("#")]

    def scalar(self, key: str, required: bool = True) -> str | None:
        if key not in self.fields:
            if required:
                raise MigrationError("Missing YAML field: " + key)
            return None
        if self.children(key):
            raise MigrationError("Migration field cannot be a block: " + key)
        return _scalar(self.fields[key][2])

    def mapping(self, key: str) -> dict[str, str]:
        if key not in self.fields:
            return {}
        raw, _ = _parts(self.fields[key][2])
        if raw.strip() not in ("", "{}"):
            raise MigrationError("Unsupported mapping syntax in " + key)
        result = {}
        for line in self.children(key):
            match = re.fullmatch(r"  ([A-Za-z0-9_.-]+):(?:[ \t]+(.*))?", line, re.ASCII)
            if match is None:
                raise MigrationError("Unsupported nested mapping in " + key)
            child = match.group(1)
            if child in result:
                raise MigrationError("Duplicate mapping key in " + key)
            result[child] = _scalar(match.group(2) or "")
        return result

    def change(self, key: str, value: str | None) -> bytes:
        lines = self.lines.copy()
        if key in self.fields:
            self.scalar(key)
            start, _, raw = self.fields[key]
            _, comment = _parts(raw)
            newline = "\r\n" if lines[start].endswith("\r\n") else "\n"
            if value is None:
                lines[start] = ""
            else:
                prefix = re.match(r"^[^:]+:[ \t]*", lines[start]).group()
                lines[start] = prefix + value + comment + newline
        elif value is not None:
            if lines and not lines[-1].endswith("\n"):
                raise MigrationError("YAML file must end with a newline before adding a field.")
            lines.append(key + ": " + value + "\n")
        return "".join(lines).encode("utf-8")


def _regular(path: Path):
    if path.is_symlink() or not path.is_file():
        raise MigrationError("Expected a regular file: " + str(path))


def _integer(raw: str, field: str) -> int:
    if re.fullmatch(r"0|[1-9][0-9]*", raw) is None:
        raise MigrationError("Invalid nonnegative integer in " + field)
    return int(raw)


def validate_audit(directory: Path, storage: dict[str, str]):
    if storage.get("checkpoint-format") != "1":
        raise MigrationError("Unsupported checkpoint integrity format.")
    sequence = _integer(storage.get("sequence", ""), "storage.sequence")
    audit = directory / "audit"
    if audit.is_symlink() or not audit.is_dir():
        raise MigrationError("The paired audit directory is missing or unsafe.")
    for segment in audit.iterdir():
        if segment.is_symlink():
            raise MigrationError("Audit directories must not contain symbolic links.")
        if segment.name == "legacy":
            continue
        if not segment.is_dir() or re.fullmatch(r"[0-9]{20}", segment.name) is None:
            raise MigrationError("Unexpected audit directory entry: " + segment.name)
        for file in segment.iterdir():
            if file.name.endswith(".tmp"):
                continue
            _regular(file)
            match = AUDIT_NAME.fullmatch(file.name)
            if match is None or int(match.group(1)) // 1000 != int(segment.name):
                raise MigrationError("Unexpected audit record name: " + file.name)
            if int(match.group(1)) > sequence:
                raise MigrationError("Uncheckpointed audit deltas remain. Start and normally stop the original server first.")
    if sequence > 0:
        identity = storage.get("last-audit-id", "")
        try:
            UUID(identity)
        except ValueError as error:
            raise MigrationError("Checkpoint audit identity is invalid.") from error
        _regular(audit / f"{sequence // 1000:020d}" / f"{sequence:020d}-{identity}.yml")


def backup_plugin(directory: Path, transaction: str) -> Path:
    backups = directory / "backups"
    backups.mkdir(exist_ok=True)
    if backups.is_symlink():
        raise MigrationError("backups directory must not be a symbolic link.")
    target = backups / ("UniversalShop-before-economy-migration-" + transaction + ".zip")
    descriptor, name = tempfile.mkstemp(prefix="migration-backup-", suffix=".tmp", dir=backups)
    os.close(descriptor)
    temporary = Path(name)
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for parent, folders, files in os.walk(directory, followlinks=False):
                parent_path = Path(parent)
                folders[:] = [folder for folder in folders if parent_path / folder != backups]
                for folder in folders:
                    if (parent_path / folder).is_symlink():
                        raise MigrationError("Plugin backup refuses symbolic links: " + folder)
                for name in sorted(files):
                    file = parent_path / name
                    if name.endswith(".tmp") or name == "ledger.lock":
                        continue
                    _regular(file)
                    archive.write(file, file.relative_to(directory).as_posix())
        with temporary.open("r+b") as source:
            os.fsync(source.fileno())
        os.replace(temporary, target)
        _fsync_directory(backups)
        return target
    finally:
        temporary.unlink(missing_ok=True)


def migrate(directory: Path, from_mode: str, to_mode: str, provider: str | None,
            confirmation: str | None, dry_run: bool = False) -> dict:
    directory = directory.expanduser().resolve(strict=True)
    if not directory.is_dir():
        raise MigrationError("Data folder is not a directory.")
    if from_mode not in ("INTERNAL", "VAULT") or to_mode not in ("INTERNAL", "VAULT"):
        raise MigrationError("Economy modes must be INTERNAL or VAULT.")
    if not dry_run and confirmation != ACKNOWLEDGEMENT:
        raise MigrationError("Explicit --confirm BALANCES_TRANSFERRED is required; transfer balances externally first.")
    if to_mode == "VAULT":
        if not provider or len(provider) > 200 or any(ord(char) < 32 or ord(char) > 65535 for char in provider):
            raise MigrationError("--provider must name the target Vault provider exactly (1 to 200 plain characters).")
    elif provider is not None:
        raise MigrationError("--provider is used only when the target mode is VAULT.")
    with LedgerLease(directory):
        pending = directory / "pending.yml"
        if pending.exists() or pending.is_symlink():
            raise MigrationError("Resolve pending.yml in the original economy before migrating.")
        state_file, config_file = directory / "state.yml", directory / "config.yml"
        _regular(state_file)
        _regular(config_file)
        before_state, before_config = state_file.read_bytes(), config_file.read_bytes()
        payload = verify_checkpoint(before_state)
        state, config = YamlLines(payload), YamlLines(before_config)
        if state.scalar("schema") != "1":
            raise MigrationError("Unsupported ledger schema.")
        if state.scalar("economy-mode") != from_mode or config.scalar("economy").upper() != from_mode:
            raise MigrationError("--from must match both the saved ledger and current config.")
        old_provider = state.scalar("economy-provider", required=False)
        if from_mode == "VAULT" and not old_provider:
            raise MigrationError("Original Vault provider binding is missing; restart/shut down in the original mode first.")
        if from_mode == to_mode and (to_mode == "INTERNAL" or old_provider == provider):
            raise MigrationError("The requested economy/provider binding is already active.")
        if "market" in state.fields:
            raw, _ = _parts(state.fields["market"][2])
            if raw.strip() not in ("", "{}") or state.children("market"):
                raise MigrationError("Reclaim all marketplace escrow before migrating.")
        for amount in state.mapping("market-credits").values():
            if _integer(amount, "market-credits") != 0:
                raise MigrationError("Collect all seller credits through the original Vault provider before migrating.")
        storage = state.mapping("storage")
        validate_audit(directory, storage)
        # Reparse after each exact-line change; no YAML object round trip touches Bukkit values.
        after_payload = state.change("economy-mode", to_mode)
        after_payload = YamlLines(after_payload).change("economy-provider",
                json.dumps(provider, ensure_ascii=False) if to_mode == "VAULT" else None)
        after_state = reseal(after_payload)
        after_config = config.change("economy", to_mode)
        guard_config = config.change("economy", GUARD)
        transaction = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + uuid4().hex[:12]
        receipt = {
            "receiptVersion": 1, "operation": "offline-economy-binding-migration",
            "id": transaction, "created": datetime.now(timezone.utc).isoformat(),
            "from": from_mode, "to": to_mode, "fromProvider": old_provider,
            "toProvider": provider, "balances": "Transferred externally; this tool did not alter wallet values or call any provider.",
            "acknowledgement": confirmation if not dry_run else "DRY_RUN",
            "checkpointSequence": int(storage["sequence"]),
            "before": {"stateFileSha256": digest(before_state), "checkpointPayloadSha256": digest(payload), "configFileSha256": digest(before_config)},
            "after": {"stateFileSha256": digest(after_state), "checkpointPayloadSha256": digest(after_payload), "configFileSha256": digest(after_config)},
            "completionRule": "Migration completed only when BOTH current file SHA-256 values match the after values. Otherwise keep server stopped and restore the full backup.",
            "temporaryEconomyGuard": GUARD,
        }
        if dry_run:
            return {"dryRun": True, "dataFolder": str(directory), "plan": receipt}
        backup = backup_plugin(directory, transaction)
        receipt["backup"] = str(backup)
        receipt_file = directory / "migrations" / (transaction + ".json")
        write_atomic(receipt_file, (json.dumps(receipt, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
        # Invalid economy first is a startup guard even for VAULT -> VAULT provider rebinding.
        try:
            write_atomic(config_file, guard_config)
            write_atomic(state_file, after_state)
            write_atomic(config_file, after_config)  # Final publication; no mutable receipt write follows it.
        except Exception as error:
            try:
                write_atomic(config_file, guard_config)
            except Exception:
                pass
            raise MigrationError("Migration write failed. Keep the server stopped; restore the complete backup "
                                 + str(backup) + " and inspect receipt " + str(receipt_file)) from error
        return {"completed": True, "dataFolder": str(directory), "backup": str(backup),
                "receipt": str(receipt_file), "from": from_mode, "to": to_mode, "provider": provider}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-folder", required=True, type=Path)
    parser.add_argument("--from", dest="from_mode", required=True, type=str.upper, choices=("INTERNAL", "VAULT"))
    parser.add_argument("--to", dest="to_mode", required=True, type=str.upper, choices=("INTERNAL", "VAULT"))
    parser.add_argument("--provider", help="Exact getName() of the intended Vault economy provider.")
    parser.add_argument("--confirm", help="BALANCES_TRANSFERRED acknowledges the separate, completed external balance transfer.")
    parser.add_argument("--dry-run", action="store_true", help="Validate and show a migration plan without changing plugin data.")
    args = parser.parse_args(argv)
    try:
        result = migrate(args.data_folder, args.from_mode, args.to_mode, args.provider, args.confirm, args.dry_run)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (MigrationError, OSError, UnicodeError) as error:
        print("Migration refused: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
