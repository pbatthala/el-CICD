/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the refresh-credentials pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/efresh-credentials -pipeline-template.
 *
 */

def call(Map args) {
    dir (el.cicd.PROJECT_DEFS_DIR) {
        def allProjectFiles = findFiles(glob: "**/*.{json,js,yml,yaml}")
        def envs = args.isNonProd ? projectInfo.NON_PROD_ENVS : [projectInfo.PRE_PROD_ENV, projectInfo.PROD_ENV]

        allProjectFiles.each { projectFile ->
            def projectInfo
            def projectId = projectFile.name
            projectInfo = pipelineUtils.gatherProjectInfoStage(projectId)

            stage('Push el-CICD credentials') {
                credentialsUtils.pushElCicdCredentialsToCicdServer(projectInfo, envs)
            }

            stage('Delete old github public keys with curl') {
                credentialsUtils.deleteDeployKeysFromGithub(projectInfo)
            }

            stage('Create and push public key for each github repo to github with curl') {
                credentialsUtils.createAndPushPublicPrivateGithubRepoKeys(projectInfo)
            }
        }
    }
}