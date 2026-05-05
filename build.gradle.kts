plugins {
    id("org.playframework.play1")
}

play1 {
    frameworkPath.set(file("/opt/play1"))
    frameworkVersion.set("1.13.0")
    modules("docviewer")
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
