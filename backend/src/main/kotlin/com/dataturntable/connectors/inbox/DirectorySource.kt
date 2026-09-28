package com.dataturntable.connectors.inbox

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Keeps file connectors inside one directory tree (`dataturntable.connectors.file.base-dir`),
 * so a connector cannot read or write arbitrary files of the server.
 */
class FileSandbox(baseDir: String) {
    val base: Path = Path.of(baseDir).toAbsolutePath().normalize()

    /** [path] relative to the base directory, or absolute inside it; symbolic links may not lead out. */
    fun resolve(path: String): Path {
        val p = Path.of(path.trim())
        val resolved = (if (p.isAbsolute) p else base.resolve(p)).normalize()
        require(resolved.startsWith(base)) { "Path '$path' is outside the file connector directory $base" }
        if (Files.exists(resolved)) {
            require(resolved.toRealPath().startsWith(base.toRealPath())) { "Path '$path' leads outside the file connector directory $base" }
        }
        return resolved
    }
}

/**
 * Directory inbox. Config: `path` (directory below the base directory), `pattern` (glob,
 * default all files), `minAgeSeconds` (files still being written are skipped, default 10),
 * `afterRead` (move | delete | keep, default move) and `processedDir` (default `.processed`).
 */
class DirectorySource(private val sandbox: FileSandbox, config: Map<String, Any?>) : InboxSource {
    private val dir: Path = sandbox.resolve(config.str("path") ?: throw IllegalArgumentException("path is required"))
    private val pattern = config.str("pattern")
    private val minAge = config.int("minAgeSeconds", 10).toLong()
    private val afterRead = AfterRead.parse(config["afterRead"], AfterRead.MOVE)
    private val processedDir: Path = dir.resolve(config.str("processedDir") ?: ".processed").normalize()
        .also { require(it.startsWith(sandbox.base)) { "processedDir is outside the file connector directory" } }

    override fun list(): List<InboxItem> {
        require(Files.isDirectory(dir)) { "Directory $dir does not exist" }
        val cutoff = Instant.now().minusSeconds(minAge)
        return Files.list(dir).use { files ->
            files.filter { it.isRegularFile() && !it.name.startsWith(".") && globMatches(pattern, it.name) }
                .map { f ->
                    val modified = Files.getLastModifiedTime(f).toInstant()
                    val size = Files.size(f)
                    InboxItem(key = "${f.name}|$size|${modified.toEpochMilli()}", name = f.name, size = size, modified = modified, ref = f.toString())
                }
                .filter { it.modified!!.isBefore(cutoff) }
                .toList()
        }.sortedWith(compareBy({ it.modified }, { it.name }))
    }

    override fun read(item: InboxItem): List<InboxContent> =
        listOf(InboxContent(item.name, Files.probeContentType(Path.of(item.ref)), Files.readAllBytes(Path.of(item.ref))))

    override fun acknowledge(item: InboxItem) {
        val file = Path.of(item.ref)
        when (afterRead) {
            AfterRead.KEEP -> {}
            AfterRead.DELETE -> Files.deleteIfExists(file)
            AfterRead.MOVE -> {
                Files.createDirectories(processedDir)
                var target = processedDir.resolve(file.name)
                if (Files.exists(target)) {
                    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(ZoneOffset.UTC).format(Instant.now())
                    target = processedDir.resolve("${file.name}.$stamp")
                }
                if (Files.exists(file)) Files.move(file, target, StandardCopyOption.ATOMIC_MOVE)
            }
        }
    }
}
