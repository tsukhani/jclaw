// JClaw MCP Server (JCLAW-282) — standalone executable that exposes
// JClaw's OpenAPI surface as MCP tools to external clients (Claude
// Desktop, Cursor, another JClaw instance via the JCLAW-281 server-
// level handle).
//
// Independent of Play 1.x. Builds to a fat jar invokable over stdio:
//
//     java -jar mcp-server/build/libs/jclaw-mcp-server-all.jar \
//         --base-url=http://localhost:9000 \
//         --token=$JCLAW_API_TOKEN
//
// Runs in the same JDK as JClaw (25+) but has its own narrow
// dependency surface (swagger-parser for OpenAPI ingestion, OkHttp for
// the bearer-auth outbound calls, Gson for JSON-RPC marshaling). No
// classpath coupling to the main app.

plugins {
    id("java")
    id("application")
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        // Match the Play app's JDK pin so a developer who can run the
        // backend can also build the MCP server without juggling JVMs.
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    mainClass.set("jclaw.mcp.server.Main")
    applicationName = "jclaw-mcp-server"
}

dependencies {
    // OpenAPI 3 spec parser — converts JSON/YAML into the
    // io.swagger.v3.oas.models.OpenAPI object tree that ToolCatalog
    // walks. The "parser-v3" subartifact pulls only the v3 codepaths;
    // we don't ingest Swagger 2 specs.
    implementation("io.swagger.parser.v3:swagger-parser:2.1.31")

    // Outbound HTTP to the JClaw backend. Same major version as the
    // Play app uses (5.x) for consistency, but this module's classpath
    // is independent — no shared pool, no shared dispatcher.
    implementation("com.squareup.okhttp3:okhttp-jvm:5.3.2")

    // JSON-RPC wire encoding for the MCP stdio protocol and request-
    // body marshaling for outbound tool calls. Gson matches what the
    // main app uses so a future shared utilities module would slot in
    // cleanly.
    implementation("com.google.code.gson:gson:2.13.2")

    // SLF4J + a minimal binding so library log emission has somewhere
    // to go. Logs go to stderr (the MCP stdio protocol owns stdout)
    // via slf4j-simple's default configuration.
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("org.slf4j:slf4j-simple:2.0.16")

    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // MockWebServer for the bearer-passthrough + HTTP-invocation tests
    // — same major as okhttp, exercises the real OkHttp call path.
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.3.2")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Distributable fat jar so the README's `java -jar …` example works
// without a separate "build a distribution" step. JAR is small (<2 MB)
// and never published — operators build it once and point an MCP
// client at it.
tasks.named<Jar>("jar") {
    manifest {
        attributes["Main-Class"] = "jclaw.mcp.server.Main"
        attributes["Implementation-Title"] = "JClaw MCP Server"
        attributes["Implementation-Version"] = project.version
    }
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        // Strip signed-jar metadata from dependencies — combining
        // multiple signed jars into one fat jar always fails verification.
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}
