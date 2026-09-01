# CLAUDE.md

Guidance for working in this repo. See `README.md` for the full user-facing docs and
`refactoring-idx-sync-rewrite.md` for the design brief, analysis, and phased plan behind the rewrite.

## What this is

`idx-sync` — a fast, safe, **one-way** folder synchronization / backup CLI, written in idiomatic Kotlin.
It auto-detects folder pairs via hidden `.idxsync` markers, computes what changed (create/update/delete),
and mirrors a **source** folder onto its **target**, with a Mordant-powered full-width console UI.

This is a ground-up Kotlin rewrite of an earlier Java tool (kept only as a behavioral reference). The
on-disk `.idxsync` YAML format is **backward-compatible** with the original — don't break it.

## Build, test, run

```bash
./gradlew build         # compile + run all tests (also builds the shadow jar; `build` dependsOn shadowJar)
./gradlew test          # tests only
./gradlew shadowJar     # self-contained fat jar → build/libs/idx-sync.jar
./gradlew installDist   # runnable install → build/install/idx-sync
./gradlew               # no args: clean + build + shadowJar (defaultTasks)

java -jar build/libs/idx-sync.jar demo 10s   # quickest way to eyeball the UI
```

- **JDK 17** target (source/target compatibility 17, Kotlin `jvmTarget` 17). Kotlin 2.2.x, Gradle Kotlin DSL.
- Entry point: `ch.frostnova.cli.idx.sync.MainKt` (`application` plugin, `applicationName = "idx-sync"`).

## Stack

- **Clikt** — CLI command tree / parsing. **Mordant** — rich cross-platform terminal output (both by ajalt;
  Mordant is why colors work on Windows, not just Linux — this was a core goal of the rewrite).
- **Jackson-YAML** (`jackson-dataformat-yaml` + `jackson-module-kotlin` + `jsr310`) for the `.idxsync` format.
- Tests: **JUnit 5 + AssertJ + MockK**. Tests are first-class here — the old tool's biggest gap was coverage.

## Architecture (package `ch.frostnova.cli.idx.sync`)

Clean separation of concerns; keep it that way when adding code.

| Package  | Responsibility |
|----------|----------------|
| `core`   | Pure domain model (`SyncMode`, `SyncAction`, `FileChange`, `SyncPair`, `SyncResult`) — no I/O |
| `config` | `.idxsync` marker model (`IdxSyncFile`) + `IdxSyncFileRepository` (read/write YAML) |
| `filter` | `AntPathMatcher`, `PlatformExcludes`, `ExcludeFilter` (the effective per-file filter) |
| `scan`   | `FileTreeWalker`, `SyncFolderScanner` (find markers), `SyncPairResolver` (source↔target) |
| `diff`   | `DiffEngine` — change detection between two trees |
| `sync`   | `AtomicFileWriter` (crash-safe) + `FileSynchronizer` (safety-guarded copy/delete) |
| `ui`     | `ConsoleUi` (Mordant front-end) + `Format` |
| `cli`    | `Commands` (Clikt tree), `SyncApplication` (orchestration), `DemoRunner` |

## Safety invariants — do not break these

These are the whole point of the tool; every change touching `sync/`, `diff/`, or `filter/` must preserve
them, and they should stay covered by tests:

1. **Source is strictly read-only during a sync** — never write/move/delete inside a source folder. A hard
   guard (`FileSynchronizer.isProtected`) refuses any write/delete whose destination lands inside a
   protected root. It resolves symlinks first (`realLocation`) so a symlinked directory can't disguise a
   write into the source, and `SyncApplication` protects **every** scanned source root (targets on restore),
   not just the selected pair's.
2. **Crash-safe writes** — copy to a hidden temp file *in the same directory*, `fsync` it to disk
   (`fd.sync()`), then atomically swap (old → backup → replace → drop backup). An abort or power loss
   mid-copy must never corrupt or lose the target. Beware `ATOMIC_MOVE` failing across filesystems — the
   fallback must still be crash-safe. `AtomicFileWriter(verify = true)` additionally hash-checks the written
   bytes against the source before the rename (wired to `sync --verify`).
3. **Backup-safe** — a 0-byte, missing, unreadable, or non-regular source is **skipped and reported**,
   never written over a good target.
4. **`restore` never deletes** — restore is the only mode that writes to a source, and it only
   creates/updates. It requires a source-id, shows the diff, and asks before writing.
5. **Platform junk is always excluded**, regardless of user patterns.

## Conventions

- Idiomatic Kotlin; SOLID, small classes, clear package boundaries (see table above).
- **Platform excludes are data, not code** — they live in `src/main/resources/platform-excludes.yaml`,
  matched by file/dir name, case-insensitively (excluding a dir prunes its subtree). Add/change excludes
  there, not in code. The always-on set is grouped (`idx-sync`, `windows`, `macos`, `linux`,
  `editors-and-temp`); the `editors-and-temp` group is the safe place for generic junk like `*.bak`.
  Be cautious adding to the always-on set for a *backup* tool — patterns there can never be turned off
  per-folder, so anything with real-file false positives (e.g. `*.log`, `build`, `dist`) belongs in
  per-folder `exclude-patterns`, not here.
- Change detection: present in source only → **create**; both but size differs or mtime differs > 2s →
  **update**; target only → **delete** (mirror mode; never in restore). Copies preserve source mtime.
  The 2s threshold (`DiffEngine.updateThreshold`) tolerates FAT/exFAT's 2-second mtime resolution.
- Exclude-pattern semantics: slash-less pattern matches that name at any depth (gitignore-like); a pattern
  with `/` or `**` uses ant matching against the path relative to the root.

## Commands (user-facing surface)

`scan` · `diff` · `sync [source] [--verify]` · `source <path> <name>` · `target <path> <id>` ·
`pair <source> <target>` · `remove <path>` · `restore <source> [sub-path]` · `version` · `demo <duration>`.
No args → usage. The banner is `src/main/resources/banner.txt`. `sync`/`restore` accept a source
**selector** that matches a pair's source by `folder-id`, folder name, **or** path (source or target dir);
it selects every matching pair (see `SyncApplication.matchPairs`) and errors when nothing matches.

Commands live in `cli/Commands.kt` (one `CliktCommand` per verb) and are registered in `Main.kt`'s
`subcommands(...)`. Set the command name explicitly via `CliktCommand(name = "...")` when the class name
doesn't lowercase to it (e.g. `PairCommand` → `"pair"`).

`main` reads global `--ascii` / `--no-unicode` (also `NO_UNICODE` / `IDX_SYNC_ASCII` env) *before* Clikt
parsing and strips them, building a plain-ASCII `ConsoleUi`. `SyncApplication.run`/`restore` return a
`Boolean`; the `Sync`/`Restore` commands throw `ProgramResult(1)` on failure so `main` sets a non-zero exit
code. Shell completion is **not** wired — Clikt 5's `CompletionCommand` produced empty output under the
manual parse loop; revisit if needed.
