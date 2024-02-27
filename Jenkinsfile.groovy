// METHODS FOR REPLACEMENT
def replaceTemplate(String fileName, String outputPath, Map replacementMap) {
  def content = readFile("${env.HELM_TEMPLATE_DIR}/${fileName}")
  replacementMap.each { key, value -> content = content.replace(key, value) }
  writeFile file: outputPath, text: content
}

def replaceChart() {
  replaceTemplate('Chart.yaml', "${env.CURR_DIR}/charts/${params.chartName}/Chart.yaml", ['{{CHART_VERSION}}': "${env.CHART_VERSION}"])
}

// METHODS FOR HELM
def helmUpgrade() {
  sh """
    sudo helm repo update ${env.CHART_REPO_NAME}
    sudo helm upgrade --install ${params.appName} ${env.CHART_REPO_NAME}/${params.chartName} --namespace ${params.namespace} --create-namespace --version ${env.CHART_VERSION} --kubeconfig ${env.KUBE_CONFIG_DIR} --timeout 2m0s
  """
}

// METHODS FOR GIT
def gitCheckout(String tagName) {
  try {
    echo 'Checkout - Starting.'
    cleanWs()
    checkout([$class: 'GitSCM', branches: [[name: "${tagName}"]], extensions: [], userRemoteConfigs: [[credentialsId: "${env.GITHUB_CREDENTIAL_ID}", url: "${env.GIT_REPO_URL}"]]])
    echo 'Checkout - Completed.'
  } catch (err) {
    echo 'Checkout - Failed.'
    currentBuild.result = 'FAILURE'
    error(err.message)
  }
}

