# Import application acceptance

These public synthetic fixtures and read-only assertions exercise the integrated import-application implementation. They are separate from the accepted runtime8 Folia checkpoint. The managed server matrix requires its own explicitly selected artifact and reports.

The fixtures contain three baseline records and four updated records, stable synthetic UUIDs, shared fake skin text, normalized mount/guard references, and opaque references that must remain preserved. No private migration file is used. The JSON bytes are copied to an owned `.yml` inbox filename solely to exercise the existing YAML-import entry point; JSON is valid YAML and the source archive must remain byte-for-byte identical.

Managed coverage:

- Stage leaves every active definition and recovery pointer unchanged; repeated staging has the same content identity.
- Explicit apply installs dormant definitions with imported combat disabled; repeats are idempotent only while output remains exact.
- Duplication creates deterministic independent UUIDs and remaps only normalized resolved references; original/unknown values remain unchanged.
- A concurrent administrator edit makes a previously reviewed replacement stale, with no partial installation.
- A fresh replacement increments revisions, adds the new record, and atomically updates last-valid recovery pointers.
- A real managed restart precedes rollback. Rollback restores exact prior definition/pointer bytes and removes application receipts; copied records can be rolled back independently.
- A second restart confirms that deleted definitions remain absent. Existing default preview/apply/repeat/rollback behavior is also exercised.

`python3 qa/import-applications/validate-cli.py` exercises the installed converter in independent processes, records distribution/source/log hashes, and verifies argument rejection, exact counts, stale application refusal, revision changes, all three modes and rollback. Read-only validation is checked against file paths, bytes and modification times for empty, valid, invalid and pending-transaction stores. It does not start a server. The integrated CLI passed 11 grouped checks across 47 invocations in `.qa/import-cli-validation/1793808976660376000-50856/validation.json`.

`validate-fixtures.py --draft-run <captured-run>` remains a separately labelled local draft check; it must not be cited as integrated/server evidence. Its three reopen/rollback phases passed in `.qa/import-fixture-validation/1793808774866168000-48884/validation.json`.

Large compact-journal/crash recovery remains a separate core-test gate. Managed tests use dedicated `animus-import-<platform>-<version>` instances and retain all failed attempts. Run `qa/multiplexor/import-applications.py --help` for the explicit Multiplexor workspace, build-run, artifact hash, platform and version arguments. The wrapper records actual restarts, console receipts, archive bytes, definition/checkpoint snapshots, server/plugin hashes and final cleanup. NPC rendering is disabled for this filesystem/administration scenario.

The runtime9 managed matrix passed 14 checks on each of Paper, Spigot and Folia 26.1.2, 26.2 and 26.3 (126 total), using actual Multiplexor build `run-1793809087767551-52812` and plugin SHA256 `72c41097d3e3c7aea3ce7cd4956d5b3073b26900f27c0dddbc1db7486cf5540d`. Each target completed three server starts, including a restart before rollback and a restart after all records were removed. Reports and exact source snapshots remain under the retired QA workspace's `animus-evidence/animus-import-*/application-*/result.json`. All nine attempts passed without a retry.

Cleanup receipt in the retired QA workspace's `animus-evidence/runtime-9-import-cleanup-1793809290164652000.json` (SHA256 `a2758c638cab83dfd4bd005510b817760530b239b247a0e237da8603174dbaa7`) records all 18 current/earlier owned QA ports closed, all instances stopped, and no remaining NPC definitions or recovery pointers. This data/command test does not establish graphical appearance, broader imported-behavior parity or full Folia simulation support. Independent acceptance auditing is recorded separately.

Independent strict acceptance passed in `animus-evidence/audits/imports-runtime-9-final-1793809554180223000/audit.json` (SHA256 `450a1cd6625c76db786d1d5ca6ec6ec44fb88dba8bb4d1f122b9e1450ea2148c`). It checks all nine complete reports against source/candidate/checkpoint payloads, journal inverses, actual phase receipts, exact deployed hashes and currently closed ports. The archived aggregate is `animus-evidence/runtime-9-import-final.json`.

Runtime10 repeats this entire nine-target data workflow after the public module API and shared file-mutation lane integration. Build `run-1793811646388580-96533`, plugin SHA-256 `a6b3a8632f37595e55959c4f0cc0bac2a39ea73a7d57cbb863c5e5a7e886a601`, passed all 126 checks on fresh `animus-r10-import-*` instances. The optional `--instance-name` must match `animus-r<N>-import-<platform>-<exact-version-with-hyphens>`; the original defaults and scenario body are unchanged. Independent audit the retired QA workspace's `animus-evidence/audits/imports-runtime-10-final-1/audit.json` has SHA-256 `a979ccd3f8bd2bca5891fccca4705e5040b2e9d234f3a5f194f27acf91cc0acd`. All nine latest attempts stopped with closed ports and no owned definitions or recovery pointers. A prior Folia 26.3 attempt retained its failed result for a JLine console-thread shutdown error despite passing all data assertions; its unchanged retry passed. This evidence concerns data and administration, not rendering or behavior execution.
