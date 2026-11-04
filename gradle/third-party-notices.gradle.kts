// Third-party notices (JCLAW-1593): writes THIRD-PARTY-NOTICES.md at the project root with the license of every
// jar the release bundle ships (lib/ and framework/lib/), every JavaScript package the web interface is built
// from, and every skill that came from another author. playBundle and playDist depend on it, so a zip always
// carries a copy that matches what it ships, and it fails the build on any component with no recorded license.
// What a POM, a package or a skill cannot say is recorded by hand in gradle/third-party-licenses.json.

import groovy.json.JsonSlurper
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import org.w3c.dom.Element

data class Gav(val group: String, val artifact: String, val version: String)

data class Pom(val licenses: List<Pair<String?, String?>>, val parent: Gav?, val url: String?)

data class Component(
    val name: String,
    val versions: Set<String>,
    val source: String?,
    val licenses: List<String>,
    val copyrights: List<String>,
    val licenseText: String?,
    val noticeText: String?,
    val note: String?,
    /** Whether the component as shipped already carries [licenseText]: an unmodified jar or a skill folder does, a minified bundle does not. */
    val textShipped: Boolean,
)

val noticesOut = layout.projectDirectory.file("THIRD-PARTY-NOTICES.md").asFile
val overridesIn = layout.projectDirectory.file("gradle/third-party-licenses.json").asFile
val frontendDir = layout.projectDirectory.dir("frontend").asFile
val bundledPackagesFile = frontendDir.resolve("node_modules/.cache/jclaw/bundled-packages.json")
val skillsDir = layout.projectDirectory.dir("skills").asFile
val playHome = file("/opt/play1")

val licenseFileRx = Regex("(?i)^(LICEN[CS]E|COPYING)([._-][^/]*)?$")
val noticeFileRx = Regex("(?i)^NOTICE([._-][^/]*)?$")
// A text or Markdown file (or one with no extension) whose name says it holds a license or notice; source files are
// excluded, since code that handles licenses is named that way too.
val licenseNamedRx = Regex("(?i)(licen[cs]e|notice|copying)[^/]*$|^[^.]*(licen[cs]e|notice|copying)")
val ownLegalFiles = setOf("LICENSE", "TRADEMARK.md", "THIRD-PARTY-NOTICES.md")
val copyrightRx = Regex("(?im)^[ \\t*#/;-]*((?:copyright\\b|\\(c\\)|©).*)$")

/** A license name or URL as declared, mapped to its SPDX id where one is clear; otherwise kept as declared. */
fun spdx(name: String?, url: String?): String? {
    val n = (name ?: "").lowercase().replace(Regex("\\s+"), " ").trim()
    val u = (url ?: "").lowercase()
    fun has(vararg parts: String) = parts.all { n.contains(it) }
    return when {
        n.isEmpty() && u.isEmpty() -> null
        (has("apache") && n.contains("2")) || u.contains("apache.org/licenses/license-2.0") -> "Apache-2.0"
        n == "mit" || has("mit license") || u.contains("opensource.org/licenses/mit") -> "MIT"
        has("bouncy castle") -> "MIT"
        has("classpath") && (has("gpl") || has("general public")) -> "GPL-2.0-with-classpath-exception"
        has("gpl2 w/ cpe") -> "GPL-2.0-with-classpath-exception"
        (has("eclipse public license") && n.contains("2.0")) || n == "epl 2.0" || u.contains("epl-2.0") -> "EPL-2.0"
        (has("eclipse public license") && n.contains("1.0")) || n == "epl 1.0" || u.contains("epl-v10") -> "EPL-1.0"
        has("eclipse distribution license") || u.contains("edl-v10") -> "BSD-3-Clause"
        has("lesser") || n.startsWith("lgpl") -> if (n.contains("3")) "LGPL-3.0" else "LGPL-2.1"
        has("cddl") || has("common development and distribution") -> if (n.contains("1.1")) "CDDL-1.1" else "CDDL-1.0"
        (has("mozilla public license") && n.contains("2.0")) || n == "mpl 2.0" || u.contains("mozilla.org/mpl/2.0") -> "MPL-2.0"
        (has("mozilla public license") && n.contains("1.1")) || n == "mpl 1.1" -> "MPL-1.1"
        has("freebsd") -> "BSD-2-Clause"
        has("bsd") && (n.contains("3") || has("new") || has("revised")) -> "BSD-3-Clause"
        has("bsd") && (n.contains("2") || has("simplified")) -> "BSD-2-Clause"
        // Declared without saying which BSD: kept as one name rather than guessed.
        n == "bsd" || n == "bsd license" || n == "the bsd license" -> "BSD"
        has("universal permissive") -> "UPL-1.0"
        has("public domain") -> "Public Domain"
        n == "cc0" || has("cc0") -> "CC0-1.0"
        else -> name?.trim()?.takeIf { it.isNotEmpty() } ?: url
    }
}

