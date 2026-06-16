plugins {
    id("org.playframework.play1")
    id("org.sonarqube") version "7.3.1.8318"
}

play1 {
    val playRoot = file("/opt/play1")
    frameworkPath.set(playRoot)

    // Pinned framework version range. Bump when migrating to a new minor
    // (e.g. "1.14.x"). The mini-DSL: dots are literal, "x" is one or more
    // digits — so the form is always X.Y.x. Acts as a guard rail: the
    // declared exact version below must fall inside this range, and the
    // installed fork's version must equal the declared one.
    val frameworkVersionRange = "1.13.x"
    val versionPattern = Regex(
        "^" + frameworkVersionRange.replace(".", "\\.").replace("x", "\\d+") + "$"
    )

    // Single source of truth for the play1 release jclaw expects. Lives at
    // the repo root so both Dockerfiles can read it (avoids a stale parse
    // of this Gradle file from inside Docker, which silently broke when
    // build.gradle.kts switched to the dynamic `installed` variable). To
    // bump play1: edit .play-version AND ensure /opt/play1 is on the
    // matching release.
    val declaredFile = rootProject.file(".play-version")
    require(declaredFile.isFile) {
        "Missing $declaredFile — expected a single line with the pinned play1 version (e.g. 1.13.7)."
    }
    val declared = declaredFile.readText().trim()
    require(versionPattern.matches(declared)) {
        "Declared play1 version $declared (in .play-version) is outside the pinned $frameworkVersionRange " +
        "range. Either update the range here, or correct .play-version."
    }

    val versionFile = playRoot.resolve("framework/src/play/version")
    require(versionFile.isFile) {
        "play1 framework not found at $versionFile — is ${playRoot.absolutePath} the right frameworkPath?"
    }
    val installed = versionFile.readText().trim()
    require(installed == declared) {
        "play1 fork at $playRoot is at $installed but jclaw declares $declared (.play-version). " +
        "Bump .play-version to match the fork, or check out v$declared in the fork."
    }
    frameworkVersion.set(declared)
}

