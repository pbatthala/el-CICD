/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the build-to-dev pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/build-and-deploy-microservices-pipeline-template.
 *
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    projectInfo.deployToEnv = projectInfo.devEnv

    stage("Select sandbox, microservice, and branch") {
        def sandboxNamespacePrefix = "${projectInfo.id}-${el.cicd.SANDBOX_NAMESPACE_BADGE}"

        namespaces = []        
        (1..projectInfo.sandboxEnvs).each { i ->
            namespaces += "${sandboxNamespacePrefix}-${i}"
        }
        String deployableNamespaces = "${projectInfo.devNamespace}\n" + namespaces.join('\n')

        List inputs = [choice(name: 'deployableNamespaces', description: 'Build Namespace', choices: deployableNamespaces),
                       string(name: 'gitBranch',  defaultValue: projectInfo.gitBranch, description: 'The branch to build', trim: true),
                       booleanParam(name: 'buildAll', description: 'Build all microservices'),
                       booleanParam(name: 'recreateAll', description: 'Clean the environment of all resources before deploying')]

        inputs += projectInfo.microServices.collect { microService ->
            booleanParam(name: "${microService.name}", description: "${microService.active ? '' : el.cicd.INACTIVE}")
        }

        def cicdInfo = input(message: "Select namepsace and microservices to build to:", parameters: inputs)

        projectInfo.deployToNamespace = cicdInfo.deployableNamespaces
        projectInfo.gitBranch = cicdInfo.gitBranch
        projectInfo.recreateAll = cicdInfo.recreateAll
        projectInfo.microServices.each { it.build = cicdInfo.buildAll || cicdInfo[it.name] }
    }

    stage('Clean ${} if requested') {
        if (projectInfo.recreateAll) {
            deploymentUtils.removeAllMicroservices(projectInfo)
        }
    }

    def microServices = [[],[],[]]
    projectInfo.microServices.findAll { it.build }.eachWithIndex { microService, i ->
        microServices[i%3].add(microService)
    }

    if (microServices) {
        parallel(
            firstBucket: {
                stage("building first bucket of microservices to ${projectInfo.deployToNamespace}") {
                    microServices[0].each { microService ->
                        sh """
                            oc start-build ${microService.id}-build-to-dev \
                                --env DEPLOY_TO_NAMESPACE=${projectInfo.deployToNamespace} \
                                --env GIT_BRANCH=${projectInfo.gitBranch} \
                                --wait -n ${projectInfo.cicdMasterNamespace}
                        """
                    }
                }
            },
            secondBucket: {
                stage("building second bucket of microservices to ${projectInfo.deployToNamespace}") {
                    if (microServices[1]) {
                        microServices[1].each { microService ->
                            sh """
                                oc start-build ${microService.id}-build-to-dev \
                                    --env DEPLOY_TO_NAMESPACE=${projectInfo.deployToNamespace} \
                                    --env GIT_BRANCH=${projectInfo.gitBranch} \
                                    --wait -n ${projectInfo.cicdMasterNamespace}
                            """
                        }
                    }
                }
            },
            thirdBucket: {
                stage("building third bucket of microservices to ${projectInfo.deployToNamespace}") {
                    if (microServices[2]) {
                        microServices[2].each { microService ->
                            sh """
                                oc start-build ${microService.id}-build-to-dev \
                                    --env DEPLOY_TO_NAMESPACE=${projectInfo.deployToNamespace} \
                                    --env GIT_BRANCH=${projectInfo.gitBranch} \
                                    --wait -n ${projectInfo.cicdMasterNamespace}
                            """
                        }
                    }
                }
            }
        )
    }
}
