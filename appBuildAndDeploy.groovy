#!/usr/bin/env groovy

// Pipeline definition
def COLOR_MAP = [
        'SUCCESS': 'good',
        'FAILURE': 'danger',
        'UNSTABLE': 'warning'
]

pipeline {
    agent any

    // Environment variables
    environment {
        DOCKER_REGISTRY = 'URL'
        APP_NAME = 'app-name'
        DOCKER_IMAGE = "${DOCKER_REGISTRY}/${APP_NAME}:${BUILD_NUMBER}"
        SONAR_PROJECT_KEY = 'sonar-project-key'
        DEPLOYMENT_ENV = 'production'
        SLACK_CHANNEL = '#deployments'
    }

    // Pipeline tools
    tools {
        maven 'Maven-3.8.6'
        jdk 'JDK-17'
        python 'Python-3.9'
    }

    // Pipeline options
    options {
        timestamps()
        timeout(time: 1, unit: 'HOURS')
        buildDiscarder(logRotator(numToKeepStr: '10'))
        disableConcurrentBuilds()
    }

    // Pipeline stages
    stages {
        // Code checkout
        stage('Checkout') {
            steps {
                script {
                    // Clean workspace before build
                    cleanWs()
                    checkout scm

                    // Save git commit details
                    env.GIT_COMMIT_MSG = sh(script: 'git log -1 --pretty=%B ${GIT_COMMIT}', returnStdout: true).trim()
                    env.GIT_AUTHOR = sh(script: 'git log -1 --pretty=%an ${GIT_COMMIT}', returnStdout: true).trim()
                }
            }
        }

        // Static code analysis
        stage('Static Analysis') {
            parallel {
                // SonarQube analysis
                stage('SonarQube Analysis') {
                    steps {
                        withSonarQubeEnv('SonarQube') {
                            sh """
                                sonar-scanner \
                                -Dsonar.projectKey=${SONAR_PROJECT_KEY} \
                                -Dsonar.sources=. \
                                -Dsonar.host.url=${SONAR_HOST_URL} \
                                -Dsonar.python.coverage.reportPaths=coverage.xml
                            """
                        }
                        // Quality Gate
                        timeout(time: 5, unit: 'MINUTES') {
                            waitForQualityGate abortPipeline: true
                        }
                    }
                }

                // Security scanning
                stage('Security Scan') {
                    steps {
                        // Run OWASP Dependency Check
                        dependencyCheck additionalArguments: '--format HTML --format XML',
                                odcInstallation: 'OWASP-Dependency-Check'

                        // Publish results
                        dependencyCheckPublisher pattern: 'dependency-check-report.xml'
                    }
                }
            }
        }

        // Build and unit tests
        stage('Build & Test') {
            steps {
                script {
                    // Run Python tests with coverage
                    sh '''#!/bin/bash
                        python -m venv venv
                        source venv/bin/activate
                        pip install -r requirements.txt
                        pytest tests/ --cov=src --cov-report=xml
                        deactivate
                    '''

                    // Build Docker image
                    docker.build(DOCKER_IMAGE, "--no-cache .")
                }
            }
            post {
                always {
                    // Publish test results
                    junit 'test-results/*.xml'
                    // Publish coverage report
                    cobertura coberturaReportFile: 'coverage.xml'
                }
            }
        }

        // Integration tests
        stage('Integration Tests') {
            steps {
                script {
                    // Run integration tests using Python script
                    sh '''#!/bin/bash
                        source venv/bin/activate
                        python integration_tests/run_tests.py
                        deactivate
                    '''
                }
            }
        }

        // Push to registry
        stage('Push to Registry') {
            when {
                branch 'main'
            }
            steps {
                script {
                    // Login to Docker registry
                    withCredentials([usernamePassword(
                            credentialsId: 'docker-registry-credentials',
                            usernameVariable: 'DOCKER_USER',
                            passwordVariable: 'DOCKER_PASS'
                    )]) {
                        sh """
                            echo ${DOCKER_PASS} | docker login ${DOCKER_REGISTRY} -u ${DOCKER_USER} --password-stdin
                            docker push ${DOCKER_IMAGE}
                        """
                    }
                }
            }
        }

        // Deploy to staging
        stage('Deploy to Staging') {
            when {
                branch 'main'
            }
            steps {
                script {
                    // Deploy using Kubernetes
                    withKubeConfig([credentialsId: 'kubeconfig']) {
                        sh """
                            kubectl set image deployment/${APP_NAME} \
                            ${APP_NAME}=${DOCKER_IMAGE} \
                            -n staging
                            
                            kubectl rollout status deployment/${APP_NAME} \
                            -n staging --timeout=300s
                        """
                    }
                }
            }
        }

        // Production deployment approval
        stage('Production Approval') {
            when {
                branch 'main'
            }
            steps {
                // Request manual approval
                timeout(time: 24, unit: 'HOURS') {
                    input message: 'Deploy to production?',
                            ok: 'Deploy'
                }
            }
        }

        // Deploy to production
        stage('Deploy to Production') {
            when {
                branch 'main'
            }
            steps {
                script {
                    // Deploy to production using Kubernetes
                    withKubeConfig([credentialsId: 'kubeconfig']) {
                        sh """
                            kubectl set image deployment/${APP_NAME} \
                            ${APP_NAME}=${DOCKER_IMAGE} \
                            -n production
                            
                            kubectl rollout status deployment/${APP_NAME} \
                            -n production --timeout=300s
                        """
                    }
                }
            }
        }
    }

    // Post-build actions
    post {
        always {
            // Clean up Docker images
            sh "docker rmi ${DOCKER_IMAGE} || true"

            // Send notification to Slack
            slackSend(
                    channel: SLACK_CHANNEL,
                    color: COLOR_MAP[currentBuild.currentResult],
                    message: """
                    ${currentBuild.currentResult}: Job ${env.JOB_NAME} build ${env.BUILD_NUMBER}
                    Commit: ${env.GIT_COMMIT_MSG}
                    Author: ${env.GIT_AUTHOR}
                    Duration: ${currentBuild.durationString}
                    More info at: ${env.BUILD_URL}
                """
            )
        }

        // Archive artifacts on success
        success {
            archiveArtifacts artifacts: 'dist/*/', fingerprint: true
        }

        // Clean workspace
        cleanup {
            cleanWs()
        }
    }
}
