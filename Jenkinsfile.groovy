// METHODS FOR REPLACEMENT
def replaceTemplate(String fileName, String outputPath, Map replacementMap) {
  def content = readFile("${env.helmTemplateDir}/${fileName}")
  replacementMap.each { key, value -> content = content.replace(key, value) }
  writeFile file: outputPath, text: content
}

def replaceChart() {
  replaceTemplate("Chart.yaml", "${env.currentDir}/charts/${params.chartName}/Chart.yaml", ["{{CHART_VERSION}}": "${env.chartVersion}"])
}

// METHODS FOR HELM
def helmUpgrade() {
  sh """
    sudo helm repo update ${env.chartRepoName}
    sudo helm upgrade --install ${params.appName} ${env.chartRepoName}/${params.chartName} --namespace ${params.namespace} --create-namespace --version ${env.chartVersion} --kubeconfig ${env.kubeConfigDir} --debug --atomic --timeout 2m0s
  """
}

pipeline {
  agent any

  parameters {
    string(name: 'appName', defaultValue: params.appName, description: 'Please fill application name.')
    string(name: 'namespace', defaultValue: params.namespace, description: 'Please fill namespace.')
    choice(name: 'chartName', choices: ['bcc', 'cp', 'csc', 'ioc', 'ucp'], description: 'Please select chart name.')
    string(name: 'chartVersion', defaultValue: params.chartVersion, description: 'Please fill version.')
    choice(name: 'buildType', choices: ['alpha'], description: 'Please select build type.')
    booleanParam(name: 'releaseTag', description: '')
  }

  environment {
    currentDir = sh(script: 'sudo pwd', returnStdout: true).trim()
    helmTemplateDir = "${env.currentDir}/helm-template"
    kubeConfigDir = "/root/.kube/Config"

    gitCredentialId = "GITHUB-jenkins"
    gitBranch = "main"
    gitRepoUrl = "https://github.com/pongsathorn-ph/png-iapi-chart.git"

    chartRepoName = "demo-repo"
    chartRepoUrl = "https://pongsathorn-ph.github.io/png-iapi-chart/"
    currentBuild = String.format("%04d", currentBuild.number)
    chartVersion = "${params.chartVersion}-${env.currentBuild}-${params.buildType}"

    imageRepoDev = "pongsathorn/demo-ui-dev"
    imageRepoPre = "pongsathorn/demo-ui-pre"
    imageRepoPro = "pongsathorn/demo-ui-pro"
  }

  stages {

    stage("Initial") {
      steps {
        script {
          if (params.releaseTag) {
            withEnv(["chartVersion=${params.chartVersion}-${env.currentBuild}"]) {
              echo "Release tag: ${params.releaseTag}"
              echo "Chart version: ${env.chartVersion}"
            }
          }
        }
      }
    }

    stage("Checkout") {
      steps {
        script {
          try {
            echo "Checkout - Starting."
            cleanWs()
            checkout([$class: 'GitSCM', branches: [[name: "${env.gitBranch}"]], extensions: [], userRemoteConfigs: [[credentialsId: "${env.gitCredentialId}", url: "${env.gitRepoUrl}"]]])
            echo "Checkout - Completed."
          } catch (err) {
            echo "Checkout - Failed."
            currentBuild.result = 'FAILURE'
            error('Checkout stage failed.')
          }
        }
      }
    }

    stage("Replace") {
      steps {
        script {
          try {
            echo "Replace - Starting."
            replaceChart()
            sh "sudo ls -al ${env.currentDir}/charts/${params.chartName}"
            sh "cat ${env.currentDir}/charts/${params.chartName}/Chart.yaml"
            echo "Replace - Completed."
          } catch(err) {
            echo "Replace - Failed."
            currentBuild.result = 'FAILURE'
            error('Package stage failed.')
          }
        }
      }
    }

    stage("Package") {
      steps {
        script {
          try {
            echo "Package - Starting."
            sh """
              sudo mkdir -p ${env.currentDir}/assets/${params.chartName}

              sudo helm dependency update ${env.currentDir}/charts/${params.chartName}/

              sudo helm package ${env.currentDir}/charts/${params.chartName} -d ${env.currentDir}/temp
              sudo helm repo index --url assets/${params.chartName} --merge ${env.currentDir}/index.yaml ${env.currentDir}/temp
              
              sudo mv ${env.currentDir}/temp/${params.chartName}-*.tgz ${env.currentDir}/assets/${params.chartName}
              sudo mv ${env.currentDir}/temp/index.yaml ${env.currentDir}/
              sudo rm -rf ${env.currentDir}/temp
            """
            echo "Package - Completed."
          } catch (err) {
            echo "Package - Failed."
            currentBuild.result = 'FAILURE'
            error('Package stage failed.')
          }
        }
      }
    }
/*
    stage("Git commit and push") {
      steps {
        script {
          try {
            sh """
              git config --global user.name 'Jenkins Pipeline'
              git config --global user.email 'jenkins@localhost'
              git checkout -b ${env.gitBranch}
              git add .
              git commit -m 'Update from Jenkins-Pipeline'
            """
            withCredentials([gitUsernamePassword(credentialsId: "${env.gitCredentialId}", gitToolName: 'Default')]) {
              sh "git push origin ${env.gitBranch}"
            }
          } catch(err) {
            echo "GIT - Failed."
            currentBuild.result = 'FAILURE'
            error('Git stage failed.')
          }
        }
      }
    }

    stage("Helm install") {
      steps {
        script {
          try {
            sleep 60
            sh "sudo helm repo add ${env.chartRepoName} ${env.chartRepoUrl}"
            helmUpgrade()
          } catch (err) {
            retry(2) {
              sleep 60
              helmUpgrade()
            }
          }
        }
      }
    }
*/
  }
}
