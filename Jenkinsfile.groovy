// METHODS FOR REPLACEMENT
def replaceTemplate(String fileName, String outputPath, Map replacementMap) {
  def content = readFile("${env.HELM_TEMPLATE_DIR}/${fileName}")
  replacementMap.each { key, value -> content = content.replace(key, value) }
  writeFile file: outputPath, text: content
}

def replaceChart() {
  def replacementMap = [
    "{{CHART_VERSION}}": "${env.CHART_VERSION}", 
    "{{DEPENDENCY_UI_VERSION}}": "${env.DEPENDENCY_UI_VERSION}",
    "{{DEPENDENCY_API_VERSION}}": "${env.DEPENDENCY_API_VERSION}"
  ]
  replaceTemplate('Chart.yaml', "${env.WORKSPACE}/charts/${params.chartName}/Chart.yaml", replacementMap)
}

def packageProcess() {
  sh """
    sudo mkdir -p ${env.WORKSPACE}/assets/${params.chartName}

    sudo helm dependency update ${env.WORKSPACE}/charts/${params.chartName}/

    sudo helm package ${env.WORKSPACE}/charts/${params.chartName} -d ${env.WORKSPACE}/temp
    sudo helm repo index --url assets/${params.chartName} --merge ${env.WORKSPACE}/index.yaml ${env.WORKSPACE}/temp

    sudo mv ${env.WORKSPACE}/temp/${params.chartName}-*.tgz ${env.WORKSPACE}/assets/${params.chartName}
    sudo mv ${env.WORKSPACE}/temp/index.yaml ${env.WORKSPACE}/
    sudo rm -rf ${env.WORKSPACE}/temp

    #sudo ls -al ${env.WORKSPACE}/assets/${params.chartName}
    #sudo cat ${env.WORKSPACE}/index.yaml
  """
}

def gitCommitPushProcess() {
  withCredentials([gitUsernamePassword(credentialsId: "${env.GITHUB_CREDENTIAL_ID}", gitToolName: 'Default')]) {
    sh """
      git config --global user.name 'Jenkins Pipeline'
      git config --global user.email 'jenkins@localhost'
      git checkout -b ${env.GIT_BRANCH_NAME}
      git add .
      git commit -m 'Update from Jenkins-Pipeline'
      git push origin ${env.GIT_BRANCH_NAME}
    """
  }
}

def gitCheckoutProcess(String tagName) {
  cleanWs()
  checkout([$class: 'GitSCM', branches: [[name: tagName]], extensions: [], userRemoteConfigs: [[credentialsId: env.GITHUB_CREDENTIAL_ID, url: "https://${env.GIT_REPO}"]]])
}

def gitRemoveTagProcess(String tagName) {
  catchError(buildResult: 'SUCCESS', stageResult: 'SUCCESS') {
    sh """
      git tag -d ${tagName}
      git push --delete https://$GITHUB_CREDENTIAL_USR:$GITHUB_CREDENTIAL_PSW@${env.GIT_REPO} ${tagName}
    """
  }
}

def gitPushTagProcess(String tagName) {
  sh """
    git tag ${tagName}
    git push https://$GITHUB_CREDENTIAL_USR:$GITHUB_CREDENTIAL_PSW@${env.GIT_REPO} ${tagName}
  """
}

// METHODS FOR HELM
def helmUpgrade() {
  sh """
    sudo helm repo update ${env.CHART_REPO_NAME}
    sudo helm upgrade --install ${params.appName} ${env.CHART_REPO_NAME}/${params.chartName} --namespace ${params.namespace} --create-namespace --version ${env.CHART_VERSION} --kubeconfig ${env.KUBE_CONFIG_DIR} --timeout 2m0s
  """
}

def validateAlpha(String jobName) {
  def yaml = readYaml file: "../${jobName}/helm-chart/Chart.yaml"
  def version = yaml.version
  
  if (version.toString().contains('alpha')) {
    catchError(buildResult: 'ABORTED', stageResult: 'ABORTED') {
      error('ABORTED Reason - Found alpha.')
    }
  }
}

