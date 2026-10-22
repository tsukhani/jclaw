// Resolves PIT for bin/mutation.mjs without adding it to the build: ./gradlew -I bin/mutation.init.gradle.kts pitestClasspath
allprojects {
    val pitest = configurations.create("pitest")
    dependencies.add(pitest.name, "org.pitest:pitest-command-line:1.30.1")
    // Without transitives: its JUnit Platform would shadow the 6.1.3 the fork ships in framework/lib.
    (dependencies.add(pitest.name, "org.pitest:pitest-junit5-plugin:1.2.3") as ExternalModuleDependency).isTransitive = false
    tasks.register("pitestClasspath") {
        val files: FileCollection = pitest
        doLast { files.forEach { println(it.absolutePath) } }
    }
}
