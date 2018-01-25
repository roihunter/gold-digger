pipeline {
    agent {label 'docker01'}

    options {
        ansiColor colorMapName: 'XTerm'
    }

    parameters {
        string(
            name: 'app_servers',
            defaultValue: '10.10.10.185',
            description: 'Deploy container to these servers. List of servers separated by comma.'
        )
        string(
            name: 'database_host',
            defaultValue: '10.10.10.122',
            description: 'Postgresql DB host.'
        )
        string(
            name: 'database_port',
            defaultValue: '55432',
            description: 'Postgresql DB port.'
        )
        booleanParam(name: 'build_image', defaultValue: true, description: 'Build image and upload it to Docker registry')
        booleanParam(name: 'send_notification', defaultValue: true, description: 'Send notification about deploy to Slack')
    }

    stages {
        stage('Build') {
            when {
                expression {
                    return params.build_image
                }
            }
            steps {
                sh "docker build --rm=true -t golddigger-master ."
            }
        }

        stage('Prepare and upload to registry ') {
            when {
                expression {
                    return params.build_image
                }
            }
            steps {
                withCredentials([string(credentialsId: 'docker-registry-azure', variable: 'DRpass')]) {
                    sh 'docker login roihunter.azurecr.io -u roihunter -p "$DRpass"'
                    sh "docker tag golddigger-master roihunter.azurecr.io/golddigger/master"
                    sh "docker push roihunter.azurecr.io/golddigger/master"
                    sh "docker rmi golddigger-master"
                    sh "docker rmi roihunter.azurecr.io/golddigger/master"
                }
            }
        }

        stage('Deploy containers') {
            steps {
                withCredentials([
                    string(credentialsId: 'docker-registry-azure', variable: 'DRpass'),
                    string(
                        credentialsId: 'gold_digger_master_secrets_currency_layer_access_key',
                        variable: 'gold_digger_master_secrets_currency_layer_access_key'
                    ),
                    usernamePassword(
                        credentialsId: 'gold_digger_master_database',
                        usernameVariable: 'gold_digger_master_db_user',
                        passwordVariable: 'gold_digger_master_db_password'
                    )
                ]) {
                    script {
                        def servers = params['app_servers'].tokenize(',')
                        def database_host = params['database_host']
                        def database_port = params['database_port']

                        for (item in servers) {
                            sshagent(['5de2256c-107d-4e4a-a31e-2f33077619fe']) {
                                sh """ssh -oStrictHostKeyChecking=no -t -t jenkins@${item} <<EOF
                                docker login roihunter.azurecr.io -u roihunter -p "$DRpass"
                                docker pull roihunter.azurecr.io/golddigger/master
                                docker rmi \$(docker images -qa)

                                docker stop golddigger-master
                                docker rm -v golddigger-master
                                docker run --detach -p 8000:8000 \
                                    -e "GUNICORN_WORKERS=4" \
                                    -e "GUNICORN_BIND=0.0.0.0:8000" \
                                    -e "GOLD_DIGGER_PROFILE=master" \
                                    -e GOLD_DIGGER_DATABASE_HOST='''$database_host''' \
                                    -e GOLD_DIGGER_DATABASE_PORT='''$database_port''' \
                                    -e GOLD_DIGGER_DATABASE_USER='''$gold_digger_master_db_user''' \
                                    -e GOLD_DIGGER_DATABASE_PASSWORD='''$gold_digger_master_db_password''' \
                                    -e GOLD_DIGGER_SECRETS_CURRENCY_LAYER_ACCESS_KEY='''$gold_digger_master_secrets_currency_layer_access_key''' \
                                    --hostname=golddigger-master-"$item" \
                                    --name=golddigger-master \
                                    --restart=always \
                                    roihunter.azurecr.io/golddigger/master

                                docker stop golddigger-cron-master
                                docker rm -v golddigger-cron-master
                                docker run --detach \
                                    -e "GOLD_DIGGER_PROFILE=master" \
                                    -e GOLD_DIGGER_DATABASE_HOST='''$database_host''' \
                                    -e GOLD_DIGGER_DATABASE_PORT='''$database_port''' \
                                    -e GOLD_DIGGER_DATABASE_USER='''$gold_digger_master_db_user''' \
                                    -e GOLD_DIGGER_DATABASE_PASSWORD='''$gold_digger_master_db_password''' \
                                    -e GOLD_DIGGER_SECRETS_CURRENCY_LAYER_ACCESS_KEY='''$gold_digger_master_secrets_currency_layer_access_key''' \
                                    --hostname=golddigger-cron-master-"$item" \
                                    --name=golddigger-cron-master \
                                    --restart=always \
                                    roihunter.azurecr.io/golddigger/master \
                                    python -m gold_digger cron

                                exit
                                EOF"""
                            }
                        }
                    }
                }
            }
        }

        stage('Send notification') {
            when {
                expression {
                    return params.send_notification
                }
            }
            steps {
                withCredentials([string(credentialsId: 'slack-bot-token', variable: 'slackToken')]) {
                    slackSend channel: 'deploy', message: 'GoldDigger application was deployed', color: '#0E8A16', token: slackToken, botUser: true
                }
            }
        }
    }
    post {
        always {
            // Clean Workspace
            cleanWs()
        }
    }
}