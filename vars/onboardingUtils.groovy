/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for onboading applications into the el-CICD framework
 */

def init() {
    pipelineUtils.echoBanner("COPYING ONBOARDING RESOURCES TO JENKINS AGENT")

    writeFile file:"${el.cicd.TEMPLATES_DIR}/githubSshCredentials-postfix.json", text: libraryResource('templates/githubSshCredentials-postfix.json')
    writeFile file:"${el.cicd.TEMPLATES_DIR}/githubSshCredentials-prefix.json", text: libraryResource('templates/githubSshCredentials-prefix.json')
    writeFile file:"${el.cicd.TEMPLATES_DIR}/githubWebhook-template.json", text: libraryResource('templates/githubWebhook-template.json')
    writeFile file:"${el.cicd.TEMPLATES_DIR}/jenkinsSshCredentials-postfix.xml", text: libraryResource('templates/jenkinsSshCredentials-postfix.xml')
    writeFile file:"${el.cicd.TEMPLATES_DIR}/jenkinsSshCredentials-prefix.xml", text: libraryResource('templates/jenkinsSshCredentials-prefix.xml')
    writeFile file:"${el.cicd.TEMPLATES_DIR}/jenkinsTokenCredentials-template.xml", text: libraryResource('templates/jenkinsTokenCredentials-template.xml')
}

def cleanStalePipelines(def projectInfo) {
    sh """
        ${pipelineUtils.shellEchoBanner("REMOVING STALE PIPELINES FOR ${projectInfo.id}, IF ANY")}

        BCS=\$(oc get bc --no-headers --ignore-not-found -l projectid=${projectInfo.id} -n ${projectInfo.cicdMasterNamespace} | awk '{print \$1}' | tr '\n' ' ')
        until [[ -z \${BCS} || -z \$(oc delete bc --ignore-not-found \${BCS} -n ${projectInfo.cicdMasterNamespace}) ]]
        do
            sleep 3
        done
    """
}

def cleanProjectNamespaces(def namespacesToDelete) {
    if (namespacesToDelete) {
        namespacesToDelete = namespacesToDelete.join(' ')
        sh """
            ${pipelineUtils.shellEchoBanner("REMOVING STALE NON-PROD ENVIRONMENT(S) FOR ${projectInfo.id}:", namespacesToDelete)}

            until [[ -z \$(oc delete projects --ignore-not-found ${namespacesToDelete}) ]]
            do
                sleep 3
            done
        """
    }
}

def createNamepace(def projectInfo, def namespace, def env, def nodeSelectors) {
    nodeSelectors = nodeSelectors ? "--node-selector='${nodeSelectors}'" : ''
    sh """
        if [[ -z \$(oc get projects --no-headers --ignore-not-found ${namespace}) ]]
        then
            oc adm new-project ${namespace} ${nodeSelectors}

            oc policy add-role-to-group admin ${projectInfo.rbacGroup} -n ${namespace}

            oc policy add-role-to-user edit system:serviceaccount:${projectInfo.cicdMasterNamespace}:jenkins -n ${namespace}

            oc adm policy add-cluster-role-to-user sealed-secrets-management \
                system:serviceaccount:${projectInfo.cicdMasterNamespace}:jenkins -n ${namespace}
            oc adm policy add-cluster-role-to-user secrets-unsealer \
                system:serviceaccount:${projectInfo.cicdMasterNamespace}:jenkins -n ${namespace}

            ${shellEcho ''}
        fi
    """
}

def applyResoureQuota(def projectInfo, def namespace, def resourceQuotaFile) {
    sh "oc delete quota --wait -l=projectid=${projectInfo.id} -n ${namespace}"

    if (resourceQuotaFile) {
        dir(el.cicd.RESOURCE_QUOTA_DIR) {
            sh """
                sleep 3

                oc apply -f ${resourceQuotaFile} -n ${namespace}
                oc label projectid=${projectInfo.id} -f ${resourceQuotaFile} -n ${namespace}

                ${shellEcho ''}
            """
        }
    }
}

def createNfsPersistentVolumes(def projectInfo, def isNonProd) {
    def pvNames = [:]
    if (projectInfo.nfsShares) {
        pipelineUtils.echoBanner("SETUP NFS PERSISTENT VOLUMES:", projectInfo.nfsShares.collect { it.claimName }.join(', '))

        dir(el.cicd.OKD_TEMPLATES_DIR) {
            projectInfo.nfsShares.each { nfsShare ->
                def envs = isNonProd ? projectInfo.nonProdEnvs : [projectInfo.prodEnv]
                envs.each { env ->
                    pvName = "${projectInfo.id}-${env}-${nfsShare.claimName}"
                    pvNames[(pvName)] = true
                    if ((isNonProd && env != projectInfo.prodEnv) || (!isNonProd && env == projectInfo.prodEnv)) {
                        createNfsShare(projectInfo, nfsShare, pvName, env)
                    }
                }
            }
        }
    }

    pipelineUtils.echoBanner("REMOVE UNNEEDED, AVAILABLE AND RELEASED ${projectInfo.id} NFS PERSISTENT VOLUMES, IF ANY")
    def releasedPvs = sh(returnStdout: true, script: """
        oc get pv -l projectid=test-cicd-extras --ignore-not-found | egrep 'Released|Available' | awk '{ print \$1 }'
    """).split('\n').findAll { it.trim() }

    releasedPvs.each { pvName ->
        if (!pvNames[pvName]) {
            sh "oc delete pv ${pvName}"
        }
    }
}

def createNfsShare(def projectInfo, def nfsShare, def nfsShareName, def env) {
    sh """
        oc process --local \
                   -f nfs-pv-template.yml \
                   -l projectid=${projectInfo.id}\
                   -p PV_NAME=${nfsShareName} \
                   -p CAPACITY=${nfsShare.capacity} \
                   -p ACCESS_MODE=${nfsShare.accessMode} \
                   -p NFS_EXPORT=${nfsShare.exportPath} \
                   -p NFS_SERVER=${nfsShare.server} \
                   -p CLAIM_NAME=${nfsShare.claimName} \
                   -p NAMESPACE=${projectInfo.id}-${env} \
            | oc apply -f -

        ${shellEcho ''}
    """
}
