/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for bootstrapping CICD non-prod and prod environments
 * Should be called in order as written
 */

def verifyCicdJenkinsExists(def projectInfo, def isNonProd) {
    stage("Check if group's prod or non-prod CICD Jenkins exist") {
        def prodOrNonProd  = "${isNonProd ? 'NON-' : ''}PROD"
        pipelineUtils.echoBanner("VERIFY ${projectInfo.rbacGroup}'S ${prodOrNonProd} CICD JENKINS EXIST")

        sh """
            echo 'Verify group '${projectInfo.rbacGroup}' exists'
            oc get groups ${projectInfo.rbacGroup}
        """

        def cicdProjectsExist = sh(returnStdout: true, script: "oc get projects --no-headers --ignore-not-found ${projectInfo.cicdMasterNamespace}")

        if (!cicdProjectsExist) {
            def envs = isNonProd ? projectInfo.NON_PROD_ENVS : [projectInfo.PRE_PROD_ENV, projectInfo.PROD_ENV]
            createCicdNamespaceAndJenkins(projectInfo, envs)

            def pipelines = isNonProd ? el.getNonProdPipelines() : el.getProdPipelines()
            refreshSharedPipelines(projectInfo, pipelines)

            credentialUtils.copyElCicdMetaInfoBuildAndPullSecretsToGroupCicdServer(projectInfo, envs)

            waitUntilJenkinsIsReady(projectInfo)

            stage('Push Image Repo Pull Secrets to rbacGroup Jenkins') {
                credentialUtils.pushElCicdCredentialsToCicdServer(projectInfo, envs)
            }
        }
        else {
            echo "EXISTENCE CONFIRMED: ${prodOrNonProd} CICD JENKINS EXIST"
        }
    }
}

def createCicdNamespaceAndJenkins(def projectInfo, def envs) {
    stage('Creating CICD namespaces and rbacGroup Jenkins') {
        def nodeSelectors = el.cicd.CICD_MASTER_NODE_SELECTORS ? "--node-selector='${el.cicd.CICD_MASTER_NODE_SELECTORS }'" : ''

        sh """
            ${pipelineUtils.shellEchoBanner("CREATING ${projectInfo.cicdMasterNamespace} PROJECT AND JENKINS FOR THE ${projectInfo.rbacGroup} GROUP")}

            oc adm new-project ${projectInfo.cicdMasterNamespace} ${nodeSelectors}

            oc new-app jenkins-persistent -p MEMORY_LIMIT=${el.cicd.JENKINS_MEMORY_LIMIT} \
                                          -p VOLUME_CAPACITY=${el.cicd.JENKINS_VOLUME_CAPACITY} \
                                          -p DISABLE_ADMINISTRATIVE_MONITORS=${el.cicd.JENKINS_DISABLE_ADMINISTRATIVE_MONITORS} \
                                          -p JENKINS_IMAGE_STREAM_TAG=${el.cicd.JENKINS_IMAGE_STREAM}:latest \
                                          -e OVERRIDE_PV_PLUGINS_WITH_IMAGE_PLUGINS=true \
                                          -e JENKINS_JAVA_OVERRIDES=-D-XX:+UseCompressedOops \
                                          -e TRY_UPGRADE_IF_NO_MARKER=true \
                                          -e CASC_JENKINS_CONFIG=${el.cicd.JENKINS_CONTAINER_CONFIG_DIR}/${el.cicd.JENKINS_CASC_FILE} \
                                          -n ${projectInfo.cicdMasterNamespace}

            oc policy add-role-to-group admin ${projectInfo.rbacGroup} -n ${projectInfo.cicdMasterNamespace}
        """
    }
}

def waitUntilJenkinsIsReady(def projectInfo) {
    stage ('Wait until Jenkin is ready') {
        sh """
            ${pipelineUtils.shellEchoBanner("ENSURE ${projectInfo.cicdMasterNamespace} JENKINS IS READY (CAN TAKE A FEW MINUTES)")}

            set +x
            COUNTER=1
            for PROJECT in ${projectInfo.cicdMasterNamespace}
            do
                until
                    oc get pods -l name=jenkins -n \${PROJECT} | grep "1/1"
                do
                    printf "%0.s-" \$(seq 1 \${COUNTER})
                    echo
                    sleep 3
                    let COUNTER+=1
                done
            done

            echo "Jenkins up, sleep for 10 more seconds to make sure each servers REST api are ready"
            sleep 10
            set -x
        """
    }
}

def refreshSharedPipelines(def projectInfo, def pipelines) {
    stage('Refreshing shared pipelines') {
        def msg = ['CREATING SHARED PIPELINES:']
        msg.addAll(pipelines)
        pipelineUtils.echoBanner(msg)

        pipelines.each {
            writeFile file:"${el.cicd.BUILDCONFIGS_DIR}/${it}", text: libraryResource("buildconfigs/${it}")
        }

        dir (el.cicd.BUILDCONFIGS_DIR) {
            sh """
                for FILE in ${pipelines.join(' ')}
                do
                    oc process -f \${FILE} -p EL_CICD_META_INFO_NAME=${el.cicd.EL_CICD_META_INFO_NAME} -n ${projectInfo.cicdMasterNamespace} | \
                        oc apply -f - -n ${projectInfo.cicdMasterNamespace}
                done
            """
        }
    }
}
