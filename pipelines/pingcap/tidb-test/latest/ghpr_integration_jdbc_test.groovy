// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap/tidb-test'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb-test/latest/pod-ghpr_integration_jdbc_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

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
        timeout(time: 60, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Debug info') {
            // options { }  Valid option types: [cache, catchError, checkoutToSubdirectory, podTemplate, retry, script, skipDefaultCheckout, timeout, waitUntil, warnError, withChecks, withContext, withCredentials, withEnv, wrap, ws]
            steps {
                sh label: 'Debug info', script: """
                    printenv
                    echo "-------------------------"
                    go env
                    echo "-------------------------"
                    ls -l /dev/null
                    echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                """
                container(name: 'net-tool') {
                    sh 'dig github.com'
                }
            }
        }
        stage('Checkout') {
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir("tidb") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb/rev-${REFS.pulls[0].sha}}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            script {
                                component.checkout('https://github.com/pingcap/tidb.git', 'tidb', REFS.base_ref, REFS.pulls[0].title, "")
                            }
                        }
                    }
                }
                dir("tidb-test") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb-test/rev-${REFS.pulls[0].sha}}", restoreKeys: ['git/pingcap/tidb-test/rev-']) {
                        retry(2) {
                            checkout(
                                changelog: false,
                                poll: false,
                                scm: [
                                    $class: 'GitSCM', branches: [[name: REFS.pulls[0].sha]],
                                    doGenerateSubmoduleConfigurations: false,
                                    extensions: [
                                        [$class: 'PruneStaleBranch'],
                                        [$class: 'CleanBeforeCheckout'],
                                        [$class: 'CloneOption', timeout: 15],
                                    ],
                                    submoduleCfg: [],
                                    userRemoteConfigs: [[
                                        credentialsId: GIT_CREDENTIALS_ID,
                                        refspec: "+refs/pull/${REFS.pulls[0].number}/*:refs/remotes/origin/pr/${REFS.pulls[0].number}/*",
                                        url: "git@github.com:${GIT_FULL_REPO_NAME}.git",
                                    ]],
                                ]
                            )
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb') {
                    cache(path: "./bin", filter: '**/*', key: "ws/${BUILD_TAG}/dependencies") {
                        sh label: 'tidb-server', script: 'ls bin/tidb-server || make'
                        sh label: 'download binary', script: """
                            chmod +x ${WORKSPACE}/scripts/pingcap/tidb-test/*.sh
                            ${WORKSPACE}/scripts/pingcap/tidb-test/download_pingcap_artifact.sh --pd=${REFS.base_ref} --tikv=${REFS.base_ref}
                            mv third_bin/* bin/
                            ls -alh bin/
                        """
                    }
                }
                dir('tidb-test') {
                    cache(path: "./", filter: '**/*', key: "ws/tidb-test/rev-${REFS.pulls[0].sha}") {
                        sh "touch ws-${BUILD_TAG}"
                    }
                }
            }
        }
        stage('JDBC Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_PARAMS'
                        values 'jdbc8_test ./test_fast.sh', 'jdbc8_test ./test_slow.sh', 'mybatis_test ./test.sh',
                            'jooq_test ./test.sh', 'tidb_jdbc_test/tidb_jdbc_unique_test ./test.sh',
                            'tidb_jdbc_test/tidb_jdbc8_test ./test_fast.sh', 'tidb_jdbc_test/tidb_jdbc8_test ./test_slow.sh', 
                            'tidb_jdbc_test/tidb_jdbc8_tls_test ./test_slow.sh', 'tidb_jdbc_test/tidb_jdbc8_tls_test ./test_tls.sh'
                            // 'hibernate_test/hibernate-orm-test ./test.sh'
                    }
                    axis {
                        name 'TEST_STORE'
                        values "tikv"
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yamlFile POD_TEMPLATE_FILE
                        defaultContainer 'java'
                    }
                } 
                stages {
                    stage("Test") {
                        options { timeout(time: 40, unit: 'MINUTES') }
                        steps {
                            dir('tidb') {
                                cache(path: "./bin", filter: '**/*', key: "ws/${BUILD_TAG}/dependencies") {
                                    sh label: "print version", script: """
                                        pwd && ls -alh
                                        ls bin/tidb-server && chmod +x bin/tidb-server && ./bin/tidb-server -V
                                        ls bin/pd-server && chmod +x bin/pd-server && ./bin/pd-server -V
                                        ls bin/tikv-server && chmod +x bin/tikv-server && ./bin/tikv-server -V
                                    """
                                }
                            }
                            dir('tidb-test') {
                                cache(path: "./", filter: '**/*', key: "ws/tidb-test/rev-${REFS.pulls[0].sha}") {
                                    sh """
                                        mkdir -p bin
                                        cp ${WORKSPACE}/tidb/bin/* bin/ && chmod +x bin/*
                                        ls -alh bin/
                                    """
                                    container("java") {
                                        sh label: "test_params=${TEST_PARAMS} ", script: """
                                            #!/usr/bin/env bash
                                            params_array=(\${TEST_PARAMS})
                                            TEST_DIR=\${params_array[0]}
                                            TEST_SCRIPT=\${params_array[1]}
                                            echo "TEST_DIR=\${TEST_DIR}"
                                            echo "TEST_SCRIPT=\${TEST_SCRIPT}"
                                            if [[ "${TEST_STORE}" == "tikv" ]]; then
                                                echo '[storage]\nreserve-space = "0MB"'> tikv_config.toml
                                                bash ${WORKSPACE}/scripts/pingcap/tidb-test/start_tikv.sh
                                                export TIDB_SERVER_PATH="${WORKSPACE}/tidb-test/bin/tidb-server"
                                                export TIKV_PATH="127.0.0.1:2379"
                                                export TIDB_TEST_STORE_NAME="tikv"
                                                cd \${TEST_DIR} && chmod +x *.sh && \${TEST_SCRIPT}
                                            else
                                                export TIDB_SERVER_PATH="${WORKSPACE}/tidb-test/bin/tidb-server"
                                                export TIDB_TEST_STORE_NAME="unistore"
                                                cd \${TEST_DIR} && chmod +x *.sh && \${TEST_SCRIPT}
                                            fi
                                        """
                                    }
                                }
                            }
                        }
                        post{
                            failure {
                                script {
                                    println "Test failed, archive the log"
                                }
                            }
                        }
                    }
                }
            }        
        }
    }
}
