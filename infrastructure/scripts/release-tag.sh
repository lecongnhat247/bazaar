#!/usr/bin/env bash
# Accept a commit tag or a release tag. Refuse the mutable latest tag.

reject_release_tag() {
  local tag="${1:-}"
  if [[ "$tag" =~ ^sha-[0-9a-f]{40}$ ]]; then
    return 0
  fi
  if [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    return 0
  fi
  echo "Refusing image tag '${tag:-<empty>}'. Use sha-<40 hex characters> or v<major>.<minor>.<patch>." >&2
  return 1
}
