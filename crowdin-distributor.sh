#!/usr/bin/env bash
set -e

# For snapshots, please specify the full version (with date and time)
cdist_version=""
cdist_path_version="$cdist_version"

if [ -n "${cdist_version#*-}" ]; then
  cdist_path_version="${cdist_version%%-*}-SNAPSHOT"
fi
url="https://maven.enginehub.org/repo/org/enginehub/crowdin/crowdin-distributor/$cdist_path_version/crowdin-distributor-$cdist_version-bundle.zip"
[ -d ./build ] || mkdir ./build
curl "$url" >./build/cdist.zip
(cd ./build && unzip -o cdist.zip)

# CROWDIN_DISTRIBUTOR_TOKEN is set by CI
export CROWDIN_DISTRIBUTOR_PROJECT_ID=""
export CROWDIN_DISTRIBUTOR_MODULE=""
export CROWDIN_DISTRIBUTOR_ARTIFACTORY_URL=""
export CROWDIN_DISTRIBUTOR_ARTIFACTORY_REPO=""
## Full path to the source file, will be uploaded to crowdin, must already have uploaded at least once (will not create a new file)
export CROWDIN_DISTRIBUTOR_SOURCE_FILE=""
# Artifactory Creds & Build Number is set by CI
export CROWDIN_DISTRIBUTOR_OPTS=""
"./build/crowdin-distributor-$cdist_path_version/bin/crowdin-distributor"
