// release-files.tsv: every file a release zip ships, path<TAB>sha256, written into the zip after playBundle and
// playDist build it. The next upgrade reads the installed copy to tell a file this release shipped, which a newer
// one may drop and the upgrade then leaves behind, from one the operator added (JF-522; jclaw.sh merge_absent).

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

// jclaw.sh's UPGRADE_NO_MERGE: an upgrade replaces these wholesale and never carries a file out of them.
val neverCarried = listOf("precompiled", "lib", "framework", "modules", ".classpath", "public/spa", "server.pid")
val listName = "release-files.tsv"

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

// Rewritten through ZipFileSystem with POSIX attributes on, as the packager writes it, so jclaw.sh keeps its
// executable bit.
fun writeReleaseFiles(zip: File, appName: String) {
    FileSystems.newFileSystem(zip.toPath(), mapOf("enablePosixFileAttributes" to "true")).use { fs ->
        val root = fs.getPath("/$appName")
        val rows = Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .map { root.relativize(it).toString() }
                .filter { rel -> rel != listName && neverCarried.none { rel == it || rel.startsWith("$it/") } }
                .sorted()
                .toList()
        }.map { rel -> "$rel\t${sha256(Files.readAllBytes(root.resolve(rel)))}" }
        val list = root.resolve(listName)
        Files.writeString(list, rows.joinToString("\n", postfix = "\n"))
        Files.setPosixFilePermissions(list, PosixFilePermissions.fromString("rw-r--r--"))
    }
}

val appName = rootProject.name
listOf("playBundle", "playDist").forEach { name ->
    tasks.named(name) {
        // A script plugin cannot see the fork's task classes, so the zip comes through the getter, as in
        // third-party-notices.gradle.kts.
        val zip = javaClass.getMethod("getOutputFile").invoke(this) as RegularFileProperty
        notCompatibleWithConfigurationCache("writes the release file list into the zip it just built")
        doLast { writeReleaseFiles(zip.get().asFile, appName) }
    }
}
