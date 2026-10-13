# Development and validation

## Build

```sh
mvn clean verify
```

Maven compiles against Spigot API 1.8.8 with Java 8 bytecode. No Minecraft server internals are used in production shop logic. `api-version: 1.13` prevents modern servers from remapping item names to pre-flattening material constants; older tested servers ignore that descriptor field. Explicit modern/legacy aliases handle catalog differences.

`CoreTest` checks money precision, invalid amounts, overflow, marginal pricing, tax rounding, resale floors, rotation determinism and atomic replacement. The optional `ServerTests` suite is disabled unless the disposable server starts with `-Duniversalshop.test=true`. It additionally requires the server console; ordinary players cannot invoke it.

## Integration matrix

Requirements: Python 3.10+, Node.js, Minecraft protocol dependencies, and the Java runtimes required by the selected server versions. Install the client dependencies outside the production plugin:

```sh
npm install --prefix .tools/protocol minecraft-protocol@1.68.0 minecraft-data@3.117.0
```

Store runtimes under `.tools/jdk8`, `.tools/jdk16`, `.tools/jdk17`, `.tools/jdk21` and `.tools/jdk25`, or pass another root with `--runtimes`.

```sh
python tools/matrix.py 1.7.10 1.8.8 1.12.2 1.21.11 26.3 --runtimes .tools --protocol .tools/protocol
python tools/matrix.py --all --refresh --workers 4 --runtimes .tools --protocol .tools/protocol
```

The harness downloads official Paper binaries and validates their published SHA-256. It chooses the latest stable build when one exists, otherwise the newest available build, whose channel is recorded. `--cache <folder>` can import existing version subfolders containing `server.jar`, `download.json`, and cached bootstrap libraries. Worlds and plugin state are never imported.

Each case creates a disposable loopback-only offline server with a fresh world and plugin data, connects **two network clients**, executes server-side integration scenarios against their actual players, stops the server, restarts it and reconnects both clients to verify persistence. The suite checks buy/sell accounting, limits, stock, insufficient funds, custom metadata protection, full inventories, batch sales, payments, market escrow/credit/tax/cancellation/expiry, permissions, disabled worlds, discounts, console service delivery, pending-transaction locks and GUI double-confirm guards.

GUI click events are injected on the server for determinism. The clients log in and receive server packets but do not render Minecraft graphics. These tests do not prove the visual layout in a real game client. For 26.2/26.3 the harness exports packet IDs from the running server registry and uses a minimal client for login/configuration/keepalive/teleport acknowledgment; it does not pretend an older protocol schema is current.

Integration runs write `docs/compatibility-results.json` with the exact plugin SHA-256, per-version server build/channel, runtime, status and check counts. Logs stay in `test-servers/<version>/universalshop-test.log` and are bundled separately with release evidence. Test servers bind only to `127.0.0.1`; do not expose their offline-mode ports publicly.

For the optional Vault suite, put the official Vault 1.7.3 and EssentialsX 2.22.0 JARs in `.tools/vault`, then run:

```sh
python tools/matrix.py 1.21.11 26.3 --vault-fixtures .tools/vault --report docs/vault-results.json
```

These cases use separate `1.21.11-vault` / `26.3-vault` folders. Once both matrices pass against the same JAR, run `python tools/render_report.py` and `python tools/package_release.py` to generate the readable matrix and release bundles. Packaging refuses mismatched hashes or incomplete results.

Running the harness accepts Minecraft's EULA for its disposable test servers by writing `eula=true`. It removes only the named test world/plugin-data directories beneath this checkout's `test-servers` folder. Do not point real server data at that location.

## Manual deployment checklist

Use a staging copy to inspect GUI layout with a real client, validate prices against your crafting/farming systems, verify your chosen Vault provider, and exercise custom commands with their actual dependent plugins. Configure permissions before opening services that grant valuable items/ranks. Preserve a coordinated server backup before migrating data or switching economy mode.
