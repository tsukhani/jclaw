pipeline {
    agent any

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
                sh 'play auto-test --http.port=9100'
            }
            post {
                always {
                    junit testResults: 'test-result/*.xml', allowEmptyResults: true
                }
            }
        }

        stage('Package') {
            when {
                anyOf {
                    branch 'main'
                    buildingTag()
                }
            }
            steps {
                sh 'play dist'

                // Inject the SPA build into the dist zip
                sh '''
                    cd dist
                    unzip -q jclaw.zip
                    cp -r ../frontend/.output/public jclaw/public/spa
                    rm jclaw.zip
                    zip -qr jclaw.zip jclaw/
                    rm -rf jclaw/
                '''

                archiveArtifacts artifacts: 'dist/jclaw.zip', fingerprint: true
            }
        }

        stage('Release') {
            when {
                buildingTag()
            }
            steps {
                script {
                    def version = env.TAG_NAME
                    echo "Creating release for ${version}"

                    // Publish to Bitbucket Downloads (requires Bitbucket credentials)
                    withCredentials([usernamePassword(
                        credentialsId: 'abundent',
                        usernameVariable: 'BB_USER',
                        passwordVariable: 'BB_PASS'
                    )]) {
                        sh """
                            curl -u \$BB_USER:\$BB_PASS -X POST \
                                https://bitbucket.abundent.com/rest/api/latest/projects/JCLAW/repos/jclaw/browse/downloads/jclaw-${version}.zip \
                                -F file=@dist/jclaw.zip
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