pipeline {
  agent any

  parameters {
    string(name: 'appName', defaultValue: params.appName, description: 'Please fill application name.')
    string(name: 'namespace', defaultValue: params.namespace, description: 'Please fill namespace.')
    choice(name: 'chartName', choices: ['bcc', 'cp', 'csc', 'ioc', 'ucp'], description: 'Please select chart name.')
    string(name: 'chartVersion', defaultValue: params.chartVersion, description: 'Please fill version.')
    choice(name: 'buildType', choices: ['alpha', 'ReleaseTag'], description: 'Please select build type.')
  }

  environment {
    CURR_DIR = sh(script: 'sudo pwd', returnStdout: true).trim()
    HELM_TEMPLATE_DIR = "${env.CURR_DIR}/charts/${params.chartName}/helm-template"
    KUBE_CONFIG_DIR = '/root/.kube/Config'

    GITHUB_CREDENTIAL_ID = 'GITHUB-jenkins'
    GITHUB_CREDENTIAL = credentials("${GITHUB_CREDENTIAL_ID}")
    GIT_BRANCH_NAME = 'main'
    GIT_REPO_URL = 'https://github.com/pongsathorn-ph/png-iapi-chart.git'

    CHART_REPO_NAME = 'demo-repo'
    CHART_REPO_URL = 'https://pongsathorn-ph.github.io/png-iapi-chart/'
    CURR_BUILD = String.format('%04d', currentBuild.number)
    CHART_VERSION = "${params.chartVersion}-${env.CURR_BUILD}-${params.buildType}"

    LAST_REVISION_TAG = ""
  }

  stages {
    stage('Build and deploy Alpha') {
      when {
        expression {
          params.buildType == 'alpha'
        }
      }
      stages {
        stage('Initial') {
          steps {
            script {
              currentBuild.displayName = currentBuild.displayName + "  :  ALPHA"
            }
            script {
              if (params.buildType == 'ReleaseTag') {
                withEnv(["chartVersion=${params.chartVersion}-${env.CURR_BUILD}"]) {
                  echo "Chart version: ${env.CHART_VERSION}"
                }
              }
            }
          }
        }

        stage('Checkout') {
          steps {
            script {
              gitCheckout("${env.GIT_BRANCH_NAME}")
            }
          }
        }

        stage('Replace') {
          steps {
            script {
              try {
                echo 'Replace - Starting.'
                replaceChart()
                echo 'Replace - Completed.'
                sh """
                  sudo ls -al ${env.CURR_DIR}/charts/${params.chartName}
                  cat ${env.CURR_DIR}/charts/${params.chartName}/Chart.yaml
                """
              } catch (err) {
                echo 'Replace - Failed.'
                currentBuild.result = 'FAILURE'
                error(err.message)
              }
            }
          }
        }

        stage('Package') {
          steps {
            script {
              try {
                echo 'Package - Starting.'
                sh """
                  sudo mkdir -p ${env.CURR_DIR}/assets/${params.chartName}

                  sudo helm dependency update ${env.CURR_DIR}/charts/${params.chartName}/

                  sudo helm package ${env.CURR_DIR}/charts/${params.chartName} -d ${env.CURR_DIR}/temp
                  sudo helm repo index --url assets/${params.chartName} --merge ${env.CURR_DIR}/index.yaml ${env.CURR_DIR}/temp

                  sudo mv ${env.CURR_DIR}/temp/${params.chartName}-*.tgz ${env.CURR_DIR}/assets/${params.chartName}
                  sudo mv ${env.CURR_DIR}/temp/index.yaml ${env.CURR_DIR}/
                  sudo rm -rf ${env.CURR_DIR}/temp

                  #sudo ls -al ${env.CURR_DIR}/assets/${params.chartName}
                  #sudo cat ${env.CURR_DIR}/index.yaml
                """
                echo 'Package - Completed.'
              } catch (err) {
                echo 'Package - Failed.'
                currentBuild.result = 'FAILURE'
                error(err.message)
              }
            }
          }
        }

        stage('Git commit and push') {
          steps {
            script {
              try {
                echo 'GIT Commit - Starting.'
                sh """
                  git config --global user.name 'Jenkins Pipeline'
                  git config --global user.email 'jenkins@localhost'
                  git checkout -b ${env.GIT_BRANCH_NAME}
                  git add .
                  git commit -m 'Update from Jenkins-Pipeline'
                  git push https://$GITHUB_CREDENTIAL_USR:$GITHUB_CREDENTIAL_PSW@github.com/pongsathorn-ph/png-iapi-chart.git ${env.GIT_BRANCH_NAME}
                """
                echo 'GIT Commit - Completed.'
              } catch (err) {
                echo 'GIT Commit - Failed.'
                currentBuild.result = 'FAILURE'
                error(err.message)
              }
            }
          }
        }
        
        stage('Helm install') {
          steps {
            script {
              try {
                echo 'Helm install - Starting.'
                sleep 60
                sh "sudo helm repo add ${env.CHART_REPO_NAME} ${env.CHART_REPO_URL}"
                helmUpgrade()
                echo 'Helm install - Completed.'
              } catch (err) {
                retry(2) {
                  sleep 60
                  helmUpgrade()
                }
              }
            }
          }
        }

        stage('Remove tag') {
          steps {
            script {
              echo 'Remove tag - Starting.'
              catchError(buildResult: 'SUCCESS',stageResult: 'SUCCESS') {
                sh """
                  git tag -d ${params.chartVersion}
                  git push --delete https://$GITHUB_CREDENTIAL_USR:$GITHUB_CREDENTIAL_PSW@github.com/pongsathorn-ph/png-iapi-chart.git ${params.chartVersion}
                """
              }
              echo 'Remove tag - Completed.'
            }
          }
        }

        stage('Push tag') {
          steps {
            script {
              try {
                echo 'Push tag - Starting.'
                currentBuild.displayName = "${currentBuild.displayName} : TAG 🏷️"
                sh """
                  git tag ${params.chartVersion}
                  git push https://$GITHUB_CREDENTIAL_USR:$GITHUB_CREDENTIAL_PSW@github.com/pongsathorn-ph/png-iapi-chart.git ${params.chartVersion}
                """
                echo 'Push tag - Completed.'
              } catch (err) {
                echo 'Push tag - Failed.'
                currentBuild.result = 'FAILURE'
                error(err.message)
              }
            }
          }
        }
        
      }
    }
    /*
    stage('Confirm tag version') {
      when {
        expression {
          env.buildType == 'ReleaseTag'
        }
      }
      steps {
        echo 'Ask for confirm.'
        script {
          env.tagVersion = input message: 'Confirm for production tag release V.' + params.chartVersion, parameters: [string(defaultValue: '', description: '', name: 'Type version for confirm.', trim: false)]
        }
      }
    }
    */
    stage('Tag') {
      /*
      when {
        expression {
          env.buildType == 'ReleaseTag' && params.chartVersion == env.tagVersion
        }
      }
      */
      stages {
        stage('Prepare tag') {
          steps {
            script {
              currentBuild.displayName = "${currentBuild.displayName} : TAG 🏷️"
            }
            script {
              def job = Jenkins.instance.getItemByFullName("${env.JOB_NAME}")

              job.builds.find {
                if(it.result == hudson.model.Result.SUCCESS) {
                  def is_BuildDev = it.actions.find{it instanceof ParametersAction}?.parameters.find{it.name == "BuildDev"}?.value
                  echo "is_BuildDev: ${is_BuildDev}"
                  // echo "Build name: ${it.fullDisplayName}"
                  // echo "Build number: ${it.getId()}"

                  LAST_REVISION_TAG = it.actions.find{it instanceof hudson.plugins.git.util.BuildData}?.lastBuild
                  if(LAST_REVISION_TAG != null) {
                    env.LAST_REVISION_TAG = LAST_REVISION_TAG
                    return true
                  }
                }
              }

              echo "Last revision tag: ${env.LAST_REVISION_TAG}"

              // checkout มาจาก tag
              // gitCheckout("${env.GIT_BRANCH_NAME}")
            }
          }
        }

        // /usr/share/jenkins/ref$ cat /var/jenkins_home/workspace/PNG-IAPI/PNG-IAPI_WEB-BCC-UI/helm-chart/index.yaml
        // validate image ไม่มี alpha เหลือแล้ว
        // ลบ alpha ออกจาก index
        // commit && push
        
        /*
        stage('Push tag to gitlab') {
          steps {
            sh "git tag ${env.tagVersion}"
            withCredentials([gitUsernamePassword(credentialsId: "${env.GITHUB_CREDENTIAL_ID}", gitToolName: 'Default')]) {
              sh "git push origin ${env.tagVersion}"
            }
          }
        }
        */
      }
    }
  }
}
