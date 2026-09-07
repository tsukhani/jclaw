// Shared prelude for the two GHCR-publishing stages (Release and Publish Dev
// Container), which previously carried byte-identical inline copies. Must be
// called from inside a withCredentials block that binds GH_TOKEN.
//
// A build with both RELEASE and PUBLISH_DEVCONTAINER set runs this twice. That
// is deliberate rather than latched: every step here is idempotent, and the
// repeat costs ~2s (see the binfmt note below) — cheaper than the script-scope
// gymnastics a CPS-safe latch would need.
def dockerPrelude() {
    sh '''
        echo "$GH_TOKEN" | docker login ghcr.io -u tsukhani --password-stdin

        # Register QEMU user-mode emulation via binfmt_misc on the Jenkins
        # host so BuildKit can run arm64 binaries (e.g. the arm64 runtime
        # stage's apt-get) during the cross-arch build. Docker Engine updates
        # periodically invalidate these registrations, which surfaces as
        #   .buildkit_qemu_emulator: /bin/sh: Invalid ELF image for this architecture
        # during arm64 RUN steps. tonistiigi/binfmt --install all is idempotent
        # (~2s when registrations are fresh, ~5-10s when they need rebuilding),
        # so it's safe to run every build. Scoping to 'all' covers amd64,
        # arm64, riscv64, and the less-common platforms; trim if you want to
        # lock down the allowed target set.
        docker run --privileged --rm tonistiigi/binfmt --install all

        docker buildx create --use --name jclaw-builder --driver docker-container 2>/dev/null || docker buildx use jclaw-builder
    '''
}

