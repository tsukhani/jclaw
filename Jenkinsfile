pipeline {
    agent any

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
            steps {
                sh 'play autotest'
            }
            post {
                always {
                    junit testResults: 'test-result/*.xml', allowEmptyResults: true
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
                    withCredentials([string(credentialsId: 'github-token', variable: 'GH_TOKEN')]) {
                        sh """
                            gh release create ${version} dist/jclaw.zip \
                                --repo tsukhani/jclaw \
                                --title "JClaw ${version}" \
                                --generate-notes
                        """
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
