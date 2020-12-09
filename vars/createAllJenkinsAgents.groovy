/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the create-all-jenkins-agents pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/create-all-jenkins-agents-pipeline-template.
 *
 */

def call(Map args) {
    el.initializeStage()

    def agentDockerfiles

    stage('Update Jenkins') {
        if (args.updateJenkinsImage) {
            pipelineUtils.echoBanner('UPDATE JENKINS IMAGE')

            sh "oc import-image jenkins -n openshift"
        }
        else {
            pipelineUtils.echoBanner('SKIPPING UPDATE JENKINS IMAGE')
        }
    }

    stage('Create All Agents') {
        def agentNames = el.cicd.JENKINS_AGENT_NAMES.tokenize(':')
        agentNames.add(0, el.cicd.JENKINS_AGENT_DEFAULT)

        pipelineUtils.echoBanner('CREATE JENKINS AGENTS:', agentNames.join(', '))

        dir(el.cicd.AGENTS_DIR) {
            agentNames.each { agentName ->
                sh """
                    oc delete --ignore-not-found bc ${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${agentName} -n openshift
                    sleep 5
                    cat ./Dockerfile.${agentName} | oc new-build --name ${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${agentName} -D - -n openshift
                    sleep 10

                    oc logs -f bc/${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${agentName} -n openshift
                """
            }
        }
    }
}
