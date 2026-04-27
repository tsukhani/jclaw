pipeline {
    agent any

    options {
        buildDiscarder(logRotator(
            numToKeepStr: '20',
            artifactNumToKeepStr: '5'
        ))
    }

    parameters {
        booleanParam(name: 'RELEASE', defaultValue: false, description: 'Check to create a GitHub Release from this build')
        booleanParam(name: 'PUBLISH_DEVCONTAINER', defaultValue: false, description: 'Check to build and push .devcontainer/Dockerfile to ghcr.io/tsukhani/jclaw-devcontainer (independent of RELEASE; rerun only when .devcontainer/ changes)')
    }

    tools {
        jdk 'JDK25'
        nodejs 'node-22'
    }

    environment {
        PLAY_HOME = '/opt/play-1.11.x'
        PATH = "${PLAY_HOME}:${env.PATH}"
        PNPM_HOME = "${env.WORKSPACE}/.pnpm-store"
    }

    stages {
        stage('Setup') {
            steps {
                sh 'java -version'
                sh 'play version || echo "Play not found at ${PLAY_HOME}"'
                // Corepack resolves pnpm from frontend/package.json's
                // `packageManager` field on first invocation, so no hardcoded
                // version pins here. Bumping the pin in package.json (the
                // single source of truth) doesn't need a parallel edit to
                // this file. Download happens inside the install step; the
                // per-build cache in PNPM_HOME covers repeat invocations.
                sh 'corepack enable'
                dir('frontend') {
                    sh 'pnpm install --frozen-lockfile'
                }
            }
        }

        stage('Build') {
            parallel {
                stage('Backend') {
                    steps {
                        sh 'play deps --sync'
                        sh 'play precompile'
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
                        // to disk; --classfiles points at the prod-mode
                        // compile from the Build stage, not tmp/classes, so
                        // the report matches what Sonar's binaries path sees.
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
                }
            }
        }

        stage('Sonar Analysis') {
            steps {
                // withSonarQubeEnv('SonarQube') injects SONAR_HOST_URL and
                // SONAR_AUTH_TOKEN from the server configured in Manage
                // Jenkins → System → SonarQube servers (label: SonarQube,
                // pointing at https://sonar.abundent.com). `tool 'Sonar'`
                // resolves to the SonarQube Scanner installation under
                // Manage Jenkins → Tools → SonarQube Scanner installations
                // (label: Sonar, currently auto-installed v3.3.0.1492 from
                // Maven Central).
                //
                // projectVersion is passed dynamically so each analysis run is
                // tagged with the actual application.version being analyzed,
                // keeping Sonar's history aligned with the release stream.
                //
                // Wrapped in catchError so a scanner failure, a missing Sonar
                // server, or a future quality-gate check downstream marks the
                // stage UNSTABLE (yellow) without aborting the build. Sonar
                // is advisory here — code quality feedback, not a merge gate.
                // Same pattern used by the 'Cleanup Old Releases' stage below.
                catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                    script {
                        def appVersion = sh(
                            script: "grep '^application.version=' conf/application.conf | cut -d= -f2",
                            returnStdout: true
                        ).trim()
                        withSonarQubeEnv('SonarQube') {
                            sh "${tool 'Sonar'}/bin/sonar-scanner " +
                               "-Dproject.settings=conf/sonar.properties " +
                               "-Dsonar.projectVersion=v${appVersion}"
                        }
                    }
                }
            }
        }

        stage('Package') {
            steps {
                sh 'play dist'

                // play dist names the zip after the workspace directory;
                // rename it to jclaw.zip for a stable artifact name.
                sh '''
                    cd dist
                    ZIP_FILE=$(ls *.zip | head -1)
                    if [ "$ZIP_FILE" != "jclaw.zip" ]; then
                        mv "$ZIP_FILE" jclaw.zip
                    fi
                '''

                archiveArtifacts artifacts: 'dist/jclaw.zip', fingerprint: true
            }
        }

        stage('Release') {
            when {
                expression { params.RELEASE }
            }
            steps {
                script {
                    def version = 'v' + sh(script: "grep '^application.version=' conf/application.conf | cut -d= -f2", returnStdout: true).trim()
                    sh "echo 'Creating release: ${version}'"
                    sh "git tag ${version} || true"

                    // GitHub Release with dist zip (delete existing release if re-running)
                    withCredentials([string(credentialsId: 'github-token', variable: 'GH_TOKEN')]) {
                        sh """
                            gh release delete ${version} --repo tsukhani/jclaw --yes || true
                            gh release create ${version} dist/jclaw.zip \
                                --repo tsukhani/jclaw \
                                --title "JClaw ${version}" \
                                --generate-notes
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
                        sh """
                            echo \$GH_TOKEN | docker login ghcr.io -u tsukhani --password-stdin

                            # Register QEMU user-mode emulation via binfmt_misc
                            # on the Jenkins host so BuildKit can run arm64
                            # binaries (e.g. the arm64 runtime stage's apt-get)
                            # during the cross-arch build. Docker Engine updates
                            # periodically invalidate these registrations, which
                            # surfaces as
                            #   .buildkit_qemu_emulator: /bin/sh: Invalid ELF image for this architecture
                            # during arm64 RUN steps. tonistiigi/binfmt --install
                            # all is idempotent (~2s when registrations are fresh,
                            # ~5-10s when they need rebuilding), so it's safe to
                            # run every build. Scoping to 'all' covers amd64,
                            # arm64, riscv64, and the less-common platforms; trim
                            # if you want to lock down the allowed target set.
                            docker run --privileged --rm tonistiigi/binfmt --install all

                            docker buildx create --use --name jclaw-builder --driver docker-container 2>/dev/null || docker buildx use jclaw-builder
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

        stage('Publish Dev Container') {
            when {
                expression { params.PUBLISH_DEVCONTAINER }
            }
            steps {
                script {
                    // Tag with the commit short SHA for traceability + :latest
                    // for the floating tag that .devcontainer/devcontainer.json
                    // would reference (when/if the team flips from "build
                    // locally on each dev's machine" to "pull a published image").
                    // Independent of application.version on purpose — the dev
                    // container's lifecycle is decoupled from app releases. New
                    // image = Java/Node/Play/base bumps or Dockerfile structure
                    // changes, NOT every patch release.
                    def shortSha = sh(script: "git rev-parse --short HEAD", returnStdout: true).trim()
                    withCredentials([string(credentialsId: 'github-token', variable: 'GH_TOKEN')]) {
                        // Same multi-arch buildx pattern as the Release stage:
                        // amd64 covers Intel/AMD Linux + Windows hosts; arm64
                        // covers Apple Silicon + Linux ARM. Reuse the
                        // jclaw-builder buildx instance (created in the Release
                        // stage on the same agent), and the same QEMU binfmt
                        // refresh so cross-arch RUN steps have working
                        // emulation. --provenance=false keeps the GHCR package
                        // index clean (no stray unknown/unknown manifests).
                        sh """
                            echo \$GH_TOKEN | docker login ghcr.io -u tsukhani --password-stdin

                            docker run --privileged --rm tonistiigi/binfmt --install all

                            docker buildx create --use --name jclaw-builder --driver docker-container 2>/dev/null || docker buildx use jclaw-builder
                            docker buildx build \\
                                --provenance=false \\
                                --platform linux/amd64,linux/arm64 \\
                                -t ghcr.io/tsukhani/jclaw-devcontainer:${shortSha} \\
                                -t ghcr.io/tsukhani/jclaw-devcontainer:latest \\
                                --push \\
                                .devcontainer/
                        """
                    }
                }
            }
        }

        stage('Cleanup Old Dev Container Versions') {
            when {
                expression { params.PUBLISH_DEVCONTAINER }
            }
            steps {
                catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                    withCredentials([string(credentialsId: 'github-token', variable: 'GH_TOKEN')]) {
                        // Same retention policy as the prod jclaw image:
                        // keep last 5 versions, never touch :latest. Without
                        // pruning, every Publish Dev Container run leaves an
                        // orphaned version in GHCR (the previous short-SHA
                        // tag), and the package version list grows unbounded.
                        sh '''
                            echo "Pruning old GHCR jclaw-devcontainer versions (keeping last 5, preserving :latest)..."
                            gh api --paginate /users/tsukhani/packages/container/jclaw-devcontainer/versions \
                                | jq -r '[.[] | select((.metadata.container.tags | index("latest")) | not)]
                                         | sort_by(.created_at) | reverse
                                         | .[5:] | .[].id' \
                                | while read id; do
                                    [ -z "$id" ] && continue
                                    echo "Deleting GHCR jclaw-devcontainer version id: $id"
                                    gh api -X DELETE "/user/packages/container/jclaw-devcontainer/versions/$id" || true
                                done
                        '''
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
            cleanWs()
        }
    }
}
