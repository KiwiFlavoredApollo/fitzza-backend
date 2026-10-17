#!/usr/bin/env bash
# Publish linux/arm64 service images tagged with the commit SHA.
# A service whose image inputs did not change since an earlier build is retagged
# instead of rebuilt, which keeps CodeBuild minutes and ECR storage inside budget.
set -euo pipefail

: "${ECR_REGISTRY:?ECR_REGISTRY is required}"
: "${ECR_REPOSITORY_PREFIX:?ECR_REPOSITORY_PREFIX is required}"
: "${IMAGE_TAG:?IMAGE_TAG is required}"

JAVA_SERVICES=(config-service discovery-service gateway-service user-service product-service order-service community-service review-service notification-service)
PYTHON_SERVICES=(recommendation-service virtual-tryon-service)
ACCEPTED_MEDIA_TYPES=(
  application/vnd.docker.distribution.manifest.v2+json
  application/vnd.docker.distribution.manifest.list.v2+json
  application/vnd.oci.image.manifest.v1+json
  application/vnd.oci.image.index.v1+json
)
REPORT_FILE="${REPORT_FILE:-image-publish-report.json}"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

# Hash only the files that end up in the image so test-only edits do not trigger rebuilds.
source_hash() {
  local service="$1" kind="$2"
  local -a inputs
  if [[ "${kind}" == java ]]; then
    inputs=("${service}" build.gradle settings.gradle gradle gradlew Dockerfile.java .dockerignore)
  else
    inputs=("${service}/requirements.txt" "${service}/app" Dockerfile.python .dockerignore)
  fi
  find "${inputs[@]}" -type f \
    ! -path '*/build/*' ! -path '*/.gradle/*' ! -path '*/__pycache__/*' ! -path '*/src/test/*' \
    -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -c1-32
}

image_exists() {
  local repository="$1" tag="$2"
  aws ecr describe-images --repository-name "${repository}" --image-ids "imageTag=${tag}" >/dev/null 2>&1
}

add_tag() {
  local repository="$1" source_tag="$2" new_tag="$3"
  aws ecr batch-get-image --repository-name "${repository}" --image-ids "imageTag=${source_tag}" \
    --accepted-media-types "${ACCEPTED_MEDIA_TYPES[@]}" --output json >"${WORK_DIR}/image.json"
  python3 - "${WORK_DIR}/image.json" "${WORK_DIR}/manifest.json" "${WORK_DIR}/media-type" <<'PY'
import json
import sys
from pathlib import Path

image = json.loads(Path(sys.argv[1]).read_text())["images"][0]
Path(sys.argv[2]).write_bytes(image["imageManifest"].encode("utf-8"))
Path(sys.argv[3]).write_text(image["imageManifestMediaType"])
PY
  aws ecr put-image --repository-name "${repository}" --image-tag "${new_tag}" \
    --image-manifest "file://${WORK_DIR}/manifest.json" \
    --image-manifest-media-type "$(cat "${WORK_DIR}/media-type")" >/dev/null
}

build_image() {
  local service="$1" kind="$2" source_tag="$3"
  local image="${ECR_REGISTRY}/${ECR_REPOSITORY_PREFIX}/${service}"
  local -a args=(--platform linux/arm64 --provenance=false --sbom=false --push
    --tag "${image}:${IMAGE_TAG}" --tag "${image}:${source_tag}")
  if [[ "${kind}" == java ]]; then
    args+=(--build-arg "MODULE=${service}" --file Dockerfile.java)
  else
    args+=(--build-arg "SERVICE_DIR=${service}" --file Dockerfile.python)
  fi
  docker buildx build "${args[@]}" .
}

image_architecture() {
  docker buildx imagetools inspect "$1" --format '{{json .Image}}' | python3 -c '
import json, sys
image = json.load(sys.stdin)
if "architecture" in image:
    print(image["architecture"])
else:
    print(",".join(sorted({item["architecture"] for item in image.values()})))
'
}

publish_service() {
  local service="$1" kind="$2"
  local repository="${ECR_REPOSITORY_PREFIX}/${service}"
  local source_tag action
  source_tag="src-$(source_hash "${service}" "${kind}")"

  if image_exists "${repository}" "${IMAGE_TAG}"; then
    action=existing
  elif image_exists "${repository}" "${source_tag}"; then
    add_tag "${repository}" "${source_tag}" "${IMAGE_TAG}"
    action=retagged
  else
    build_image "${service}" "${kind}" "${source_tag}"
    action=built
  fi

  local digest architecture
  digest="$(aws ecr describe-images --repository-name "${repository}" --image-ids "imageTag=${IMAGE_TAG}" \
    --query 'imageDetails[0].imageDigest' --output text)"
  architecture="$(image_architecture "${ECR_REGISTRY}/${repository}:${IMAGE_TAG}")"
  if [[ "${architecture}" != arm64 ]]; then
    echo "${service}: expected arm64 image but found ${architecture}" >&2
    return 1
  fi
  echo "${service}: ${action} ${IMAGE_TAG} ${digest} ${architecture}"
  printf '{"service":"%s","action":"%s","tag":"%s","sourceTag":"%s","digest":"%s","architecture":"%s"}\n' \
    "${service}" "${action}" "${IMAGE_TAG}" "${source_tag}" "${digest}" "${architecture}" >>"${WORK_DIR}/report.jsonl"
}

for service in "${JAVA_SERVICES[@]}"; do
  publish_service "${service}" java
done
for service in "${PYTHON_SERVICES[@]}"; do
  publish_service "${service}" python
done

python3 - "${WORK_DIR}/report.jsonl" "${REPORT_FILE}" <<'PY'
import json
import sys
from pathlib import Path

rows = [json.loads(line) for line in Path(sys.argv[1]).read_text().splitlines()]
Path(sys.argv[2]).write_text(json.dumps(rows, indent=2) + "\n")
PY
