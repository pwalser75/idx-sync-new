package ch.frostnova.cli.idx.sync.core

import java.nio.file.Path

/** Direction of a synchronization. */
enum class SyncMode {
    /** Forward: mirror source → target (creates, updates and deletes on the target). */
    SYNC,

    /** Reverse: restore target → source (only creates/updates missing or older files, never deletes). */
    RESTORE,
}

/** The operation a single [FileChange] represents. */
enum class SyncAction {
    /** File exists at the origin but not the destination. */
    CREATE,

    /** File exists on both sides but differs (size or last-modified). */
    UPDATE,

    /** File exists at the destination but not the origin (mirror deletion; never emitted in RESTORE). */
    DELETE,

    /** File was intentionally not touched (e.g. unsafe 0-byte / unreadable source). */
    SKIP,
}

/**
 * One planned file operation between a source/target pair, expressed in origin→destination terms for the
 * active [SyncMode]. For [SyncMode.SYNC] origin=source and destination=target; for [SyncMode.RESTORE] the
 * roles are swapped upstream so this type always reads "copy [origin] to [destination]".
 */
data class FileChange(
    val relativePath: Path,
    val origin: Path,
    val destination: Path,
    val action: SyncAction,
    val size: Long = 0,
    /** Human-readable explanation, used for SKIP reasons and diagnostics. */
    val reason: String? = null,
)

/**
 * A resolved source→target folder pair to synchronize. Exclude patterns and the hidden-file policy are
 * carried here so the diff layer can build the effective filter without reaching back into config.
 */
data class SyncPair(
    val name: String,
    val source: Path,
    val target: Path,
    val excludePatterns: Set<String> = emptySet(),
    val includeHidden: Boolean = false,
    /** The source folder's `folder-id` (used to target a single pair from the CLI). */
    val sourceId: String? = null,
) {
    /**
     * True when source and target overlap — identical, or one is an ancestor of the other. Such a pair is
     * unsafe to synchronize (a folder would be copied into itself), so it is reported and skipped.
     */
    val overlapping: Boolean
        get() {
            val s = source.toAbsolutePath().normalize()
            val t = target.toAbsolutePath().normalize()
            return s == t || s.startsWith(t) || t.startsWith(s)
        }
}

/** Aggregated outcome of applying a set of [FileChange]s. Combine partial results with [plus]. */
data class SyncResult(
    val created: Int = 0,
    val updated: Int = 0,
    val deleted: Int = 0,
    val skipped: Int = 0,
    val bytesTransferred: Long = 0,
    /** Non-fatal notices (e.g. a 0-byte source that was skipped to protect the target). */
    val warnings: List<String> = emptyList(),
    /** Failures that occurred while applying a change. */
    val errors: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = created == 0 && updated == 0 && deleted == 0 && skipped == 0 &&
            warnings.isEmpty() && errors.isEmpty()

    operator fun plus(other: SyncResult): SyncResult = SyncResult(
        created = created + other.created,
        updated = updated + other.updated,
        deleted = deleted + other.deleted,
        skipped = skipped + other.skipped,
        bytesTransferred = bytesTransferred + other.bytesTransferred,
        warnings = warnings + other.warnings,
        errors = errors + other.errors,
    )

    companion object {
        val EMPTY = SyncResult()
    }
}