pipeline {
    agent any

    options {
        buildDiscarder(logRotator(
            numToKeepStr: '20',
            artifactNumToKeepStr: '5'
        ))
        // Ceiling, not a target: the longest legitimate run is a RELEASE build
        // whose arm64 image stage compiles the whole bundle under QEMU
        // emulation. Without this a hung `play autotest` or a wedged BuildKit
        // step pins an executor indefinitely.
        timeout(time: 120, unit: 'MINUTES')
        // This agent has no per-build test-port isolation: PLAY_TEST_PORT is
        // seeded into certs/.env by `./jclaw.sh init-worktree` via the
        // post-checkout hook, and a plain Jenkins SCM checkout runs neither
        // (core.hooksPath is never configured here, and certs/ is gitignored),
        // so concurrent `play autotest` runs would collide on the default
        // port. Overlapping builds would also share the jclaw-builder buildx
        // instance, ~/.gradle, and any live Gradle daemon.
        disableConcurrentBuilds()
        // Requires the Timestamper plugin. Purely diagnostic — drop this line
        // if the plugin isn't installed on the controller.
        timestamps()
    }

    parameters {
        booleanParam(name: 'RELEASE', defaultValue: false, description: 'Check to create a GitHub Release from this build')
        booleanParam(name: 'PUBLISH_DEVCONTAINER', defaultValue: false, description: 'Check to build and push .devcontainer/Dockerfile to ghcr.io/tsukhani/jclaw-devcontainer (independent of RELEASE; rerun only when .devcontainer/ changes)')
    }

    tools {
        jdk 'JDK25'
        // node-26 to match Dockerfile and .devcontainer/Dockerfile (NodeSource
        // setup_26.x), so the SPA in the Release zip and the SPA in the GHCR image
        // come from the same Node major. Requires a node-26 tool in Manage Jenkins
        // → Tools → NodeJS; naming one the controller does not have fails the
        // build at checkout, before any stage runs.
        //
        // Node 25+ needs no pipeline-side workaround: vitest.config.ts sets
        // --no-webstorage itself, gated on the running major (Node 24 rejects the
        // flag), so this line can move back to node-24 without any other edit.
        nodejs 'node-26'
    }

    environment {
        PLAY_HOME = '/opt/play1'
        // PNPM_HOME on PATH: get.pnpm.io's installer appends to a shell rc, and
        // every `sh` step here is a fresh non-login shell that never reads one.
        // Without this the Setup stage installs pnpm and the next step cannot
        // find it. Set to the installer's default rather than overridden, so
        // pnpm's content-addressed store still lands outside the workspace and
        // survives post{cleanup{cleanWs}} — see the store note in Setup.
        //
        // The entry must be $PNPM_HOME/bin, not $PNPM_HOME. pnpm 12 installs the
        // executable one level down and prints that path itself ("export
        // PATH=\"$PNPM_HOME/bin:$PATH\""); pnpm 11 was reachable from the parent,
        // which is what makes the wrong one look plausible. The bare directory is
        // kept after it so an older pnpm on an agent still resolves.
        PNPM_HOME = "${env.HOME}/.local/share/pnpm"
        PATH = "${PLAY_HOME}:${env.HOME}/.local/share/pnpm/bin:${env.HOME}/.local/share/pnpm:${env.PATH}"
        // GRADLE_OPTS: applied to every Gradle launcher invocation in this
        // pipeline. Two things worth carrying:
        //   -Dorg.gradle.vfs.watch=false — suppresses the "Already watching
        //     path: <workspace>" warning that Gradle's VFS subsystem emits on
        //     this agent's workspace layout. CI gets nothing from file
        //     watching since every build runs against a fresh checkout.
        //   -Xmx2g -XX:MaxMetaspaceSize=512m — sizes the launcher (gradlew
        //     client) JVM to EXACTLY match org.gradle.jvmargs in
        //     gradle.properties. Gradle compares the launcher's full JVM-arg
        //     set against that property and forks a single-use daemon on ANY
        //     difference ("To honour the JVM settings for this build a
        //     single-use Daemon process will be forked"). Carrying -Xmx2g
        //     alone still mismatched on the metaspace flag, so the fork
        //     happened anyway; matching both saves a JVM start per Gradle
        //     invocation. It also makes -Dorg.gradle.daemon=false genuinely
        //     run in-process rather than fork, which is what lets the Package
        //     stage's Gradle see pnpm on PATH.
        GRADLE_OPTS = '-Dorg.gradle.vfs.watch=false -Xmx2g -XX:MaxMetaspaceSize=512m'
    }

    stages {
        stage('Setup') {
            steps {
                sh 'java -version'
                sh 'play version || echo "Play not found at ${PLAY_HOME}"'
                // pnpm is installed here rather than resolved through corepack:
                // corepack cannot launch pnpm 12 (per-platform native binary, no
                // bin/pnpm.cjs), and Node 25+ does not ship corepack at all. The
                // installer is version-agnostic — pnpm then reads
                // frontend/package.json's `packageManager` field and switches to
                // the pinned version itself, so bumping the pin still needs no
                // parallel edit here.
                //
                // The integrity gate moved with it. pnpm records its own
                // per-platform releases in frontend/pnpm-lock.yaml and refuses to
                // run one whose bytes do not match a published, signed npm
                // release, so `pnpm install --frozen-lockfile` below both installs
                // dependencies and validates the package manager itself.
                //
                // pnpm 12 is verified green on Node 24 and Node 26 alike, and
                // vitest.config.ts gates its Node 25+ webstorage workaround on the
                // running major, so the agent's Node version is not this stage's
                // concern either way.
                //
                // Deliberately NO PNPM_HOME override. pnpm resolves its
                // content-addressed store to $PNPM_HOME/store whenever that var
                // is set, so the previous ${WORKSPACE}/.pnpm-store value put the
                // store INSIDE the workspace — where post{cleanup{cleanWs}}
                // deleted it after every build, forcing a full re-download of
                // the dependency graph from the registry on the next one.
                // Unset, the store lands at ~/.local/share/pnpm/store and
                // survives across builds. Tradeoff: store and workspace may now
                // sit on different filesystems, in which case pnpm copies
                // instead of hardlinking — far cheaper than re-fetching.
                sh 'curl -fsSL https://get.pnpm.io/install.sh | SHELL=/bin/bash sh -'
                dir('frontend') {
                    sh 'pnpm install --frozen-lockfile'
                }

                // Read application.version once. The Sonar and Release stages
                // both need it and previously ran their own identical grep,
                // which meant two places to keep in sync with the key name.
                script {
                    env.APP_VERSION = sh(
                        script: "grep '^application.version=' conf/application.conf | cut -d= -f2",
                        returnStdout: true
                    ).trim()
                }
                echo "application.version = ${env.APP_VERSION}"
            }
        }

        stage('Build') {
            // A failed backend precompile means nothing downstream can run, so
            // aborting the sibling SPA build immediately frees the agent instead
            // of burning a full Nuxt production build for a result no one will
            // read. Deliberately NOT applied to the Test stage's parallel —
            // there you want both results even when one side is red.
            //
            // failFast is a directive of the stage that CONTAINS the parallel,
            // a sibling of the parallel block. Putting it inside `parallel {}`
            // fails the Declarative parser with "Expected a stage", because
            // only stage entries are legal there.
            failFast true
            parallel {
                stage('Backend') {
                    steps {
                        // PF-90: Gradle handles dependency resolution natively;
                        // no more `play deps --sync` step. `play precompile`
                        // resolves transitively as needed.
                        sh 'play precompile'
                        // Eval dataset gate (JCLAW-875). EvalSuiteConformanceTest
                        // covers the same validation under `play autotest`, but
                        // running it here fails a malformed suite in seconds
                        // instead of after the full suite — and exercises the
                        // operator-facing CLI, which is how command rot is caught.
                        // Free: it reuses the classes precompile just produced.
                        sh './jclaw.sh evals'
                    }
                }
                stage('Frontend') {
                    steps {
                        dir('frontend') {
                            sh 'npx nuxi generate'
                        }
                    }
                }
            }
        }

        stage('Test') {
            parallel {
                stage('Backend') {
                    steps {
                        sh 'play autotest'
                        // Convert the JaCoCo binary exec dump that the test
                        // JVM wrote (via %test.javaagent.path=bin/jacocoagent.jar
                        // in conf/application.conf) into the XML format Sonar
                        // expects at sonar.coverage.jacoco.xmlReportPaths.
                        // Runs after play autotest so the exec file is flushed
                        // to disk; --classfiles points at precompiled/java from
                        // the Build stage, not tmp/classes, so the report
                        // matches what Sonar's binaries path sees.
                        //
                        // That works because playPrecompile runs with
                        // playId="test" (Play1Plugin.kt:160) — so precompiled/
                        // and the tmp/classes the test JVM actually loaded are
                        // both Play test-mode-enhanced and carry matching
                        // class IDs. JaCoCo matches execution data to classes
                        // by a bytecode CRC and SILENTLY DROPS classes whose
                        // IDs don't match, producing a report that renders fine
                        // but understates coverage. If that invariant ever
                        // breaks, this step's output says so — grep the build
                        // log for "does not match".
                        //
                        // Pointing --classfiles at build/classes/java/main
                        // instead would be wrong: that is unenhanced javac
                        // output, so nothing would match at all.
                        sh 'java -jar bin/jacococli.jar report jacoco.exec --classfiles precompiled/java --sourcefiles app --xml jacoco.xml'
                    }
                    post {
                        always {
                            junit testResults: 'test-result/*.xml', allowEmptyResults: true
                        }
                    }
                }
                stage('Frontend') {
                    steps {
                        dir('frontend') {
                            // --coverage activates vitest.config.ts's
                            // coverage.provider=v8 + reporter=[text, lcov, html]
                            // so frontend/coverage/lcov.info exists by the
                            // time the Sonar stage runs and can be picked up
                            // via sonar.javascript.lcov.reportPaths. No `--`
                            // separator — pnpm interprets that as end-of-flags
                            // and vitest then sees `--coverage` as a test-file
                            // pattern, not a coverage flag.
                            sh 'pnpm test --coverage'
                        }
                    }
                    post {
                        always {
                            // Separate from the backend's test-result/ so the two
                            // globs stay disjoint and Sonar's junit path keeps
                            // resolving to the backend results only.
                            junit testResults: 'frontend/test-report/junit.xml',
                                  allowEmptyResults: true
                        }
                    }
                }
            }
        }

        stage('Sonar Analysis') {
            steps {
                // withSonarQubeEnv('SonarQube') injects SONAR_HOST_URL and
                // SONAR_TOKEN (also SONAR_AUTH_TOKEN on older plugin versions)
                // from the server configured in Manage Jenkins → System →
                // SonarQube servers (label: SonarQube, pointing at
                // https://sonar.abundent.com). The org.sonarqube Gradle plugin
                // (declared in build.gradle.kts) reads those env vars
                // automatically — no `tool 'Sonar'` reference needed because
                // the plugin downloads the scanner engine via Maven on first
                // use and caches it under ~/.gradle/caches/.
                //
                // projectVersion is passed dynamically so each analysis run is
                // tagged with the actual application.version being analyzed,
                // keeping Sonar's history aligned with the release stream.
                //
                // Scanner submission is wrapped in catchError so a scanner
                // crash, a Sonar-server outage, or a transient network blip
                // marks the stage UNSTABLE (yellow) without aborting the
                // build — we don't want infrastructure flakes to block
                // releases. Same pattern used by the 'Cleanup Old Releases'
                // stage below.
                //
                // The quality-gate check (waitForQualityGate) stays OUTSIDE
                // that catchError on purpose (JCLAW-306): once analysis has
                // landed in Sonar, a Failed gate is a real signal about new
                // code quality and SHOULD abort the build. abortPipeline: true
                // raises a flow-control error() that bypasses any surrounding
                // catchError, so the gate is binding even if we ever wrap it.
                // Timeout caps the polling wait at 5 minutes so a server hang
                // doesn't pin the executor indefinitely.
                //
                // But the gate is only reachable when analysis actually ran.
                // waitForQualityGate reads the analysis task id that the
                // scanner writes into the build context; if the scanner failed,
                // it throws "There is no SonarQube analysis in the current
                // build context" and aborts the build — defeating the whole
                // point of the catchError above. The `analysed` latch keeps the
                // gate binding when it can be evaluated and skips it (leaving
                // the stage UNSTABLE) when it can't.
                script {
                    def analysed = false
                    catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                        withSonarQubeEnv('SonarQube') {
                            sh "./gradlew sonar -Dsonar.projectVersion=v${env.APP_VERSION}"
                        }
                        analysed = true
                    }
                    if (analysed) {
                        timeout(time: 5, unit: 'MINUTES') {
                            waitForQualityGate abortPipeline: true
                        }
                    } else {
                        echo 'Sonar analysis did not complete — skipping the quality gate. ' +
                             'Stage is UNSTABLE; new-code quality was NOT verified for this build.'
                    }
                }
            }
        }

        stage('Package') {
            steps {
                // ONE Gradle invocation, both packaging tasks. This is load-
                // bearing, not style: playDist and playBundle each declare
                // `outputs.upToDateWhen { false }` and depend on playPrecompile,
                // which in turn depends on a Delete task that wipes tmp/ +
                // precompiled/ first (Play1Plugin.kt:151-163). There is no
                // incremental reuse to lean on — the previous shape
                // (`./jclaw.sh dist` then `./gradlew playBundle`, two separate
                // processes) therefore ran a full clean precompile TWICE.
                // Gradle executes each task at most once per invocation, so
                // naming both tasks in one command collapses that to one.
                //
                // The SPA is still generated twice: the plugin calls
                // buildFrontendAndCopySpa from inside each task's ACTION rather
                // than as a shared task, so the task-once rule doesn't reach it.
                // Deduplicating that is an upstream /opt/play1 change (hoist it
                // into its own task with declared inputs/outputs).
                //
                // -Dorg.gradle.daemon=false for exactly the reason ./jclaw.sh
                // dist used it (see do_dist): both tasks probe `pnpm --version`
                // via ExecOperations.exec, which inherits the JVM's
                // frozen-at-startup PATH. A daemon started before pnpm was
                // installed — or by an earlier job — wouldn't have pnpm on PATH
                // and the probe fails. The bare `./gradlew playBundle`
                // this replaces used the daemon and carried that latent flake.
                // Because GRADLE_OPTS now matches org.gradle.jvmargs exactly,
                // this runs in-process and inherits the calling shell's PATH.
                //
                // dist/jclaw.zip        source tree filtered by .gitignore +
                //                       .distignore, plus precompiled/ and
                //                       public/spa/ — but NOT the framework jar,
                //                       framework lib, Gradle-resolved app deps,
                //                       or a runtime launcher. Operators
                //                       unzipping it need a local Java 25 +
                //                       Gradle + Play 1 fork install to assemble
                //                       the runtime classpath.
                // dist/jclaw-bundle.zip the self-contained variant (framework,
                //                       resolved deps, and a `./play` launcher
                //                       baked in, JRE-only at runtime) — the
                //                       same artifact the Dockerfile bakes into
                //                       the GHCR image.
                // Both carry the jclaw/ inner prefix (project.name from
                // settings.gradle.kts).
                sh 'GRADLE_OPTS="$GRADLE_OPTS -Dorg.gradle.daemon=false" ./gradlew playDist playBundle'

                // Preserves the existence check ./jclaw.sh dist used to perform.
                // Neither PlayDistTask nor PlayBundleTask fails if its zip
                // somehow isn't written, and a silently-empty archiveArtifacts
                // is worse than a red build.
                sh 'test -f dist/jclaw.zip && test -f dist/jclaw-bundle.zip'

                // Both zips ride the same archiveArtifacts call, so the bundle
                // falls under the job's artifact retention
                // (artifactNumToKeepStr: '5' in options.buildDiscarder) exactly
                // like the dist — latest builds only, unversioned filenames.
                archiveArtifacts artifacts: 'dist/jclaw.zip, dist/jclaw-bundle.zip', fingerprint: true
            }
        }

        stage('Release') {
            when {
                expression { params.RELEASE }
            }
            steps {
                script {
                    def version = "v${env.APP_VERSION}"
                    echo "Creating release: ${version}"

                    // No local `git tag` here. The one this replaces was never
                    // pushed, and post{cleanup{cleanWs}} deleted the workspace
                    // that held it — so it read as if Jenkins owned tagging
                    // while doing nothing. `gh release create` below creates
                    // the tag server-side from the repo's default branch.

                    // GitHub Release with both the source dist and the runnable
                    // bundle attached (delete existing release if re-running).
                    // Both assets live on the same release, so the 'Cleanup Old
                    // Releases' stage (keep last 5) prunes them together — the
                    // bundle gets the same release retention as the dist.
                    //
                    // Release notes come from the release commit body: /deploy
                    // writes a human-authored summary paragraph into every
                    // "Release vX.Y.Z" commit, so we surface that on the
                    // release page. --generate-notes is only the fallback —
                    // it builds notes from merged PRs, and this repo pushes
                    // straight to main with no PRs, so on its own it produces
                    // little more than a "Full Changelog" compare link. The
                    // subject-line guard keeps us from pasting an unrelated
                    // commit's body if a RELEASE build is ever run on a
                    // non-release HEAD.
                    withCredentials([string(credentialsId: 'github-token', variable: 'GH_TOKEN')]) {
                        sh """
                            gh release delete ${version} --repo tsukhani/jclaw --yes || true

                            : > release-notes.md
                            if [ "\$(git log -1 --format=%s HEAD)" = "Release ${version}" ]; then
                                git log -1 --format=%b HEAD | sed '/^Co-Authored-By:/d' > release-notes.md
                            fi
                            if grep -q '[^[:space:]]' release-notes.md; then
                                NOTES_ARG='--notes-file release-notes.md'
                            else
                                NOTES_ARG='--generate-notes'
                            fi

                            # SHA256SUMS rides on the release so `jclaw.sh upgrade`
                            # can verify what it downloaded before installing it
                            # over a working instance. Bare filenames (no dist/
                            # prefix) because the verifier matches on the asset
                            # name as published.
                            ( cd dist && shasum -a 256 jclaw.zip jclaw-bundle.zip > SHA256SUMS )

                            gh release create ${version} dist/jclaw.zip dist/jclaw-bundle.zip dist/SHA256SUMS \
                                --repo tsukhani/jclaw \
                                --title "JClaw ${version}" \
                                \$NOTES_ARG
                        """

                        // Multi-arch Docker image to GitHub Container Registry.
                        // buildx --push produces both linux/amd64 and
                        // linux/arm64 manifests under a single tag, so
                        // Apple Silicon users can `docker compose up -d`
                        // the native variant without Rosetta translation.
                        // Single-arch `docker build` was the prior shape;
                        // kept in git history for reference if buildx ever
                        // becomes unavailable on this agent.
                        //
                        // --provenance=false disables BuildKit's default
                        // SLSA provenance attestation. Without this flag,
                        // the published image index carries an extra
                        // `unknown/unknown` manifest per platform (the
                        // attestation blob), which GHCR's package UI then
                        // renders as a third "platform" in the docker-pull
                        // dropdown — confusing to anyone copy-pasting the
                        // wrong command. We don't currently consume the
                        // attestation anywhere, so dropping it keeps the
                        // manifest index clean. Flip back to `=true` if
                        // supply-chain requirements emerge.
                        dockerPrelude()
                        // Backstop for the Dockerfile's per-fetch retries; re-pushing the same tags is idempotent.
                        retry(2) {
                            sh """
                                docker buildx build \\
                                    --provenance=false \\
                                    --platform linux/amd64,linux/arm64 \\
                                    -t ghcr.io/tsukhani/jclaw:${version} \\
                                    -t ghcr.io/tsukhani/jclaw:latest \\
                                    --push .
                            """
                        }
                    }
                }
            }
        }

        stage('Publish Dev Container') {
            when {
                expression { params.PUBLISH_DEVCONTAINER }
            }
            steps {
                // script{} wrapper is required, not cosmetic: Declarative
                // validates every entry in a steps block (including inside a
                // block-scoped step like withCredentials) against the known
                // step list, and dockerPrelude() is a script-level method, not
                // a step. The Release stage already sits inside a script{}.
                script {
                    withCredentials([string(credentialsId: 'github-token', variable: 'GH_TOKEN')]) {
                        // Same multi-arch buildx pattern as the Release stage:
                        // amd64 covers Intel/AMD Linux + Windows hosts; arm64
                        // covers Apple Silicon + Linux ARM. Shares the
                        // jclaw-builder buildx instance and the QEMU binfmt
                        // refresh via dockerPrelude() so cross-arch RUN steps
                        // have working emulation. --provenance=false keeps the
                        // GHCR package index clean (no stray unknown/unknown
                        // manifests).
                        //
                        // Single :latest tag only — the Dev Container Dockerfile
                        // changes infrequently (Java/Node/Play/base bumps every
                        // few months at most for a project this size), and any
                        // historical state is recoverable from git via
                        // `git checkout <sha> && docker buildx build .devcontainer/`.
                        // GHCR's untagged-orphan accumulation rate at this
                        // publish cadence is negligible; if it ever becomes a
                        // storage concern, add a cleanup stage modeled on
                        // `Cleanup Old Releases` below.
                        dockerPrelude()
                        retry(2) {
                            sh '''
                                docker buildx build \\
                                    --provenance=false \\
                                    --platform linux/amd64,linux/arm64 \\
                                    -t ghcr.io/tsukhani/jclaw-devcontainer:latest \\
                                    --push \\
                                    .devcontainer/
                            '''
                        }
                    }
                }
            }
        }

        stage('Cleanup Old Releases') {
            when {
                expression { params.RELEASE }
            }
            steps {
                catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                    withCredentials([string(credentialsId: 'github-token', variable: 'GH_TOKEN')]) {
                        // Keep only the 5 most recent GitHub Releases.
                        sh '''
                            echo "Pruning old GitHub Releases (keeping last 5)..."
                            gh release list --repo tsukhani/jclaw --limit 100 \
                                --json tagName,createdAt \
                                | jq -r 'sort_by(.createdAt) | reverse | .[5:] | .[].tagName' \
                                | while read tag; do
                                    [ -z "$tag" ] && continue
                                    echo "Deleting release: $tag"
                                    gh release delete "$tag" --repo tsukhani/jclaw --yes || true
                                done
                        '''

                        // Keep only the 5 most recent GHCR package versions, never touching
                        // whichever version the :latest tag currently points at.
                        sh '''
                            echo "Pruning old GHCR package versions (keeping last 5, preserving :latest)..."
                            gh api --paginate /users/tsukhani/packages/container/jclaw/versions \
                                | jq -r '[.[] | select((.metadata.container.tags | index("latest")) | not)]
                                         | sort_by(.created_at) | reverse
                                         | .[5:] | .[].id' \
                                | while read id; do
                                    [ -z "$id" ] && continue
                                    echo "Deleting GHCR version id: $id"
                                    gh api -X DELETE "/user/packages/container/jclaw/versions/$id" || true
                                done
                        '''

                        // Prune BuildKit cache older than 30 days. The Release
                        // stage uses `docker buildx` with the docker-container
                        // driver, which stores per-platform layer cache inside
                        // the `jclaw-builder` container's volume. That cache is
                        // invisible to `docker system prune` unless we scope to
                        // the named builder. Without this, the Threadripper
                        // agent's BuildKit cache grows unbounded (up to
                        // BuildKit's 10%-of-disk default) until LRU eviction
                        // kicks in. 720h = 30 days keeps recent entries warm
                        // for incremental builds while reaping fossils; adjust
                        // `until=` down if disk pressure surfaces. The `|| true`
                        // swallows transient prune failures so they don't mark
                        // an otherwise-green release UNSTABLE.
                        sh '''
                            echo "Pruning BuildKit cache older than 30 days..."
                            docker buildx prune --builder jclaw-builder \
                                --filter 'until=720h' \
                                --force || true
                        '''
                    }
                }
            }
        }
    }

    post {
        cleanup {
            // Wipe the workspace EXCEPT .gradle/ and .git/. gradle.properties
            // sets org.gradle.configuration-cache=true, and Gradle stores those
            // entries in <project>/.gradle/configuration-cache — so a blanket
            // cleanWs() guaranteed a cold configuration on every Gradle
            // invocation (this pipeline runs two or three per build, against a
            // Kotlin-DSL script that validates the play1 fork and declares a
            // large dependency graph). Excluding it lets the cache actually hit.
            //
            // .git is excluded for a different reason: cleanWs defaults to
            // deleteDirs:false, so emptying it left the directory standing and
            // every build opened with "Workspace has a .git repository, but it
            // appears to be corrupt" followed by a full re-clone. Keeping the
            // repo makes that an incremental fetch instead; the working tree is
            // still wiped here and force-checked-out to the build's own sha.
            //
            // An empty include set means "everything", so the EXCLUDE entries
            // below are the whole filter; each pair keeps a directory itself
            // and its contents.
            cleanWs(patterns: [
                [pattern: '.gradle/**', type: 'EXCLUDE'],
                [pattern: '.gradle', type: 'EXCLUDE'],
                [pattern: '.git/**', type: 'EXCLUDE'],
                [pattern: '.git', type: 'EXCLUDE'],
            ])
        }
    }
}
