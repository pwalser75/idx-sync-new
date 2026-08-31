# Refactoring: Idx-Sync Rewrite (`idx-sync-new`, Kotlin)

> This is a **from-scratch rewrite** of the existing Java `idx-sync` tool into a new Kotlin project
> `idx-sync-new`, using the old project purely as a behavioral reference. Because it is greenfield, the
> "green baseline" is the *reference* project's observable behavior; each phase keeps the **new** project
> building with its own tests green.

## Definition

*Written and owned by the developer (from `idx-sync-refactoring.md`). Refine before G1.*

- **Purpose (why):** The existing Java `idx-sync` CLI works but has real problems: folder scanning is slow
  (parallelization didn't help and made output confusing), colored output only works on Linux (Windows
  falls back to unformatted), the console output is a poor compromise that doesn't use the full width, the
  remaining-time estimate is inaccurate (it doesn't know the total file count because files are discovered
  on the fly), and there are almost no unit tests. Recreate it cleanly.
- **Scope (what should be done):** Recreate the program from scratch as a new project `idx-sync-new`:
  - **Kotlin, idiomatic style.**
  - **Nice architecture** — separation of concerns, SOLID.
  - **Excellent, modern, state-of-the-art console output** with a "wow" factor, using an optimized
    console library.
  - **Recoverability** — survive an abort mid-copy without data loss: when updating a target file, write to
    a hidden temp file in the same directory, then rename old→backup, temp→final, delete backup.
  - **Exclude patterns** work well, plus an **internal always-exclude set** of platform system files
    (Linux, Windows, macOS, …).
  - **Backup-safe** — detect a 0-byte source file and report + skip it instead of destroying the target.
  - **Restore option** — sync in reverse to restore files at the source, but **never delete** at the source.
  - **Demo mode** — simulate copying files to exercise the UI, with a duration argument
    (e.g. `idx-sync demo 15s`).