sonar {
    properties {
        property("sonar.projectKey", "abundent:jclaw")
        property("sonar.projectName", "JClaw")
        // Overridden by the Jenkinsfile via -Dsonar.projectVersion=v${appVersion}
        // so each analysis run is tagged with the release it was run against.
        property("sonar.projectVersion", "v1")

        // Both the Play backend (app/) and the Nuxt frontend (frontend/) are
        // analyzed; SonarQube routes each file to the appropriate language
        // analyzer (Java, TypeScript, Vue, CSS) automatically.
        property("sonar.sources", "app,frontend")
        property("sonar.tests", "test,frontend/test")

        // Exclude generated artifacts, dependency caches, runtime data, and
        // vendored/third-party content. Groovy templates (app/views/**/*.html)
        // stay out because Play renders them at request time rather than
        // compiling into the precompiled/ tree Sonar analyzes, and Sonar's
        // HTML analyzer misreads Play directives as invalid markup.
        // frontend/test/** lives under sonar.sources=frontend AND
        // sonar.tests=frontend/test, which would double-index each test file
        // — the explicit exclusion carves the test subtree out of the main-
        // source scan so the two walkers produce disjoint sets.
        property(
            "sonar.exclusions",
            "**/node_modules/**, **/dist/**, **/.nuxt/**, **/.output/**, " +
                "**/public/spa/**, **/precompiled/**, **/workspace/**, " +
                "**/skills/**, app/views/**, frontend/test/**, " +
                "**/*.md, **/*.txt, **/*.sh, **/*.xml, **/*.yaml, **/*.yml, " +
                "**/*.properties, **/*.sql",
        )

        // Coverage-only exclusions: these files are still analyzed for
        // code smells, bugs, and security issues, but are not counted
        // toward the coverage metric (numerator OR denominator). The
        // sonar.exclusions list above removes files from analysis entirely;
        // this list narrows just the coverage denominator so JClaw's headline
        // coverage % reflects business code that meaningfully benefits from
        // tests, rather than being deflated by vendored or harness code.
        // See JCLAW-306 for the rationale per category.
        property(
            "sonar.coverage.exclusions",
            // shadcn-vue primitives: upstream-vendored UI wrappers, not
            // JClaw business logic. ~50 files at 0% inflate the gap without
            // representing genuine untested code.
            "frontend/components/ui/**, " +
                // Playwright end-to-end specs: test code, not production source.
                "frontend/tests/e2e/**, " +
                // Dev-mode load harnesses: run manually under controlled
                // conditions, not part of production code paths.
                "app/services/LoadTestRunner.java, app/tools/LoadTestSleepTool.java, " +
                // Generated TypeScript types: emitted by codegen, no
                // hand-written behavior to cover.
                "frontend/types/schemas.ts",
        )

        // Override the Gradle plugin's default (build/classes/java/main) to
        // also include Play's template-derived classes from `play precompile`.
        property("sonar.java.binaries", "build/classes/java/main,precompiled/java")

        property("sonar.coverage.jacoco.xmlReportPaths", "jacoco.xml")
        property("sonar.javascript.lcov.reportPaths", "frontend/coverage/lcov.info")
        property("sonar.junit.reportPaths", "test-result")
        property("sonar.dependencyCheck.htmlReportPath", "dependency-check-report.html")

        // TypeScript strict-mode + Vue parser picks up the frontend tsconfig
        // so type-aware analysis matches what vue-tsc does during pnpm typecheck.
        property("sonar.typescript.tsconfigPath", "frontend/tsconfig.json")

        property("sonar.sourceEncoding", "UTF-8")
        // sonar.java.jdkHome is deliberately omitted so the scanner picks up
        // the JDK resolved by the Jenkins `tools { jdk 'JDK25' }` block rather
        // than a hardcoded path that can drift from the Jenkins tool install.
        property("sonar.java.source", "25")

        // S1220 (default package): every test/*.java file in this codebase
        // lives in the default package per Play 1.x's test-runner convention.
        // Adding a package declaration to a single test creates inconsistency
        // without benefit; doing it across all 90 tests would touch every file
        // with no functional change. Suppressed project-wide for the test/
        // tree only — main-source files (app/) still get flagged correctly.
        //
        // S3252 ("Use static access with GenericModel"): play1's bytecode
        // enhancer injects the calling class at compile time, so every model
        // query is written as `Agent.findById(123)` / `Conversation.count(...)`
        // on the derived class. Calling `GenericModel.findById(...)` directly
        // wouldn't have the class context and wouldn't compile. The rule
        // can't be satisfied without abandoning the framework's idiom; ignore
        // everywhere it could fire (app/ and test/ both invoke model statics).
        //
        // S2925 (Thread.sleep in tests): the rule wants Awaitility-style
        // polling. Most of our sleeps are in tests that deliberately exercise
        // timing-bound behavior (streaming SSE pacing, virtual-thread
        // scheduling, MCP reconnect backoff, conversation queue eviction,
        // per-listener notification timeouts). Swapping to Awaitility would
        // be a 50-site rewrite with no behavioral improvement; the sleeps
        // are the right primitive for "verify A happens before B happens
        // before C". Test-tree scope only.
        //
        // S3457 (\n vs %n): JClaw's String.format calls produce text bound
        // for LLM context windows, HTTP response bodies, and tool output —
        // never OS-native text files. On macOS/Linux %n == \n, but on
        // Windows %n == \r\n, which would inject \r into LLM context (the
        // model is trained on \n), break golden-string assertions in tests,
        // and corrupt JSON payloads downstream. Keeping \n is the right
        // call; scope to app/ so the one real S3457 hit in test/ (an actual
        // argument-count mismatch) still gets caught.
        //
        // S125 (commented-out code): JClaw's coding style uses dense `//`
        // blocks to document non-obvious logic, design tradeoffs, and JCLAW
        // ticket references inline. Sonar's heuristic treats multi-line `//`
        // comments containing code-like tokens (identifiers, parens, dots)
        // as commented-out code; a sample of 18/27 hits confirmed 100%
        // false-positive — every flagged block is documentation. Suppressed
        // project-wide.
        //
        // S108 (empty code blocks): JClaw uses Java 21's unnamed-variable
        // syntax (`catch (IOException _) {}`, `catch (RuntimeException ignored)
        // {}`) to express intentional exception discard at the type-system
        // level. Sample of 14 hits across both app/ and test/ confirmed
        // 100% intentional empty catches for best-effort cleanup (file
        // deletes, stream closes, parse-error fallbacks). Sonar's rule
        // pre-dates the `_` syntax convention; the unnamed variable IS the
        // intent documentation. Suppressed project-wide.
        property("sonar.issue.ignore.multicriteria", "e1,e2,e3,e4,e5,e6")
        property("sonar.issue.ignore.multicriteria.e1.ruleKey", "java:S1220")
        property("sonar.issue.ignore.multicriteria.e1.resourceKey", "test/*.java")
        property("sonar.issue.ignore.multicriteria.e2.ruleKey", "java:S3252")
        property("sonar.issue.ignore.multicriteria.e2.resourceKey", "**/*.java")
        property("sonar.issue.ignore.multicriteria.e3.ruleKey", "java:S2925")
        property("sonar.issue.ignore.multicriteria.e3.resourceKey", "test/*.java")
        property("sonar.issue.ignore.multicriteria.e4.ruleKey", "java:S3457")
        property("sonar.issue.ignore.multicriteria.e4.resourceKey", "app/**/*.java")
        property("sonar.issue.ignore.multicriteria.e5.ruleKey", "java:S125")
        property("sonar.issue.ignore.multicriteria.e5.resourceKey", "**/*.java")
        property("sonar.issue.ignore.multicriteria.e6.ruleKey", "java:S108")
        property("sonar.issue.ignore.multicriteria.e6.resourceKey", "**/*.java")

        // sonar.host.url is deliberately omitted; Jenkins injects it via
        // withSonarQubeEnv('SonarQube'), which reads from Manage Jenkins →
        // System → SonarQube servers. Keeping the URL in one place prevents
        // drift if the Sonar server ever moves to a new domain.
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // jsoup 1.22.2 is one patch ahead of the 1.22.1 that Tika 3.3.0's
    // parent POM pins for its parser modules; keeps resolved/declared in sync.
    implementation("org.jsoup:jsoup:1.22.2")

    // Playwright's POM declares slf4j-simple as a runtime dep, which races
    // log4j-slf4j2-impl for the SLF4JServiceProvider ServiceLoader slot
    // (see JCLAW-88).
    implementation("com.microsoft.playwright:playwright:1.60.0") {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }

    // Tika core — drop OSGi/bndlib (Ivy promoted provided→runtime) and the
    // test/lombok pulls.
    implementation("org.apache.tika:tika-core:3.3.1") {
        exclude(group = "javax.mail")
        exclude(group = "com.sun.mail")
        exclude(group = "javax.activation")
        exclude(group = "com.sun.activation")
        exclude(group = "org.apache.logging.log4j")
        exclude(group = "org.projectlombok")
        exclude(group = "org.assertj")
        exclude(group = "org.mockito")
        exclude(group = "biz.aQute.bnd")
        exclude(group = "org.osgi")
    }

    // Tika parsers — keep pdfbox-tools (PDF OCR depends on ImageIOUtil),
    // exclude picocli (CLI front-end only), exclude mail/lucene/cxf/etc.
    implementation("org.apache.tika:tika-parsers-standard-package:3.3.1") {
        exclude(group = "org.apache.lucene")
        exclude(group = "org.ow2.asm")
        exclude(group = "org.apache.cxf")
        exclude(group = "org.apache.httpcomponents")
        exclude(group = "org.apache.logging.log4j")
        exclude(group = "org.bouncycastle")
        exclude(group = "org.slf4j")
        exclude(group = "com.healthmarketscience.jackcess")
        exclude(group = "edu.ucar")
        exclude(group = "javax.mail")
        exclude(group = "com.sun.mail")
        exclude(group = "javax.activation")
        exclude(group = "com.sun.activation")
        exclude(group = "org.apache.tika", module = "tika-parser-mail-commons")
        exclude(group = "org.apache.tika", module = "tika-parser-mail-module")
        exclude(group = "org.projectlombok")
        exclude(group = "org.assertj")
        exclude(group = "org.mockito")
        exclude(group = "com.codeborne", module = "pdf-test")
        exclude(group = "com.vladsch.flexmark", module = "flexmark-test-util")
        exclude(group = "com.vladsch.flexmark", module = "flexmark-test-specs")
        exclude(group = "com.vladsch.flexmark", module = "flexmark-core-test")
        exclude(group = "info.picocli")
        exclude(group = "org.osgi")
        // JCLAW-452: drop transitive parser backends with non-standard licenses that
        // JClaw never invokes directly — junrar (UnRar License, a use-restriction; the
        // RAR backend of tika-parser-pkg-module) and jhighlight (CDDL; the code-
        // highlight backend of tika-parser-code-module). Tika discovers parsers via
        // ServiceLoader, so the two matching parsers simply de-register and every other
        // format (PDF/Office/HTML/EPUB/zip/7z/tar) is unaffected. The only loss is
        // extracting text from .rar archives + syntax-highlighting embedded source.
        exclude(group = "com.github.junrar", module = "junrar")
        exclude(group = "org.codelibs", module = "jhighlight")
    }

    // flexmark — drop test utilities, jmh, pdf-test, assertj.
    implementation("com.vladsch.flexmark:flexmark:0.64.8") {
        exclude(group = "com.vladsch.flexmark", module = "flexmark-test-util")
        exclude(group = "com.vladsch.flexmark", module = "flexmark-test-specs")
        exclude(group = "com.vladsch.flexmark", module = "flexmark-core-test")
        exclude(group = "com.codeborne", module = "pdf-test")
        exclude(group = "org.assertj")
        exclude(group = "org.mockito")
        exclude(group = "org.projectlombok")
        exclude(group = "org.openjdk.jmh")
    }

    // Each flexmark-ext-* re-pulls flexmark-core-test → replicate the excludes.
    listOf(
        "flexmark-ext-tables",
        "flexmark-ext-gfm-strikethrough",
        "flexmark-ext-gfm-tasklist",
        "flexmark-ext-autolink",
        "flexmark-ext-typographic",
    ).forEach { module ->
        implementation("com.vladsch.flexmark:$module:0.64.8") {
            exclude(group = "com.vladsch.flexmark", module = "flexmark-test-util")
            exclude(group = "com.vladsch.flexmark", module = "flexmark-test-specs")
            exclude(group = "com.vladsch.flexmark", module = "flexmark-core-test")
            exclude(group = "org.openjdk.jmh")
            exclude(group = "org.hamcrest")
            exclude(group = "com.codeborne", module = "pdf-test")
        }
    }

    // flying-saucer-pdf-openpdf — narrow plausible transitive sources.
    implementation("org.xhtmlrenderer:flying-saucer-pdf-openpdf:9.4.0") {
        exclude(group = "com.codeborne", module = "pdf-test")
        exclude(group = "org.assertj")
        exclude(group = "org.hamcrest", module = "hamcrest-core")
        exclude(group = "org.mockito")
        exclude(group = "org.projectlombok")
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }

    implementation("org.hdrhistogram:HdrHistogram:2.2.2")

    // JCLAW-307: OpenAI/tiktoken-compatible tokenizer used for provider-facing
    // prompt measurement and fallback usage accounting when a provider omits
    // usage blocks. The registry is created lazily in TokenUsageEstimator.
    implementation("com.knuddels:jtokkit:1.1.0")

    // JCLAW-205: Caffeine JSR-107 (JCache) provider for Hibernate L2 cache.
    // Co-versioned with the caffeine-3.2.3 the fork ships.
    implementation("com.github.ben-manes.caffeine:jcache:3.2.4") {
        exclude(group = "org.osgi")
        exclude(group = "biz.aQute.bnd")
    }

    // Telegram Bot API SDK — exclude OkHttp 4.x graph (we use okhttp-jvm 5.3.2
    // directly), logback (log4j path), and test artifacts.
    listOf(
        "telegrambots-client",
        "telegrambots-longpolling",
    ).forEach { module ->
        implementation("org.telegram:$module:9.5.0") {
            exclude(group = "ch.qos.logback")
            exclude(group = "org.awaitility")
            exclude(group = "com.squareup.okhttp3", module = "okhttp")
            exclude(group = "com.squareup.okhttp3", module = "mockwebserver")
            exclude(group = "com.squareup.okio")
            exclude(group = "org.projectlombok")
            exclude(group = "org.assertj")
            exclude(group = "org.mockito")
        }
    }

    // OkHttp 5.x JVM artifact (the bare `okhttp` artifact is Gradle-Metadata
    // only and Play 1.x's Ivy didn't understand it; under Gradle Metadata is
    // native, but we keep the jvm-suffixed coord to mirror the python config).
    implementation("com.squareup.okhttp3:okhttp-jvm:5.3.2")

    // JCLAW-185: SSE + MockWebServer 5.x (plain POMs, no -jvm suffix).
    implementation("com.squareup.okhttp3:okhttp-sse:5.3.2")
    implementation("com.squareup.okhttp3:mockwebserver3:5.3.2")

    // JCLAW-83: Slack SDK (official com.slack.api), a la carte — slack-api-client
    // (Web API + Block Kit) + slack-app-backend (SlackSignature.Verifier + event
    // parsers). NO Bolt. Exclude its bare OkHttp 4.x + Okio so it runs on
    // okhttp-jvm 5.3.2 instead of clashing in the shared okhttp3 package
    // (same treatment as the Telegram SDK above).
    listOf(
        "slack-api-client",
        "slack-app-backend",
    ).forEach { module ->
        implementation("com.slack.api:$module:1.49.0") {
            exclude(group = "com.squareup.okhttp3", module = "okhttp")
            exclude(group = "com.squareup.okhttp3", module = "mockwebserver")
            exclude(group = "com.squareup.okio")
        }
    }
    // JCLAW-351: Slack Socket Mode backend. The socket-mode client classes ship inside
    // slack-api-client, but its WebSocket runtime is optional — pull the lightweight
    // Java-WebSocket impl so SocketModeClient.Backend.JavaWebSocket has a transport.
    implementation("org.java-websocket:Java-WebSocket:1.6.0")

    // JCLAW-163: whisper.cpp via JNI for offline transcription. The artifact
    // bundles native libs for every developer-laptop platform (mac arm64/x64,
    // linux x64, win x64) so there's no per-platform install dance.
    implementation("io.github.givimad:whisper-jni:1.7.1")

    // WebP decode for image captioning: caption models vary in format support — local Ollama vision
    // models reject WebP ("Failed to load image"). This TwelveMonkeys ImageIO plugin auto-registers a
    // WebP ImageReader so CaptionImageNormalizer can transcode WebP → PNG before sending. Pure-Java,
    // no natives; read-only (we only decode WebP, then write PNG via core ImageIO).
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.13.1")

    // JCLAW-21: db-scheduler — persistent task scheduling backed by a single
    // DB table (scheduled_tasks). Replaces what would otherwise be a custom
    // TaskPollerJob + CronParser + StuckTaskRecoveryJob trio. db-scheduler
    // handles polling, atomic claim via a row-version column, retry via
    // pluggable FailureHandler, and heartbeat-based dead-execution detection;
    // JClaw contributes the TaskExecutionHandler that knows how to fire each
    // Task. Core artifact has no Spring dependency (the spring-boot starter
    // is a separate optional module we don't pull in).
    implementation("com.github.kagkarlsson:db-scheduler:16.11.0")

    // Lucene 10: full-text index for task_run_message.content (transcript
    // search) via DirectLuceneMessageSearchRepository. We dropped H2's
    // bundled FullTextLucene because it's incompatible with Lucene 10 —
    // TotalHits.value went from public field to private with a getter, and
    // H2 2.3.232's FullTextLucene reads it via direct field access, so the
    // first search query IllegalAccessError's against a Lucene 10 classpath.
    // The direct repo owns its own FSDirectory under data/jclaw-lucene/,
    // kept in sync via JPA lifecycle hooks on TaskRunMessage rather than
    // H2 triggers. tika-parsers-standard-package above already excludes
    // the transitive lucene pull (line 205), so these declarations own
    // the resolved graph.
    implementation("org.apache.lucene:lucene-core:10.4.0")
    implementation("org.apache.lucene:lucene-analysis-common:10.4.0")
    // QueryParser lives in its own artifact since Lucene 9.
    implementation("org.apache.lucene:lucene-queryparser:10.4.0")

    // JCLAW-448: Cobalt — the unofficial WhatsApp-Web (Baileys-equivalent) stack
    // for the WHATSAPP_WEB transport (QR-paired, group-capable, ban-warned).
    // Namespace it.auties.whatsapp.
    //
    // RISK: single-maintainer, mid-rewrite library. The published 0.0.x line is
    // the stable API surface (the master branch is a 0.1.0 rewrite not yet on
    // Maven Central); we PIN an exact version so a surprise republish can't shift
    // the API under us. Treat every Cobalt call as load-bearing-but-fragile —
    // it's isolated behind WhatsAppCobalt* so a breaking bump is contained to
    // the channels/ package. Pulls a large transitive graph (its own protobuf,
    // bouncycastle, etc.); excluded slf4j-simple to keep the log4j path clean
    // (same treatment as the other SDKs above).
    implementation("com.github.auties00:cobalt:0.0.10") {
        // aspose-words 22.11 is a commercial, closed-source artifact NOT on Maven
        // Central (it lives in Aspose's private repo). Cobalt links it (in its
        // Medias helper) only to count document pages + render a first-page
        // thumbnail for outbound document messages. We drop the commercial artifact
        // and instead provide a tiny com.aspose.words shim backed by the PDFBox we
        // already ship — see app/com/aspose/words (JCLAW-451). The shim satisfies
        // Cobalt's static link, so ALL WhatsApp-Web media outbound works with no
        // commercial dependency.
        exclude(group = "com.aspose", module = "aspose-words")
        // slf4j-nop / slf4j-simple both grab the SLF4JServiceProvider
        // ServiceLoader slot that log4j-slf4j2-impl needs (same clash as the
        // Telegram/Slack/Playwright SDKs above) — exclude so our log4j binding wins.
        exclude(group = "org.slf4j", module = "slf4j-nop")
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
}