pipeline {
  agent any

  parameters {
    string(name: 'appName', defaultValue: params.appName, description: 'Please fill application name.')
    string(name: 'namespace', defaultValue: params.namespace, description: 'Please fill namespace.')
    choice(name: 'chartName', choices: ['bcc', 'cp', 'csc', 'ioc', 'ucp'], description: 'Please select chart name.')
    string(name: 'chartVersion', defaultValue: params.chartVersion, description: 'Please fill version.')
    choice(name: 'buildType', choices: ['ALPHA', 'RELEASE TAG'], description: 'Please select build type.')
  }

  environment {
    BUILD_NUMBER = String.format('%04d', currentBuild.number)
    HELM_TEMPLATE_DIR = "${env.WORKSPACE}/charts/${params.chartName}/helm-template"
    KUBE_CONFIG_DIR = '/root/.kube/Config'

    GITHUB_CREDENTIAL_ID = 'GITHUB-jenkins'
    GITHUB_CREDENTIAL = credentials("${GITHUB_CREDENTIAL_ID}")

    GIT_BRANCH_NAME = 'main'
    GIT_REPO = 'github.com/pongsathorn-ph/png-iapi-chart.git'

    CHART_REPO_NAME = 'demo-repo'
    CHART_REPO_URL = 'https://pongsathorn-ph.github.io/png-iapi-chart/'
    CHART_VERSION = "${params.chartVersion}-${env.BUILD_NUMBER}-${params.buildType}"

    DEPENDENCY_UI_VERSION = "2024.02.00-0002-ALPHA"
    DEPENDENCY_API_VERSION = "2024.02.00-0002-ALPHA"

    TAG_NAME_ALPHA = "${params.chartVersion}-ALPHA"
    TAG_NAME_PRO = "${params.chartVersion}"
  }

  stages {
    stage('Initial') {
      when {
        expression {
          params.buildType != 'initial'
        }
      }
      steps {
        script {
          currentBuild.displayName = "${params.chartVersion}-${env.BUILD_NUMBER}"
        }
      }
    }

    stage('Build Alpha') {
      when {
        expression {
          params.buildType == 'ALPHA'
        }
      }
      stages {
        stage('Preparing') {
          steps {
            script {
              currentBuild.displayName = "${currentBuild.displayName} : ALPHA"
            }
          }
        }

        stage('Checkout') {
          steps {
            script {
              try {
                echo 'Checkout - Starting.'
                gitCheckoutProcess("${env.GIT_BRANCH_NAME}")
                echo 'Checkout - Completed.'
              } catch (err) {
                echo 'Checkout - Failed.'
                currentBuild.result = 'FAILURE'
                error(err.message)
              }
            }
          }
        }

        stage('Replacement') {
          steps {
            script {
              try {
                echo 'Replacement - Starting.'
                replaceChart()
                sh """
                  sudo ls -al ${env.WORKSPACE}/charts/${params.chartName}
                  cat ${env.WORKSPACE}/charts/${params.chartName}/Chart.yaml
                """
                echo 'Replacement - Completed.'
              } catch (err) {
                echo 'Replacement - Failed.'
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
                packageProcess()
                echo 'Package - Completed.'
              } catch (err) {
                echo 'Package - Failed.'
                currentBuild.result = 'FAILURE'
                error(err.message)
              }
            }
          }
        }

        stage('Commit and Push') {
          steps {
            script {
              try {
                echo 'GIT Commit - Starting.'
                gitCommitPushProcess()
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
              gitRemoveTagProcess("${env.TAG_NAME_ALPHA}")
              echo 'Remove tag - Completed.'
            }
          }
        }

        stage('Push tag') {
          steps {
            script {
              try {
                echo 'Push tag - Starting.'
                gitPushTagProcess("${env.TAG_NAME_ALPHA}")
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
    
    // stage('Confirm tag version') {
    //   when {
    //     expression {
    //       params.buildType == 'RELEASE TAG'
    //     }
    //   }
    //   steps {
    //     echo 'Ask for confirm.'
    //     script {
    //       env.tagVersion = input message: 'Confirm for production tag release V.' + params.chartVersion, parameters: [string(defaultValue: '', description: '', name: 'Type version for confirm.', trim: false)]
    //     }
    //   }
    // }
    
    // stage('Tag') {
    //   when {
    //     expression {
    //       params.buildType == 'ReleaseTag'// && params.chartVersion == env.tagVersion
    //     }
    //   }
    //   stages {
    //     stage('Prepare tag') {
    //       steps {
    //         script {
    //           currentBuild.displayName = "${currentBuild.displayName} : TAG 🏷️"
    //         }
    //         // validate image ไม่มี alpha เหลือแล้ว (UI และ API)
    //         script {
    //           validateAlpha("PNG-IAPI_WEB-BCC-UI")
    //         }
    //         script {
    //           def job = Jenkins.instance.getItemByFullName("${env.JOB_NAME}")

    //           job.builds.find {
    //             if(it.result == hudson.model.Result.SUCCESS) {
    //               // def is_BuildDev = it.actions.find{it instanceof ParametersAction}?.parameters.find{it.name == "BuildDev"}?.value
    //               // echo "is_BuildDev: ${is_BuildDev}"
    //               echo "Build name: ${it.name}"
    //               echo "Build number: ${it.getId()}"

    //               TAG_LAST_REVISION = it.actions.find{it instanceof hudson.plugins.git.util.BuildData}?.lastBuild
    //               if(TAG_LAST_REVISION != null) {
    //                 env.TAG_LAST_REVISION = TAG_LAST_REVISION
    //                 return true
    //               }
    //             }
    //           }

    //           echo "Last revision tag: ${env.TAG_LAST_REVISION}"

    //           checkout มาจาก tag
    //           gitCheckout("${env.GIT_BRANCH_NAME}")
    //         }
    //       }
    //     }
        
    //     // ลบ alpha ออกจาก index
    //     // commit && push
    //   }
    // }
  }
}
