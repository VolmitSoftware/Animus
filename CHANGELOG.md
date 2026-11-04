# Changelog

## 0.1.0 - Unreleased

### Added

- Initial Animus project scaffold based on Husk 0.1.2 (`82c39ef`).
- Shared runtime, TOML plugin settings, lossless legacy JSON settings migration, and Animus startup branding.
- Java 25 builds for exactly Minecraft 26.1.2, 26.2, and 26.3; Bukkit-first distribution, with experimental mod-loader scaffolds.
- Gradle wrapper, runtime tests, shaded dependency isolation, artifact verification, and Multiplexor build targets.
- Immutable JSON NPC definitions, revision/hash conflict checks, atomic transaction journal, backups, rollback, and last-valid registry reloads.
- Shared local/server Citizens conversion with staged previews, complete source/record preservation, per-field reports, skin deduplication, identity collision checks, and dormant apply.
- Permission-gated administration, bounded file-worker queue, and platform-aware command replies.
- Storage/import/configuration/command tests and a reproducible live console/restart scenario.

Packet rendering, movement, interaction, behaviors, combat, extension modules, additional-platform NPC implementations, and the browser editor remain unimplemented.
