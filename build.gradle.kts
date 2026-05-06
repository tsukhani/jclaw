plugins {
    id("org.playframework.play1")
    id("org.sonarqube") version "7.3.0.8198"
}

play1 {
    val playRoot = file("/opt/play1")
    frameworkPath.set(playRoot)

    // Pinned framework version range. Bump when migrating to a new minor
    // (e.g. "1.14.x"). The mini-DSL: dots are literal, "x" is one or more
    // digits — so the form is always X.Y.x. The regex below is derived
    // mechanically; for a different shape (e.g. any 1.x), replace this
    // pair with an explicit Regex instead.
    //
    // The plugin uses frameworkVersion as a path-template input (e.g.
    // framework/play-$it.jar), so the value handed to .set() must be a
    // single concrete version string. We read it from the canonical
    // version file the play1 fork ships at framework/src/play/version
    // (the same one `play version` returns), then validate it against
    // this range. Patch bumps (1.13.2, 1.13.3, ...) flow through
    // automatically; major/minor bumps fail at configure time with a
    // clear message rather than producing mysterious "play-X.Y.Z.jar
    // not found" errors deeper in the build.
    val frameworkVersionRange = "1.13.x"
    val versionPattern = Regex(
        "^" + frameworkVersionRange.replace(".", "\\.").replace("x", "\\d+") + "$"
    )

    val versionFile = playRoot.resolve("framework/src/play/version")
    require(versionFile.isFile) {
        "play1 framework not found at $versionFile — is ${playRoot.absolutePath} the right frameworkPath?"
    }
    val installed = versionFile.readText().trim()
    require(versionPattern.matches(installed)) {
        "play1 framework version $installed is outside the pinned $frameworkVersionRange range. " +
        "Either update the pin in build.gradle.kts to a range that matches, or check out a " +
        "$frameworkVersionRange release in $playRoot."
    }
    frameworkVersion.set(installed)

    modules("docviewer")
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
        property("sonar.issue.ignore.multicriteria", "e1")
        property("sonar.issue.ignore.multicriteria.e1.ruleKey", "java:S1220")
        property("sonar.issue.ignore.multicriteria.e1.resourceKey", "test/*.java")

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
    implementation("com.microsoft.playwright:playwright:1.59.0") {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }

    // Tika core — drop OSGi/bndlib (Ivy promoted provided→runtime) and the
    // test/lombok pulls.
    implementation("org.apache.tika:tika-core:3.3.0") {
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
    implementation("org.apache.tika:tika-parsers-standard-package:3.3.0") {
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

    // JCLAW-205: Caffeine JSR-107 (JCache) provider for Hibernate L2 cache.
    // Co-versioned with the caffeine-3.2.3 the fork ships.
    implementation("com.github.ben-manes.caffeine:jcache:3.2.3") {
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
}
