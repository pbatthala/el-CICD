/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Delete a project from OKD; i.e. the opposite of onboarding a project.
 */

def call(Map args) {
    def projectInfo = args.projectInfo

    stage('Remove project namespace environments') {
        def namespacesToDelete = projectInfo.nonProdNamespaces.values().join(' ')
        namespacesToDelete += projectInfo.sandboxNamespaces ? ' ' + projectInfo.sandboxNamespaces.join(' ') : ''
        namespacesToDelete += args.deleteRbacGroupJenkins ? " ${projectInfo.nonProdCicdNamespace}" : ''

        def msg = args.deleteRbacGroupJenkins ?
            "REMOVING ${projectInfo.rbacGroup} AUTOMATION SERVER AND ${projectInfo.id} NON-PROD ENVIRONMENT(S)" :
            "REMOVING ${projectInfo.id} NON-PROD ENVIRONMENTS"

        sh """
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

    stage('Delete GitHub deploy keys from GitHub') {
        onboardingUtils.deleteOldGithubKeys(projectInfo, true)
    }

    if (!args.deleteRbacGroupJenkins) {
        stage('Remove project build-to-dev pipelines from Jenkins') {
            def namespacesToDelete = projectInfo.nonProdNamespaces.values().join(' ')
            namespacesToDelete += projectInfo.sandboxNamespaces ? ' ' + projectInfo.sandboxNamespaces.join(' ') : ''
            namespacesToDelete += args.deleteRbacGroupJenkins ? " ${projectInfo.nonProdCicdNamespace}" : ''

            sh """
                ${pipelineUtils.shellEchoBanner("REMOVING PROJECT BUILD-TO-DEV PIPELINES FOR ${projectInfo.id}")}

                for BCS in \$(oc get bc -l projectid=${projectInfo.id} -n ${projectInfo.nonProdCicdNamespace} -o jsonpath='{.items[*].metadata.name}')
                do
                    while [ \$(oc get bc \${BCS} -n ${projectInfo.nonProdCicdNamespace} | grep \${BCS} | wc -l) -gt 0 ] ;
                    do
                        oc delete bc \${BCS} --ignore-not-found -n ${projectInfo.nonProdCicdNamespace}
                        sleep 5
                        ${shellEcho ''}
                    done
                done
            """
        }

        stage('Delete GitHub deploy keys from Jenkins') {
            def cicdRbacGroupJenkinsCredsUrls = verticalJenkinsCreationUtils.buildCicdJenkinsUrls(projectInfo)

            onboardingUtils.init()
            def authBearerCommand = """cat ${el.cicd.TEMPLATES_DIR}/AuthBearerHeader-template.txt | sed "s/%TOKEN%/\$(oc whoami -t)/" > ${el.cicd.TEMP_DIR}/AuthBearerHeader.txt"""
            sh """
                ${shellEcho 'Creating header file with auth token'}
                ${maskCommand(authBearerCommand)}
            """

            projectInfo.microServices.each { microService ->
                sh """
                    curl -ksS -X POST -H "`cat ${el.cicd.TEMP_DIR}/AuthBearerHeader.txt`" \
                        "${cicdRbacGroupJenkinsCredsUrls.updateProdCicdJenkinsCredsUrl}/${microService.gitSshPrivateKeyName}/doDelete"
                """
            }
        }
    }
}