- **Constraints:**
  - New project lives at `idx-sync-new/` (existing `idx-sync/` untouched, used as reference).
  - Kotlin + idiomatic style; SOLID architecture with clear separation of concerns.
  - Atomic, abort-safe file replacement is mandatory (never leave a half-written target).
  - Backup safety is mandatory (0-byte / unreadable source must never destroy a good target).
  - Restore must never delete at the source.
  - Cross-platform console output (must look good on both Linux and Windows — no unformatted fallback).
  - Real unit-test coverage (the old project's biggest gap).
- **Non-goals (out of scope):**
  - Bidirectional / two-way sync (this is one-way; restore is a separate explicit reverse mode).
  - Content-hash comparison (compare by size + last-modified, like the reference) — unless raised later.
  - GUI; network/cloud targets; a scheduler/daemon.
  - Migrating or deleting the old `idx-sync` project.

## Analysis

*Written by Claude in phase 1, from reading the reference `idx-sync/` project.*

### Current state (reference project `idx-sync/`)

Java 11, Gradle + Maven, Jackson-YAML, shadow jar. Package `ch.frostnova.cli.idx.sync`. Flow driven by
`IdxSync` (main): `scan()` → `diff()` → `sync()`.

- **`IdxSync`** — arg parsing (`run`/`scan`/`diff`/`source`/`target`/`remove`), logo/usage printing, and
  all result formatting inline with ANSI escapes.
- **`config/IdxSyncFile`** — the `.idxsync` YAML model: `folder-id`, `folder-name`, `exclude-patterns`,
  `include-hidden`, `source-folder-id`, `tags`. Source folders have an id+name; target folders carry
  `source-folder-id`. `ObjectMappers.yaml()` is the Jackson mapper.
- **`filter/PathFilter`** — ant-style wildcard → regex matcher (`*`, `?`, `**/`), with a hardcoded
  `DEFAULT_EXCLUDES` list (`.idxsync`, `$RECYCLE.BIN`, `System Volume Information`, `Windows`, `Thumbs.db`,
  `node_modules`, …).
- **`io/FileSystemUtil`** — two traversal implementations: a `ForkJoinPool`+`Phaser` **parallel** walker
  and a recursive single-threaded one, both estimating progress by dividing weight across children.
- **`task/*`** — a `Task<T>` abstraction with `TaskRunner` + `ProgressMonitor`; concrete tasks
  `FindSyncFilesTask` (walks filesystem roots up to depth 5 to find `.idxsync` files), `CompareFilesTask`
  (walks source + target, computes CREATE/UPDATE/DELETE), `SyncFilesTask` (does the copying/deleting).
- **`monitor/console`** — `ConsoleProgressMonitor`, `ConsoleProgressBar`, `ProgressBarStyle.autodetect()`,
  `AnsiEscape`, `TextIcon`, plus a `ProgressTimer` with a cost model
  (`FIXED_FILE_COST + bytes*FILE_COST_PER_BYTE`) for the remaining-time estimate.
- **`SyncFilesTask.run()`** copies via a plain buffered stream **directly onto the target path** (no temp
  file), then sets last-modified from the source. **No atomicity, no 0-byte guard.**

### Problems (motivating the rewrite)

1. **Slow scanning** — traversal + per-file attribute reads dominate; the parallel walker adds complexity
   and confusing interleaved output without a real speedup.
2. **Windows console** — ANSI coloring only works on Linux; Windows gets an unformatted fallback.
3. **Poor output** — doesn't use full terminal width; hand-rolled progress bar.
4. **Inaccurate ETA** — files are discovered on the fly, so total count is unknown; the cost model
   (`FIXED_FILE_COST`/`FILE_COST_PER_BYTE`) can't compensate.
5. **Not abort-safe** — `SyncFilesTask` writes straight to the target; an abort mid-write corrupts it.
6. **Not backup-safe** — a truncated/0-byte source overwrites a good target.
7. **Almost no tests** — only `PathFilterTest`, `ByteFormatTest`, `LimitedListTest`, `IdxSyncFileTest`.
8. **No restore, no demo mode.**

### Affected code (new project)

Everything is new under `idx-sync-new/`. Nothing in `idx-sync/` is modified. Existing `.idxsync` files in
the field should keep working, so the config format stays compatible.

### Dependencies & blast radius

Self-contained CLI; no external callers. The only compatibility contract is the on-disk **`.idxsync` YAML
format**, which we keep backward-compatible so already-marked folders continue to work.

### Test coverage

Reference coverage is minimal (4 small unit tests). The rewrite treats tests as first-class: unit tests for
the path matcher, config round-trip, diff engine, and — critically — the atomic/abort-safe writer and the
0-byte safety guard, using JUnit 5 temp directories.

### Risks

- **Atomic replace across filesystems** — `ATOMIC_MOVE` can fail across devices; need a correct fallback
  that is still crash-safe (temp in the *same* directory, per the brief).
- **Cross-platform terminal** — must verify the chosen library renders on Windows (the whole point).
- **Traversal correctness** — symlink loops, permission-denied dirs, hidden-file handling must match intent.
- **Data safety** — a bug in delete/overwrite logic risks real data; the 0-byte guard and restore's
  never-delete rule must be covered by tests.

## Plan

*Written by Claude in phase 2. Ordered phases, each ending green. Checkboxes are the progress state.*

**Confirmed tech stack:** Kotlin 2.x on JDK 21, Gradle (Kotlin DSL) + Shadow fat-jar,
**Clikt** for CLI parsing, **Mordant** for rich cross-platform terminal output (both by ajalt; Mordant
handles Windows ANSI natively — this fixes the Windows problem), **Jackson-YAML** (`jackson-dataformat-yaml`
+ `jackson-module-kotlin`) for the `.idxsync` format, **JUnit 5 + AssertJ + MockK** for tests. Package root
`ch.frostnova.cli.idx.sync`.

**Target architecture (SOLID, separated):**
```
core     — SyncMode, SyncAction, FileChange, SyncPair, SyncResult (pure domain, no I/O)
config   — IdxSyncFile model + IdxSyncFileRepository (read/write .idxsync YAML)
filter   — AntPathMatcher + ExcludeFilter (user patterns + built-in platform excludes)
scan     — FileTreeWalker, SyncFolderScanner, SyncPairResolver
diff     — DiffEngine (source vs target -> List<FileChange>)
sync     — AtomicFileWriter, FileSynchronizer (safe + abort-safe), restore support
ui       — ProgressReporter (interface) + MordantProgressReporter + demo driver
cli      — Clikt commands: run / scan / diff / source / target / remove / restore / demo
```

### Phase 1 — Project scaffold ✅
- [x] Create `idx-sync-new/` with Gradle Kotlin DSL, JVM 21 target, Shadow plugin, package layout.
- [x] Add dependencies: Clikt, Mordant, Jackson-YAML + jackson-module-kotlin, JUnit 5, AssertJ, MockK.
- [x] Minimal `main()` + one smoke test.
- [x] **Verify:** `./gradlew build` green (compiles, smoke test passes, app runs).

### Phase 2 — Core domain model + `.idxsync` config ✅
- [x] `core` domain types: `SyncMode` (SYNC/RESTORE), `SyncAction` (CREATE/UPDATE/DELETE/SKIP), `FileChange`,
      `SyncPair`, `SyncResult` (with `plus`/`isEmpty`).
- [x] `config/IdxSyncFile` (Jackson-YAML serializable, same field names as reference) +
      `IdxSyncFileRepository` (read/write/remove/resolve `.idxsync`).
- [x] Unit tests: config YAML round-trip incl. the legacy README sample (backward-compat), unknown-field
      tolerance, malformed→null.
- [x] **Verify:** `./gradlew build` green (8 tests).

### Phase 3 — Path filtering & platform excludes ✅
- [x] `filter/AntPathMatcher` (`*`, `?`, `**`), exact semantics ported from `PathFilter` (+ optional
      case-insensitive mode).
- [x] `filter/PlatformExcludes` — built-in always-exclude sets for Windows / macOS / Linux + idx-sync's own
      files (`.idxsync`, `*.idxtmp`, `*.idxbak`); segment-based, case-insensitive.
- [x] `filter/ExcludeFilter` combining user `exclude-patterns` + `include-hidden` + platform excludes.
- [x] Unit tests: ported `PathFilterTest` wildcard cases + platform + hidden + user-pattern cases.
- [x] **Verify:** `./gradlew build` green (20 tests).

### Phase 4 — Filesystem traversal & scanning ✅
- [x] `scan/FileTreeWalker` — clean sequential DFS (symlink-safe, permission-safe, per-dir error callback),
      `Visit.CONTINUE`/`SKIP_SUBTREE`.
- [x] `scan/SyncFolderScanner` — find `.idxsync` from filesystem roots; depth-limited; skips
      platform-excluded/hidden dirs and pseudo filesystems (`/dev`,`/proc`,`/sys`,`/run`).
- [x] `scan/SyncPairResolver` — match source↔target by `source-folder-id`, merge exclude patterns, sort.
- [x] Unit tests over temp directory trees (resolve, orphan target, merge, depth limit, skips, malformed).
- [x] **Verify:** `./gradlew build` green (26 tests).

### Phase 5 — Diff engine ✅
- [x] `diff/DiffEngine` — walk origin+destination, produce `FileChange`s (CREATE/UPDATE/DELETE) by size +
      last-modified (**abs** >1s mtime delta or size mismatch = UPDATE — fixes a reference bug that missed
      source-newer changes); honor exclude filter; `SyncMode.RESTORE` reverses direction and never deletes.
- [x] Deleted subtrees collapse to their root (one recursive delete, no redundant ops).
- [x] Unit tests: created/updated/deleted/unchanged, source-newer, subtree-root delete, restore-never-
      deletes + reversed direction, excludes/hidden/platform respected.
- [x] **Verify:** `./gradlew build` green (32 tests).

### Phase 6 — Safe, abort-safe sync engine ✅
- [x] `sync/AtomicFileWriter` — temp→backup→rename scheme in the same dir (`.name.idxtmp`/`.idxbak`),
      `ATOMIC_MOVE` with plain-move fallback, backup-restore on failure, temp cleanup in `finally`; internal
      stream-based seam for testability.
- [x] `sync/FileSynchronizer` — apply `FileChange`s via a `SyncListener`; **0-byte / missing / unreadable /
      non-regular source guard** → SKIP + warning, never touches target; preserves last-modified; safe
      recursive delete; aggregates `SyncResult` (created/updated/deleted/skipped/bytes/warnings/errors).
- [x] Added `warnings` to `SyncResult` (skips reported distinctly from errors).
- [x] Unit tests: create/replace/preserve-mtime/byte-progress, **abort mid-write leaves target intact + no
      litter**, 0-byte skip protects target, missing-source skip, recursive delete, listener progress.
- [x] **Verify:** `./gradlew build` green (41 tests).

### Phase 7 — Console UI (Mordant) + progress ✅
- [x] `ui/ConsoleUi` (Mordant) — logo, styled messages, an indeterminate **spinner** phase (scan/diff) and
      a full-width **byte-accurate** progress bar (bar · % · speed · ETA) for the copy phase; the
      synchronizer drives it through a `SyncListener`. Mordant auto-downgrades to plain text (fixes Windows).
- [x] `ui/Format` — `formatBytes` / `formatDuration`.
- [x] Result/summary rendering (created/updated/deleted/skipped/bytes/warnings/errors + elapsed).
- [x] Unit tests: formatting + static rendering on a NONE-ansi terminal (live animations verified via the
      demo in Phase 8).
- [x] **Verify:** `./gradlew build` green (44 tests).
- Note: dropped the separate `ProgressReporter` interface — Mordant already provides the plain-terminal
      fallback, so a second abstraction was YAGNI. `ConsoleUi` is the single reporter (used by real sync
      and demo alike).

### Phase 8 — CLI wiring (Clikt) + demo mode ✅
- [x] Clikt command tree: `run` (default), `restore`, `scan`, `diff`, `source <path> <name>`,
      `target <path> <source-id>`, `remove <path>`, `demo <duration>` — each with help text; logo on every
      invocation; bare `idx-sync` defaults to `run`.
- [x] `cli/SyncApplication` — shared scan→compare→sync orchestration driving `ConsoleUi`.
- [x] `cli/DemoRunner` — simulates 2 pairs + 18 synthetic file copies across the requested duration
      (`demo 15s`), touching no files; `parseDurationMillis` supports `15s`/`2m`/`500ms`/`1h`/bare seconds.
- [x] Unit tests: duration parsing (valid/invalid), marker serialization no-leak.
- [x] **Verify:** `./gradlew build` green (47 tests); `demo` runs with animated bars in both color and
      plain (NO_COLOR) modes; `--help`, `source`/`target`/`remove` verified on a temp dir.
- Fix: `@get:JsonIgnore` on `isSource`/`isTarget` so computed props no longer leak into written `.idxsync`.

### Phase 9 — Packaging, docs, end-to-end ✅
- [x] Shadow fat-jar executable (`build/libs/idx-sync.jar`, 12 MB, runnable via `java -jar`).
- [x] README (usage, `.idxsync` format, exclude semantics, change rules, build, architecture table).
- [x] End-to-end integration test: real temp source→target sync — mirror/exclude/0-byte-skip/delete,
      0-byte-protects-target, idempotent re-run + update propagation, restore-never-deletes.
- [x] Filter usability fix: slash-less patterns (`node_modules`) now exclude at any depth (gitignore-like);
      seeded source defaults switched to bare names.
- [x] Final full **Verify:** `./gradlew clean build shadowJar` green (52 tests); fat jar runs.

## Work Log

*Appended by Claude as work happens — what changed and why. Append-only.*

### 2026-08-31 — Intake
- Read `idx-sync-refactoring.md` and the reference `idx-sync/` project (IdxSync, IdxSyncFile, PathFilter,
  FileSystemUtil, FindSyncFilesTask, CompareFilesTask, SyncFilesTask, ConsoleProgressMonitor).
- Pre-filled Definition from the brief; wrote Analysis and a 9-phase Plan.
- **Tech stack confirmed by user:** Mordant + Clikt (console/CLI), Jackson-YAML + jackson-module-kotlin
  (`.idxsync`), JUnit 5 + AssertJ + MockK (tests), Kotlin on JDK 21, Gradle Kotlin DSL + Shadow.
- User said "start working on it" → proceeding into Phase 1 (scaffold). G1/G2 taken as approved (Definition
  is the user's own brief; stack confirmed). Baseline = reference project behavior + new project builds green.

### 2026-08-31 — Baseline (toolchain)
- Environment JDKs: 11, 17, 25 (full JDKs); only a JRE for 21 (`/opt/abaclient/jre21`, no compiler).
- Decision: run Gradle + Kotlin on **JDK 25**, emit **JVM 21 bytecode** (`jvmTarget = JVM_21`, no Java
  toolchain block → avoids needing a JDK-21 install/download). Honors "JDK 21" as the bytecode target.
- **Gradle 8.10.1 fails on JDK 25** → generated a **Gradle 9.1.0** wrapper (full Java 25 support) using
  JDK 17 to bootstrap. **Shadow 8.3.5** broke on Gradle 9 (`mainClassName` removed) → bumped to **9.0.0**.
  **Kotlin 2.1.0** compiler couldn't parse Java "25" → bumped to **Kotlin 2.2.20**.
- New project builds green on this toolchain — baseline established.

### 2026-08-31 — Phase 1: Project scaffold
- Did: created `idx-sync-new/` (settings/build/gradle.properties/.gitignore, Gradle 9.1.0 wrapper), package
  `ch.frostnova.cli.idx.sync`, minimal `Main.kt`, `SmokeTest`. Dropped the unused kotlinx-serialization
  plugin (using Jackson).
- Verify: `./gradlew build` green; `run` executes; smoke test passes.
- Commit: n/a (not requested yet).

### 2026-08-31 — Phase 2: Core domain model + `.idxsync` config
- Did: `core/Model.kt` (SyncMode, SyncAction, FileChange, SyncPair, SyncResult); `config/IdxSyncFile`
  (Jackson kebab-case props, `@JsonIgnoreProperties(ignoreUnknown)`, isSource/isTarget); `config/
  IdxSyncFileRepository` (YAMLMapper + Kotlin module, read/write/remove, malformed→null).
- Decisions: kept reference field names for `.idxsync` backward-compat; SyncPair carries excludePatterns +
  includeHidden (keeps `core` free of filter deps); FileChange uses origin/destination so RESTORE reuses
  the same type with roles swapped upstream. Fix: `jacksonObjectMapper(factory)` overload takes a lambda —
  switched to `YAMLMapper(factory).registerKotlinModule()`.
- Verify: `./gradlew build` green, 8 tests (1 smoke + 7 config).
- Commit: n/a.

### 2026-08-31 — Phase 3: Path filtering & platform excludes
- Did: `filter/AntPathMatcher` (regex translation, `\`→`/` normalization, optional ignoreCase),
  `filter/PlatformExcludes` (Windows/macOS/Linux/IDX segment sets, case-insensitive, `+` composable),
  `filter/ExcludeFilter` (segment-wise platform+hidden checks that prune subtrees, user ant patterns on the
  full relative path).
- Decisions: preserved the original `**`→`.*` semantics exactly (so `**/test.txt` does NOT match root
  `test.txt`) — verified by ported tests. Redesigned defaults as a filename/segment matcher (cleaner &
  faster than the old absolute-path ant list) and dropped the over-aggressive `Windows` folder exclude
  (a backup tool shouldn't silently skip a user folder named "Windows"; users can still add it).
- Verify: `./gradlew build` green, 20 tests total.
- Commit: n/a.

### 2026-08-31 — Phase 4: Filesystem traversal & scanning
- Did: `scan/FileTreeWalker` (single-threaded DFS, symlink/permission-safe), `scan/SyncFolderScanner`
  (depth-limited root scan, prunes junk/hidden/pseudo-fs), `scan/SyncPairResolver` (source↔target matching).
- Decisions: deliberately single-threaded (the old parallel walker added complexity and confusing output
  without real speedup on I/O-bound single-device scans); scanner returns `DiscoveredMarker(file, dir)`
  keyed on the folder (cleaner than the marker file path); resolver lives in `scan` so `core` stays
  dependency-free.
- Verify: `./gradlew build` green, 26 tests total.
- Commit: n/a.

### 2026-08-31 — Phase 5: Diff engine
- Did: `diff/DiffEngine` enumerating both trees (files+dirs+attrs) through the walker+filter, then emitting
  CREATE/UPDATE/DELETE; RESTORE reverses origin/destination and suppresses DELETE.
- Decisions: **fixed a reference bug** — the old comparator only flagged UPDATE when the *target* was newer
  than the source (`between(src,tgt) > 1s`); a backup tool must update when the *source* changed, so we use
  the absolute mtime delta. Removed-subtree deletes are collapsed to their root to avoid redundant/duplicate
  delete ops. Unreadable source files are treated as absent (skipped) rather than crashing. Fix: `dirs += rel`
  on a `val HashSet` was ambiguous (plus vs plusAssign) → used explicit `.add`.
- Verify: `./gradlew build` green, 32 tests total.
- Commit: n/a.

### 2026-08-31 — Phase 6: Safe, abort-safe sync engine
- Did: `sync/AtomicFileWriter` (crash-safe temp→backup→rename, stream seam), `sync/FileSynchronizer` (guard
  + listener + recursive delete + result aggregation); added `SyncResult.warnings`.
- Decisions: the abort-safety proof is a real test — a failing InputStream through the production write path
  leaves "PRECIOUS ORIGINAL" intact with zero `.idxtmp`/`.idxbak` litter. Safety guard treats 0-byte AND
  missing/unreadable/non-regular sources as SKIP (protect the backup), surfaced as warnings not errors.
  Refactored the writer to a `write(InputStream, dest, mtime)` internal seam so the abort test exercises real
  code rather than a mock.
- Verify: `./gradlew build` green, 41 tests total.
- Commit: n/a.

### 2026-08-31 — Phase 7: Console UI (Mordant)
- Did: `ui/ConsoleUi` (Mordant 3.0.1 progress API: `progressBarContextLayout` + `animateOnThread`/`execute`/
  `stop`, spinner for indeterminate phases, full-width byte progress bar with speed+timeRemaining),
  `ui/Format`, summary block.
- Decisions: inspected the Mordant jars with `javap` to pin the exact 3.x API (context layout, `terminal.
  size.width`, `advance`/`update`, `stop`) before writing — avoided version guesswork. **Accurate ETA** comes
  from knowing total copy bytes before the bar starts (the whole point vs. the old on-the-fly estimate).
  Dropped the planned `ProgressReporter` interface as YAGNI (Mordant already gives the plain fallback).
- Verify: `./gradlew build` green, 44 tests total.
- Commit: n/a.

### 2026-08-31 — Phase 8: CLI (Clikt) + demo mode
- Did: `cli/SyncApplication` (orchestration), `cli/DemoRunner` (synthetic simulation), `cli/Commands.kt`
  (Clikt 5 command tree + help + duration parsing), `Main.kt` wiring; ran the app end to end.
- Decisions: pinned Clikt 5.0.2 API from the jar (`command.main(args)`, `override fun help(context)`,
  class-name-derived command names). Bare invocation injects `run` so the root can still require a
  subcommand. Verified the whole thing runs — colored on capable terminals, clean plain text under
  `NO_COLOR` (proves the cross-platform requirement). Caught and fixed the `isSource/isTarget` YAML leak.
- Verify: `./gradlew build` green, 47 tests; manual demo + marker commands confirmed.
- Commit: n/a.

### 2026-08-31 — Phase 9: Packaging, docs, end-to-end
- Did: `EndToEndTest` (full scan→resolve→diff→sync pipeline over temp dirs), README, verified
  `clean build shadowJar` + fat-jar run.
- Decisions: the E2E test surfaced a real usability gap — ant `**/node_modules` never matches a root-level
  `node_modules`, so the seeded defaults would silently fail. Improved `ExcludeFilter`: slash-less/`**`-free
  patterns now match a segment at ANY depth (gitignore-like), slashed/`**` patterns keep ant semantics;
  seeded source excludes changed to bare names (`node_modules`,`.git`,`target`,`build`). This keeps the
  ported ant tests passing while making everyday excludes intuitive.
- Verify: `./gradlew clean build shadowJar` green, **52 tests**; `java -jar build/libs/idx-sync.jar` runs.
- Commit: n/a.

### 2026-08-31 — Completion
- All 9 phases done. New project `idx-sync-new/` (Kotlin, Gradle 9.1 + Shadow, JDK 25 toolchain → JVM 21
  bytecode). 52 tests green. Existing `idx-sync/` untouched (reference only).
- Every brief requirement met: idiomatic Kotlin + SOLID packages; excellent cross-platform Mordant UI with
  accurate ETA; crash-safe atomic writes; platform + user excludes; 0-byte/unsafe-source backup guard;
  restore (never deletes); demo mode with duration arg. Bugs fixed vs. reference: source-newer UPDATE
  detection, Windows coloring, redundant subtree deletes, exclude-at-root.
- Suggested next step: review, then commit `idx-sync-new/` (see plan). Optional future work: content-hash
  comparison mode, parallel copy across independent pairs, config-cache for faster builds.
