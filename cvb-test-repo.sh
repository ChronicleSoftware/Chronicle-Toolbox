#!/usr/bin/env bash
set -euo pipefail

BASE_DIR="$HOME/Downloads/chronicle-test-repos"
rm -rf "${BASE_DIR}"
mkdir -p "${BASE_DIR}"

REPOS=(repo1 repo2 repo3)

for NAME in "${REPOS[@]}"; do
  git init -b main "${BASE_DIR}/${NAME}" >/dev/null
  pushd "${BASE_DIR}/${NAME}" >/dev/null
  echo "# ${NAME^}" > README.md
  git add README.md && git commit -m "init ${NAME}" >/dev/null
  git checkout -b develop >/dev/null
  echo "dev work" > DEV.md
  git add DEV.md && git commit -m "dev ${NAME}" >/dev/null
  popd >/dev/null
done

# Emit Windows‐style absolute paths
YAML_PATH="${BASE_DIR}/repos.yaml"
echo "repos:" > "${YAML_PATH}"
pushd "${BASE_DIR}" >/dev/null
for NAME in "${REPOS[@]}"; do
  WIN_PATH="$(pwd -W)/${NAME}"
  echo "  - ${WIN_PATH}" >> "${YAML_PATH}"
done
popd >/dev/null

echo "Done—repos.yaml written with Windows paths in ${YAML_PATH}"
