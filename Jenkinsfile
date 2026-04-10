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
                sh 'corepack enable && corepack prepare pnpm@10.33.0 --activate'
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
                            sh 'pnpm test'
                        }
                    }
                }
            }
        }

        stage('Package') {
            steps {
                sh 'play dist'

                // Inject the SPA build into the dist zip
                // play dist names the zip after the workspace directory, so find it dynamically
                sh '''
                    cd dist
                    ZIP_FILE=$(ls *.zip | head -1)
                    DIR_NAME="${ZIP_FILE%.zip}"
                    unzip -q "$ZIP_FILE"
                    cp -r ../frontend/.output/public "$DIR_NAME/public/spa"
                    rm "$ZIP_FILE"
                    zip -qr jclaw.zip "$DIR_NAME/"
                    rm -rf "$DIR_NAME/"
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

                        // Docker image to GitHub Container Registry
                        sh """
                            echo \$GH_TOKEN | docker login ghcr.io -u tsukhani --password-stdin
                            docker build -t ghcr.io/tsukhani/jclaw:${version} -t ghcr.io/tsukhani/jclaw:latest .
                            docker push ghcr.io/tsukhani/jclaw:${version}
                            docker push ghcr.io/tsukhani/jclaw:latest
                        """
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