fun child(el: Element, tag: String): Element? {
    var node = el.firstChild
    while (node != null) {
        if (node is Element && (node.localName ?: node.tagName) == tag) return node
        node = node.nextSibling
    }
    return null
}

fun children(el: Element, tag: String): List<Element> {
    val out = mutableListOf<Element>()
    var node = el.firstChild
    while (node != null) {
        if (node is Element && (node.localName ?: node.tagName) == tag) out += node
        node = node.nextSibling
    }
    return out
}

fun textOf(el: Element?, tag: String): String? =
    el?.let { child(it, tag) }?.textContent?.trim()?.takeIf { it.isNotEmpty() && !it.contains("\${") }

fun readPom(file: File): Pom {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }
    val root = factory.newDocumentBuilder().parse(file).documentElement
    val licenses = child(root, "licenses")?.let { children(it, "license") }.orEmpty().map { textOf(it, "name") to textOf(it, "url") }
    val parent = child(root, "parent")?.let { p ->
        val g = textOf(p, "groupId"); val a = textOf(p, "artifactId"); val v = textOf(p, "version")
        if (g != null && a != null && v != null) Gav(g, a, v) else null
    }
    return Pom(licenses, parent, textOf(root, "url") ?: child(root, "scm")?.let { textOf(it, "url") })
}

val pomCache = mutableMapOf<Gav, Pom?>()

fun resolvePoms(ids: Collection<Gav>) {
    val wanted = ids.filter { it !in pomCache }.distinct()
    if (wanted.isEmpty()) return
    val query = dependencies.createArtifactResolutionQuery()
    wanted.forEach { query.forModule(it.group, it.artifact, it.version) }
    val found = mutableMapOf<Gav, Pom>()
    query.withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java).execute().resolvedComponents.forEach { c ->
        val id = c.id as? ModuleComponentIdentifier ?: return@forEach
        val pom = c.getArtifacts(MavenPomArtifact::class.java).filterIsInstance<ResolvedArtifactResult>().firstOrNull() ?: return@forEach
        found[Gav(id.group, id.module, id.version)] = readPom(pom.file)
    }
    wanted.forEach { pomCache[it] = found[it] }
}

/** The licenses a module's POM declares, inherited from its parents when it declares none, and its project URL. */
fun pomLicenses(gav: Gav, depth: Int = 0): Pair<List<String>, String?> {
    resolvePoms(listOf(gav))
    val pom = pomCache[gav] ?: return emptyList<String>() to null
    val own = pom.licenses.mapNotNull { (n, u) -> spdx(n, u) }.distinct()
    if (own.isNotEmpty() || pom.parent == null || depth > 8) return own to pom.url
    val (inherited, parentUrl) = pomLicenses(pom.parent, depth + 1)
    return inherited to (pom.url ?: parentUrl)
}

fun copyrightLines(vararg texts: String?): List<String> =
    texts.filterNotNull().flatMap { t ->
        copyrightRx.findAll(t).map { it.groupValues[1].trim().trimEnd('*', '/', ' ') }.filter { line ->
            val l = line.lowercase()
            (Regex("\\d{4}").containsMatchIn(line) || l.contains("(c)") || line.contains("©")) &&
                listOf("[yyyy]", "<year>", "copyright notice", "copyright owner]", "{yyyy}").none { l.contains(it) }
        }.toList()
    }.distinct().take(12)

fun jarTexts(jar: File, licenseEntry: String? = null): Pair<String?, String?> = ZipFile(jar).use { zip ->
    val names = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
    fun read(name: String?) = name?.let { zip.getInputStream(zip.getEntry(it)).bufferedReader().readText() }
    fun pick(rx: Regex) = names.filter { it.startsWith("META-INF/") && rx.matches(it.substringAfterLast('/')) && it.count { c -> c == '/' } <= 2 }
        .minWithOrNull(compareBy({ it.count { c -> c == '/' } }, { it.length }))
    read(licenseEntry ?: pick(licenseFileRx)) to read(pick(noticeFileRx))
}

