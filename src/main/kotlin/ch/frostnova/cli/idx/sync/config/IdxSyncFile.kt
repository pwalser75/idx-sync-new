package ch.frostnova.cli.idx.sync.config

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * The `.idxsync` marker file placed in a source and/or target folder. The property names are kept
 * identical to the original Java tool so existing marker files keep working.
 *
 * A **source** folder carries [folderId] (+ optional [folderName], [excludePatterns], [includeHidden],
 * [tags]); a **target** folder carries [sourceFolderId] pointing at the source it mirrors.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class IdxSyncFile(
    @param:JsonProperty("folder-id") @get:JsonProperty("folder-id")
    val folderId: String? = null,

    @param:JsonProperty("folder-name") @get:JsonProperty("folder-name")
    val folderName: String? = null,

    @param:JsonProperty("exclude-patterns") @get:JsonProperty("exclude-patterns")
    val excludePatterns: Set<String> = emptySet(),

    @param:JsonProperty("include-hidden") @get:JsonProperty("include-hidden")
    val includeHidden: Boolean = false,

    @param:JsonProperty("source-folder-id") @get:JsonProperty("source-folder-id")
    val sourceFolderId: String? = null,

    @param:JsonProperty("tags") @get:JsonProperty("tags")
    val tags: Set<String> = emptySet(),
) {
    /** True when this marker designates a target folder (mirrors another folder). */
    @get:JsonIgnore
    val isTarget: Boolean get() = sourceFolderId != null

    /** True when this marker designates a source folder. */
    @get:JsonIgnore
    val isSource: Boolean get() = sourceFolderId == null && folderId != null
}
