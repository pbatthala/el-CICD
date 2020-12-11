#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

source ${SCRIPTS_DIR}/credential-functions.sh

rm -rf ${SECRET_FILE_TEMP_DIR}
mkdir -p ${SECRET_FILE_TEMP_DIR}

echo
echo "Create ${EL_CICD_META_INFO_NAME} ConfigMap from ${CONFIG_REPOSITORY}/el-cicd-bootstrap.config"
oc delete --ignore-not-found cm ${EL_CICD_META_INFO_NAME}
oc create cm ${EL_CICD_META_INFO_NAME} --from-env-file=${CONFIG_REPOSITORY}/el-cicd-bootstrap.config -n ${EL_CICD_NON_PROD_MASTER_NAMEPACE}

_install_sealed_secrets ${EL_CICD_NON_PROD_MASTER_NAMEPACE}

echo
echo "Adding read only deploy key for el-CICD"
_push_github_public_ssh_deploy_key el-CICD ${EL_CICD_SSH_READ_ONLY_PUBLIC_DEPLOY_KEY_TITLE} ${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE} 

echo
echo "Adding read only deploy key for el-CICD-config"
_push_github_public_ssh_deploy_key el-CICD-config \
                                   ${EL_CICD_CONFIG_SSH_READ_ONLY_PUBLIC_DEPLOY_KEY_TITLE} \
                                   ${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE}


echo
CICD_ENVIRONMENTS="${DEV_ENV} $(echo $TEST_ENVS | sed 's/:/ /g')"
echo "Creating the image repository pull secrets for each environment: ${CICD_ENVIRONMENTS}"
for ENV in ${CICD_ENVIRONMENTS}
do
    _create_env_docker_registry_secret ${ENV} ${EL_CICD_NON_PROD_MASTER_NAMEPACE}
done

JENKINS_URL=$(oc get route jenkins -o jsonpath='{.spec.host}' -n ${EL_CICD_NON_PROD_MASTER_NAMEPACE})

echo
echo 'Pushing el-CICD git site wide READ/WRITE token to Jenkins'
_push_access_token_to_jenkins  ${JENKINS_URL} ${GIT_SITE_WIDE_ACCESS_TOKEN_ID} ${EL_CICD_GIT_REPO_ACCESS_TOKEN_FILE}

echo
echo 'Pushing el-CICD git READ ONLY private key to Jenkins'
_push_ssh_creds_to_jenkins ${JENKINS_URL} ${EL_CICD_READ_ONLY_GITHUB_PRIVATE_KEY_ID} ${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE}

echo
echo 'Pushing el-CICD-config git READ ONLY private key to Jenkins'
_push_ssh_creds_to_jenkins ${JENKINS_URL} ${EL_CICD_CONFIG_REPOSITORY_READ_ONLY_GITHUB_PRIVATE_KEY_ID} ${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE}

echo
echo "Pushing the image repository access tokens for each environment to Jenkins: ${CICD_ENVIRONMENTS}"
for ENV in ${CICD_ENVIRONMENTS}
do
    ACCESS_TOKEN_ID=$(eval echo \${${ENV}_IMAGE_REPO_ACCESS_TOKEN_ID})
    SECRET_TOKEN_FILE=$(eval echo \${${ENV}_PULL_TOKEN_FILE})

    echo
    echo "Pushing ${ENV} image repo access tokens per environment to Jenkins"
    _push_access_token_to_jenkins ${JENKINS_URL} ${ACCESS_TOKEN_ID} ${SECRET_TOKEN_FILE}
done

echo
echo "Run custom credentials script 'secrets-non-prod.sh' FOUND IN ${CONFIG_REPOSITORY_BOOTSTRAP}"
${CONFIG_REPOSITORY_BOOTSTRAP}/secrets-non-prod.sh

rm -rf ${SECRET_FILE_TEMP_DIR}

echo 
echo 'Non-prod Onboarding Server Credentials Script Complete'