package ch.frostnova.cli.idx.sync.scan

import ch.frostnova.cli.idx.sync.core.SyncPair

/**
 * Matches discovered target markers to their source markers by `source-folder-id` and produces the
 * resolvable [SyncPair]s. Only targets whose source is also present are returned; exclude patterns from
 * both sides are merged.
 */
class SyncPairResolver {

    fun resolve(markers: List<DiscoveredMarker>): List<SyncPair> {
        val sourcesById = markers
            .filter { it.file.isSource }
            .associateBy { it.file.folderId }

        return markers
            .filter { it.file.isTarget }
            .mapNotNull { target ->
                val source = sourcesById[target.file.sourceFolderId] ?: return@mapNotNull null
                SyncPair(
                    name = source.file.folderName ?: source.file.folderId ?: source.dir.toString(),
                    source = source.dir,
                    target = target.dir,
                    excludePatterns = source.file.excludePatterns + target.file.excludePatterns,
                    includeHidden = source.file.includeHidden,
                    sourceId = source.file.folderId,
                )
            }
            .sortedBy { it.name.lowercase() }
    }
}