fun dirTexts(dir: File): Pair<String?, String?> {
    val files = dir.listFiles().orEmpty().filter { it.isFile }
    fun pick(rx: Regex) = files.filter { rx.matches(it.name) }.minByOrNull { it.name.length }?.readText()
    return pick(licenseFileRx) to pick(noticeFileRx)
}

fun glob(pattern: String) = Regex(pattern.split("*").joinToString(".*") { Regex.escape(it) })

@Suppress("UNCHECKED_CAST")
fun section(map: Map<String, Any?>, key: String) = (map[key] as? Map<String, Any?>).orEmpty()

@Suppress("UNCHECKED_CAST")
fun strings(value: Any?): List<String> = (value as? List<Any?>).orEmpty().mapNotNull { it?.toString() }

fun pnpmLicenses(): Map<String, List<Map<String, Any?>>> {
    val cmd = listOf("pnpm", "licenses", "list", "--json")
    val process = ProcessBuilder(cmd).directory(frontendDir).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val out = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "`${cmd.joinToString(" ")}` failed in frontend/; run pnpm install there first" }
    @Suppress("UNCHECKED_CAST")
    return JsonSlurper().parseText(out) as Map<String, List<Map<String, Any?>>>
}

fun writeNotices(libJars: Set<File>, frameworkJars: Set<File>) {
    @Suppress("UNCHECKED_CAST")
    val overrides = JsonSlurper().parse(overridesIn) as Map<String, Any?>
    val jarOverrides = section(overrides, "jars")
    val moduleOverrides = section(overrides, "modules")
    val jsOverrides = section(overrides, "js")
    val jsAlsoShipped = section(overrides, "jsAlsoShipped")
    val skillOverrides = section(overrides, "skills")
    val fileOverrides = section(overrides, "files")
    val ownAuthors = strings(overrides["ownSkillAuthors"]).toSet()
    val used = mutableSetOf<String>()
    val unrecorded = mutableListOf<String>()

    // ── Java: the jars playBundle ships, named by the module Gradle resolved or the fork's coordinates file ──
    val coordsByPath = configurations.getByName("runtimeClasspath").incoming.artifacts.artifacts.mapNotNull { a ->
        (a.id.componentIdentifier as? ModuleComponentIdentifier)?.let { a.file.canonicalPath to Gav(it.group, it.module, it.version) }
    }.toMap()
    val frameworkCoords = playHome.resolve("framework/lib-coordinates.properties").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") && it.contains('=') }
        .associate { line ->
            val (jar, coords) = line.split('=', limit = 2)
            jar to coords.split(':').takeIf { it.size >= 3 }?.let { Gav(it[0], it[1], it[2]) }
        }
    // playClasspath also carries all of framework/lib, unfiltered; the bundle ships only frameworkLib's share of it.
    val frameworkLibDir = playHome.resolve("framework/lib").canonicalPath + File.separator
    val jars = (libJars.filterNot { it.canonicalPath.startsWith(frameworkLibDir) } + frameworkJars)
        .filter { it.isFile && it.name.endsWith(".jar") }.distinctBy { it.canonicalPath }
    val frameworkPaths = frameworkJars.map { it.canonicalPath }.toSet()
    resolvePoms(jars.mapNotNull { coordsByPath[it.canonicalPath] ?: frameworkCoords[it.name] })

    val java = mutableMapOf<String, Component>()
    fun addJava(c: Component) {
        val prior = java[c.name]
        java[c.name] = if (prior == null) c else prior.copy(
            versions = prior.versions + c.versions,
            copyrights = (prior.copyrights + c.copyrights).distinct(),
            licenseText = prior.licenseText ?: c.licenseText,
            noticeText = prior.noticeText ?: c.noticeText,
            textShipped = prior.textShipped || c.textShipped,
        )
    }
    for (jar in jars) {
        val override = jarOverrides.entries.firstOrNull { glob(it.key).matches(jar.name) }
        if (override != null) {
            used += "jars.${override.key}"
            @Suppress("UNCHECKED_CAST") val o = override.value as Map<String, Any?>
            val (ownLicense, ownNotice) = jarTexts(jar, o["licenseEntry"]?.toString())
            val licenseText = o["licenseFile"]?.let { playHome.resolve(it.toString()).readText() } ?: ownLicense
            val noticeText = o["noticeFile"]?.let { playHome.resolve(it.toString()).readText() } ?: ownNotice
            addJava(Component(
                o["name"].toString(), setOf(jar.nameWithoutExtension.substringAfterLast('-').takeIf { it.any(Char::isDigit) } ?: "-"),
                o["source"]?.toString(), listOf(o["license"].toString()),
                (strings(o["copyright"]) + copyrightLines(licenseText, noticeText)).distinct(), licenseText, noticeText, o["note"]?.toString(),
                textShipped = ownLicense != null && o["licenseFile"] == null,
            ))
            continue
        }
        val gav = coordsByPath[jar.canonicalPath] ?: if (jar.canonicalPath in frameworkPaths) frameworkCoords[jar.name] else null
        if (gav == null) {
            unrecorded += "jar ${jar.name}: no Maven coordinates; record it under \"jars\""
            continue
        }
        var (licenses, url) = pomLicenses(gav)
        val key = "${gav.group}:${gav.artifact}"
        var note: String? = null
        if (licenses.isEmpty()) {
            @Suppress("UNCHECKED_CAST") val o = moduleOverrides[key] as? Map<String, Any?>
            if (o == null) {
                unrecorded += "jar ${jar.name} ($key:${gav.version}): its POM and parents declare no license; record it under \"modules\""
                continue
            }
            used += "modules.$key"
            licenses = listOf(o["license"].toString())
            url = o["source"]?.toString() ?: url
            note = o["note"]?.toString()
        }
        val (licenseText, noticeText) = jarTexts(jar)
        addJava(Component(key, setOf(gav.version), url ?: "https://central.sonatype.com/artifact/${gav.group}/${gav.artifact}",
            licenses, copyrightLines(licenseText, noticeText), licenseText, noticeText, note, textShipped = licenseText != null))
    }

    // ── JavaScript: the packages the client build bundled (frontend/tools/bundled-packages.ts records them) ──
    check(bundledPackagesFile.isFile) {
        "${bundledPackagesFile.relativeTo(projectDir)} is missing: build the web interface first (./gradlew playFrontendSpa)"
    }
    @Suppress("UNCHECKED_CAST") val bundled = JsonSlurper().parse(bundledPackagesFile) as Map<String, Any?>
    val js = mutableMapOf<String, Component>()
    fun addJs(dir: File, note: String?) {
        @Suppress("UNCHECKED_CAST") val pkg = JsonSlurper().parse(dir.resolve("package.json")) as Map<String, Any?>
        val name = pkg["name"].toString()
        val (licenseText, noticeText) = dirTexts(dir)
        @Suppress("UNCHECKED_CAST") val o = jsOverrides[name] as? Map<String, Any?>
        val declared = when (val l = pkg["license"]) {
            is String -> l
            is Map<*, *> -> l["type"]?.toString()
            else -> (pkg["licenses"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("type")?.toString() }?.joinToString(" OR ")
        }
        val license = when {
            o != null -> { used += "js.$name"; o["license"].toString() }
            declared.isNullOrBlank() || declared.equals("unlicensed", true) || declared.startsWith("SEE LICENSE", true) -> {
                unrecorded += "JavaScript package $name (${dir.path}): no license in its package.json; record it under \"js\""; return
            }
            else -> declared
        }
        val repo = (pkg["repository"] as? Map<*, *>)?.get("url")?.toString() ?: pkg["repository"] as? String
        val source = pkg["homepage"]?.toString() ?: repo?.removePrefix("git+")?.removeSuffix(".git") ?: "https://www.npmjs.com/package/$name"
        val prior = js[name]
        val version = pkg["version"].toString()
        js[name] = prior?.copy(versions = prior.versions + version) ?: Component(name, setOf(version), source, listOf(license),
            copyrightLines(licenseText, noticeText), licenseText, noticeText, note, textShipped = false)
    }
    strings(bundled["roots"]).map(::File).forEach { addJs(it, null) }
    val byName = strings(bundled["helpers"]).associateWith { "Injected into the bundle by the build as a helper module." } +
        jsAlsoShipped.mapValues { it.value?.toString() }
    if (byName.isNotEmpty()) {
        val installed = pnpmLicenses().values.flatten().groupBy { it["name"].toString() }
        byName.forEach { (name, note) ->
            val dirs = installed[name].orEmpty().flatMap { strings(it["paths"]) }.map(::File).filter { it.isDirectory }
            if (dirs.isEmpty()) unrecorded += "JavaScript package $name: the build ships it, but pnpm lists no installed copy"
            if (name in jsAlsoShipped.keys) used += "jsAlsoShipped.$name"
            dirs.forEach { addJs(it, note) }
        }
    }

    // ── Skills: every skill whose author is not Abundent's, or that is recorded as another's work ──
    val skills = mutableListOf<Component>()
    for (dir in skillsDir.listFiles().orEmpty().filter { it.resolve("SKILL.md").isFile }.sortedBy { it.name }) {
        val frontmatter = Regex("(?s)^---\\n(.*?)\\n---").find(dir.resolve("SKILL.md").readText())?.groupValues?.get(1).orEmpty()
        val author = Regex("(?m)^\\s*author:\\s*[\"']?(.*?)[\"']?\\s*$").find(frontmatter)?.groupValues?.get(1)
        @Suppress("UNCHECKED_CAST") val o = skillOverrides[dir.name] as? Map<String, Any?>
        if (o == null) {
            if (author != null && author !in ownAuthors) unrecorded += "skill ${dir.name} (author: $author): record its license under \"skills\""
            continue
        }
        used += "skills.${dir.name}"
        val (licenseText, noticeText) = dirTexts(dir)
        skills += Component(dir.name, setOf("-"), o["source"]?.toString(), listOf(o["license"].toString()),
            (strings(o["copyright"]) + copyrightLines(licenseText, noticeText)).distinct(), licenseText, noticeText, o["note"]?.toString(),
            textShipped = licenseText != null)
    }

    // ── Other files: third-party code or documents the repo carries outside a jar, a package or a skill ──
    val files = mutableListOf<Component>()
    fileOverrides.forEach { (path, value) ->
        @Suppress("UNCHECKED_CAST") val o = value as Map<String, Any?>
        val file = projectDir.resolve(path)
        if (!file.isFile) return@forEach
        used += "files.$path"
        val licenseFile = o["licenseFile"]?.let { projectDir.resolve(it.toString()) }
        val (jarLicense, jarNotice) = if (file.name.endsWith(".jar")) jarTexts(file) else (null to null)
        val licenseText = licenseFile?.takeIf { it.isFile }?.readText() ?: jarLicense
        files += Component(o["name"].toString(), setOf("-"), o["source"]?.toString(), listOf(o["license"].toString()),
            (strings(o["copyright"]) + copyrightLines(licenseText, jarNotice)).distinct(), licenseText, jarNotice, o["note"]?.toString(),
            textShipped = licenseText != null)
    }

    // Any other license file the zip would carry means third-party content nothing above records.
    val distignored = projectDir.resolve(".distignore").takeIf { it.isFile }?.readLines().orEmpty()
        .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    val recordedLicenseFiles = fileOverrides.values.mapNotNull { (it as? Map<*, *>)?.get("licenseFile")?.toString() }.toSet()
    val recordedSkillDirs = skills.map { "skills/${it.name}/" }
    val tracked = ProcessBuilder("git", "ls-files", "--cached", "--others", "--exclude-standard").directory(projectDir)
        .redirectError(ProcessBuilder.Redirect.INHERIT).start().let { p -> p.inputStream.bufferedReader().readLines().also { p.waitFor() } }
    tracked.filter { path ->
        val base = path.substringAfterLast('/')
        val textLike = !base.contains('.') || base.substringAfterLast('.').lowercase() in setOf("md", "txt", "rst", "html")
        // The index can list a file deleted on disk; the zip, like this check, takes only files that exist.
        licenseNamedRx.containsMatchIn(base) && textLike && projectDir.resolve(path).isFile && path !in ownLegalFiles && path !in recordedLicenseFiles &&
            distignored.none { path.startsWith(it) } && recordedSkillDirs.none { path.startsWith(it) }
    }.forEach { unrecorded += "file $it: a license file of third-party content; record that content under \"files\"" }

    val stale = (jarOverrides.keys.map { "jars.$it" } + moduleOverrides.keys.map { "modules.$it" } + jsOverrides.keys.map { "js.$it" } +
        jsAlsoShipped.keys.map { "jsAlsoShipped.$it" } + skillOverrides.keys.map { "skills.$it" } + fileOverrides.keys.map { "files.$it" }).filter { it !in used }
    if (unrecorded.isNotEmpty() || stale.isNotEmpty()) {
        throw GradleException(buildString {
            if (unrecorded.isNotEmpty()) appendLine("${unrecorded.size} bundled component(s) have no recorded license:").also { unrecorded.forEach { appendLine("  - $it") } }
            if (stale.isNotEmpty()) appendLine("gradle/third-party-licenses.json records what the bundle no longer ships: ${stale.joinToString()}")
        })
    }
    noticesOut.writeText(render(java.values.sortedBy { it.name.lowercase() }, js.values.sortedBy { it.name.lowercase() }, skills, files))
    logger.lifecycle("Wrote ${noticesOut.name}: ${java.size} Java libraries, ${js.size} JavaScript packages, ${skills.size} skills, ${files.size} other files")
}

fun render(java: List<Component>, js: List<Component>, skills: List<Component>, files: List<Component>): String = buildString {
    fun cell(s: String?) = (s ?: "").replace("|", "\\|").replace("\n", " ")
    fun fenced(text: String) {
        val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(maxOf(3, longest + 1))
        appendLine("$fence text"); appendLine(text.trim('\n').trimEnd()); appendLine(fence); appendLine()
    }
    fun versions(c: Component) = c.versions.sorted().joinToString(", ")
    fun withoutCopyright(text: String) = text.lines().filterNot { copyrightRx.matches(it) && copyrightLines(it).isNotEmpty() }.joinToString("\n")
    fun bodyKey(text: String) = withoutCopyright(text).lowercase().replace(Regex("^\\W*(the )?mit license( \\(mit\\))?"), "")
        .replace(Regex("[^a-z0-9]+"), " ").trim()
    val all = java.map { "Java" to it } + js.map { "Web" to it } + skills.map { "Skills" to it } + files.map { "Other" to it }

    appendLine("# Third-party notices")
    appendLine()
    // Edition-neutral: the same generator writes this file in any repository cut from this one, whose own license differs.
    appendLine("This product's own code is under the license in [LICENSE](LICENSE). Its release bundles also include the")
    appendLine("third-party components listed here, each under its own license.")
    appendLine()
    appendLine("- Each Java library ships as its unmodified jar, which carries its own license file and NOTICE file under")
    appendLine("  `META-INF/`. Their NOTICE files are repeated below, and a license text appears below only for a jar that carries none.")
    appendLine("- The web interface's packages are bundled into minified JavaScript, CSS and fonts that keep no license files, so")
    appendLine("  their copyright lines and license texts appear in full below.")
    appendLine("- Skills from other authors ship as their own folders under `skills/`.")
    appendLine("- Other third-party files ship beside their license file, which is named below.")
    appendLine()
    appendLine("Generated by `./gradlew thirdPartyNotices`, which the release build runs, so the copy in a bundle matches what that")
    appendLine("bundle ships. Do not edit it by hand: record what a component cannot say for itself in `gradle/third-party-licenses.json`.")
    appendLine()
    appendLine("## Summary")
    appendLine()
    appendLine("| License | Java libraries | Web interface packages | Skills | Other files |")
    appendLine("|---|---:|---:|---:|---:|")
    all.flatMap { (s, c) -> c.licenses.map { it to s } }.groupBy({ it.first }, { it.second })
        .entries.sortedWith(compareByDescending<Map.Entry<String, List<String>>> { it.value.size }.thenBy { it.key })
        .forEach { (license, sections) -> appendLine("| ${cell(license)} | ${sections.count { it == "Java" }} | ${sections.count { it == "Web" }} | ${sections.count { it == "Skills" }} | ${sections.count { it == "Other" }} |") }
    appendLine()

    appendLine("## Java libraries")
    appendLine()
    appendLine("| Library | Version | License | License file | Source |")
    appendLine("|---|---|---|---|---|")
    java.forEach { appendLine("| ${cell(it.name)} | ${cell(versions(it))} | ${cell(it.licenses.joinToString(" / "))} | ${if (it.textShipped) "in the jar" else "below"} | ${cell(it.source)} |") }
    appendLine()
    appendLine("## Web interface packages")
    appendLine()
    appendLine("| Package | Version | License | Source |")
    appendLine("|---|---|---|---|")
    js.forEach { appendLine("| ${cell(it.name)} | ${cell(versions(it))} | ${cell(it.licenses.joinToString(" / "))} | ${cell(it.source)} |") }
    appendLine()
    appendLine("## Skills from other authors")
    appendLine()
    appendLine("| Skill | License | Source |")
    appendLine("|---|---|---|")
    skills.forEach { appendLine("| ${cell(it.name)} | ${cell(it.licenses.joinToString(" / "))} | ${cell(it.source)} |") }
    appendLine()
    appendLine("## Other third-party files")
    appendLine()
    appendLine("| Component | License | Source |")
    appendLine("|---|---|---|")
    files.forEach { appendLine("| ${cell(it.name)} | ${cell(it.licenses.joinToString(" / "))} | ${cell(it.source)} |") }
    appendLine()
    val noted = all.map { it.second }.filter { it.note != null }.distinctBy { it.name }
    if (noted.isNotEmpty()) {
        appendLine("## Notes")
        appendLine()
        noted.forEach { appendLine("- **${it.name}:** ${it.note}") }
        appendLine()
    }

    appendLine("## NOTICE files")
    appendLine()
    all.map { it.second }.filter { !it.noticeText.isNullOrBlank() }
        .groupBy { it.noticeText!!.trim().replace(Regex("\\s+"), " ") }.values
        .sortedBy { it.first().name.lowercase() }
        .forEach { group ->
            appendLine("**${group.joinToString(", ") { it.name }}**")
            appendLine()
            fenced(group.first().noticeText!!)
        }

    appendLine("## Licenses")
    appendLine()
    appendLine("Each license text appears once, with its copyright lines removed; the copyright lines of each component it covers")
    appendLine("are listed above it.")
    appendLine()
    fun owner(c: Component) = "- ${c.name}${if (c.copyrights.isEmpty()) "" else ": " + c.copyrights.joinToString("; ")}"
    all.map { it.second }.filter { !it.textShipped }.flatMap { c -> c.licenses.map { it to c } }.groupBy({ it.first }, { it.second })
        .entries.sortedWith(compareByDescending<Map.Entry<String, List<Component>>> { it.value.size }.thenBy { it.key })
        .forEach { (license, components) ->
            appendLine("### $license")
            appendLine()
            val unique = components.distinctBy { it.name }
            val variants = unique.filter { !it.licenseText.isNullOrBlank() }.groupBy { bodyKey(it.licenseText!!) }.values
                .sortedByDescending { it.size }.map { it.toMutableList() }
            val textless = unique.filter { it.licenseText.isNullOrBlank() }
            if (variants.isEmpty()) {
                appendLine("Full text: https://spdx.org/licenses/${license.replace(' ', '-')}.html")
                appendLine()
                textless.forEach { appendLine(owner(it)) }
                appendLine()
            } else {
                variants.first() += textless
                variants.forEach { group ->
                    group.sortedBy { it.name.lowercase() }.forEach { appendLine(owner(it)) }
                    appendLine()
                    fenced(withoutCopyright(group.first { !it.licenseText.isNullOrBlank() }.licenseText!!))
                }
            }
        }
}.trimEnd() + "\n"

val thirdPartyNotices = tasks.register("thirdPartyNotices") {
    group = "build"
    description = "Write THIRD-PARTY-NOTICES.md for what the release bundle ships; fails on any component with no recorded license (JCLAW-1593)."
    notCompatibleWithConfigurationCache("resolves each library's POM through the project's repositories while it runs")
    mustRunAfter("playFrontendSpa")
    val bundle = tasks.getByName("playBundle")
    val libJars = bundle.javaClass.getMethod("getPlayClasspath").invoke(bundle) as FileCollection
    val frameworkJars = bundle.javaClass.getMethod("getFrameworkLib").invoke(bundle) as FileCollection
    inputs.file(overridesIn)
    outputs.file(noticesOut)
    outputs.upToDateWhen { false }
    doLast { writeNotices(libJars.files, frameworkJars.files) }
}

listOf("playBundle", "playDist").forEach { tasks.named(it) { dependsOn(thirdPartyNotices) } }
