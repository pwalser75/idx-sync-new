package ch.frostnova.cli.idx.sync.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/**
 * Reads and writes [IdxSyncFile] markers (`.idxsync`, YAML). Single responsibility: the on-disk
 * representation of the marker file. Malformed markers are reported as `null` from [readOrNull] so a
 * filesystem scan can skip them without aborting.
 */
class IdxSyncFileRepository {

    // Configured to match the original Java tool's mapper so `.idxsync` files are fully interoperable.
    private val mapper = YAMLMapper(
        YAMLFactory.builder()
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .build(),
    ).registerKotlinModule()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(SerializationFeature.WRITE_SINGLE_ELEM_ARRAYS_UNWRAPPED)
        .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        .enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    /** Resolve the marker path within [dir]. */
    fun resolve(dir: Path): Path = dir.resolve(FILENAME)

    /** True if [dir] contains a marker file. */
    fun exists(dir: Path): Boolean = resolve(dir).let { it.exists() && it.isRegularFile() }

    /** Read the marker in [dir], or `null` if absent/unreadable/malformed. */
    fun readOrNull(dir: Path): IdxSyncFile? {
        val path = resolve(dir)
        if (!path.exists() || !path.isRegularFile()) return null
        return runCatching { mapper.readValue<IdxSyncFile>(path.toFile()) }.getOrNull()
    }

    /** Write [file] as the marker in [dir], returning the marker path. */
    fun write(dir: Path, file: IdxSyncFile): Path {
        val path = resolve(dir)
        Files.newBufferedWriter(path).use { mapper.writeValue(it, file) }
        return path
    }

    /** Delete the marker in [dir] if present; returns true if a file was removed. */
    fun remove(dir: Path): Boolean = Files.deleteIfExists(resolve(dir))

    companion object {
        const val FILENAME = ".idxsync"
    }
}
