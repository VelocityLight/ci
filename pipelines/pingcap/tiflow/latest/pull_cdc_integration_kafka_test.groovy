// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
// @Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflow"
final GIT_FULL_REPO_NAME = 'pingcap/tiflow'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflow/latest/pod-pull_cdc_integration_kafka_test.yaml'

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
    }
    options {
        timeout(time: 65, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Debug info') {
            steps {
                sh label: 'Debug info', script: """
                    printenv
                    echo "-------------------------"
                    go env
                    echo "-------------------------"
                    echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                """
                container(name: 'net-tool') {
                    sh 'dig github.com'
                }
            }
        }
        stage('Checkout') {
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                dir("tiflow") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tiflow/rev-${ghprbActualCommit}", restoreKeys: ['git/pingcap/tiflow/rev-']) {
                        retry(2) {
                            checkout(
                                changelog: false,
                                poll: false,
                                scm: [
                                    $class: 'GitSCM', branches: [[name: ghprbActualCommit ]],
                                    doGenerateSubmoduleConfigurations: false,
                                    extensions: [
                                        [$class: 'PruneStaleBranch'],
                                        [$class: 'CleanBeforeCheckout'],
                                        [$class: 'CloneOption', timeout: 15],
                                    ],
                                    submoduleCfg: [],
                                    userRemoteConfigs: [[
                                        refspec: "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*",
                                        url: "https://github.com/${GIT_FULL_REPO_NAME}.git",
                                    ]],
                                ]
                            )
                        }
                    }
                }
            }
        }
        stage("prepare") {
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                dir("third_party_download") {
                    retry(2) {
                        sh label: "download third_party", script: """
                            cd ../tiflow && ./scripts/download-integration-test-binaries.sh master && ls -alh ./bin
                            make check_third_party_binary
                            cd - && mkdir -p bin && mv ../tiflow/bin/* ./bin/
                            ls -alh ./bin
                            ./bin/tidb-server -V
                            ./bin/pd-server -V
                            ./bin/tikv-server -V
                            ./bin/tiflash --version
                            ./bin/sync_diff_inspector --version
                        """
                    }
                }
                dir("tiflow") {
                    cache(path: "./bin", filter: '**/*', key: "git/pingcap/tiflow/cdc-integration-test-binarys-${ghprbActualCommit}") { 
                        // build cdc, kafka_consumer, storage_consumer, cdc.test for integration test
                        // only build binarys if not exist, use the cached binarys if exist
                        sh label: "prepare", script: """
                            ls -alh ./bin
                            [ -f ./bin/cdc ] || make cdc
                            [ -f ./bin/cdc_kafka_consumer ] || make kafka_consumer
                            [ -f ./bin/cdc_storage_consumer ] || make storage_consumer
                            [ -f ./bin/cdc.test ] || make integration_test_build
                            ls -alh ./bin
                            ./bin/cdc version
                        """
                    }
                    cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}/tiflow-cdc") { 
                        sh label: "prepare", script: """
                            cp -r ../third_party_download/bin/* ./bin/
                            ls -alh ./bin
                        """
                    }
                }
            }
        }

        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_GROUP'
                        values 'G00', 'G01', 'G02', 'G03', 'G04', 'G05', 'G06',  'G07', 'G08', 'G09', 'G10', 'G11', 'G12', 'G13', 
                            'G14', 'G15', 'G16', 'G17', 'G18', 'G19', 'G20', 'G21', 'G22', 'G23', 'G24', 'others'
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yamlFile POD_TEMPLATE_FILE
                        defaultContainer 'golang'
                    }
                } 
                stages {
                    stage("Test") {
                        options { timeout(time: 45, unit: 'MINUTES') }
                        environment { 
                            TICDC_CODECOV_TOKEN = credentials('codecov-token-tiflow') 
                            TICDC_COVERALLS_TOKEN = credentials('coveralls-token-tiflow')    
                        }
                        steps {
                            dir('tiflow') {
                                cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}/tiflow-cdc") {
                                    container("kafka") {
                                        timeout(time: 6, unit: 'MINUTES') {
                                            sh label: "Waiting for kafka ready", script: """
                                                echo "Waiting for zookeeper to be ready..."
                                                while ! nc -z localhost 2181; do sleep 10; done
                                                echo "Waiting for kafka to be ready..."
                                                while ! nc -z localhost 9092; do sleep 10; done
                                                echo "Waiting for kafka-broker to be ready..."
                                                while ! echo dump | nc localhost 2181 | grep brokers | awk '{\$1=\$1;print}' | grep -F -w "/brokers/ids/1"; do sleep 10; done
                                            """
                                        }
                                    }
                                    sh label: "${TEST_GROUP}", script: """
                                        rm -rf /tmp/tidb_cdc_test && mkdir -p /tmp/tidb_cdc_test
                                        chmod +x ./tests/integration_tests/run_group.sh
                                        ./tests/integration_tests/run_group.sh kafka ${TEST_GROUP}
                                    """
                                }
                            }
                        }
                        post {
                            failure {
                                sh label: "collect logs", script: """
                                    ls /tmp/tidb_cdc_test/
                                    tar -cvzf log-${TEST_GROUP}.tar.gz \$(find /tmp/tidb_cdc_test/ -type f -name "*.log")    
                                    ls -alh  log-${TEST_GROUP}.tar.gz  
                                """
                                archiveArtifacts artifacts: "log-${TEST_GROUP}.tar.gz", fingerprint: true 
                            }
                        }
                    }
                }
            }        
        }
    }
}
