/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Delete a project from OKD; i.e. the opposite of onboarding a project.
 */

def call(Map args) {

    elCicdCommons.initialize()

    elCicdCommons.cloneElCicdRepo()

    def projectInfo = pipelineUtils.gatherProjectInfoStage(args.projectId)

    stage('Remove stale namespace environments and pipelines if necessary') {
        def namespacesToDelete = projectInfo.nonProdNamespaces.values().join(' ')
        namespacesToDelete += projectInfo.sandboxNamespaces ? ' ' + projectInfo.sandboxNamespaces.join(' ') : ''
        namespacesToDelete += args.deleteRbacGroupJenkins ? " ${projectInfo.nonProdCicdNamespace}" : ''

        sh """
            ${pipelineUtils.shellEchoBanner("REMOVING PROJECT PIPELINES FOR ${projectInfo.id}, IF ANY")}

            if [[ '${args.deleteRbacGroupJenkins}' != 'true' ]]
            then
                for BCS in \$(oc get bc -l projectid=${projectInfo.id} -n devops-cicd-non-prod -o jsonpath='{.items[*].metadata.name}')
                do
                    while [ \$(oc get bc \${BCS} -n ${projectInfo.nonProdCicdNamespace} | grep \${BCS} | wc -l) -gt 0 ] ;
                    do
                        oc delete bc \${BCS} --ignore-not-found -n ${projectInfo.nonProdCicdNamespace}
                        sleep 5
                        ${shellEcho ''}
                    done
                done
            fi

            ${pipelineUtils.shellEchoBanner("REMOVING PROJECT NON-PROD ENVIRONMENT(S) FOR ${projectInfo.id}")}

            oc delete project ${namespacesToDelete} || true

            NAMESPACES_TO_DELETE='${namespacesToDelete}'
            for NAMESPACE in \${NAMESPACES_TO_DELETE}
            do
                until
                    !(oc project \${NAMESPACE} > /dev/null 2>&1)
                do
                    sleep 1
                done
            done
        """
    }

    stage('Delete old github public keys with curl') {
        onboardingUtils.deleteOldGithubKeys(projectInfo, true)
    }
}