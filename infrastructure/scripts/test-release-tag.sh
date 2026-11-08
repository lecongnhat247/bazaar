#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck disable=SC1091
source "$root/infrastructure/scripts/release-tag.sh"

expect_reject() {
  if reject_release_tag "$1"; then
    echo "Expected the tag to be refused: ${1:-<empty>}" >&2
    exit 1
  fi
}

expect_accept() {
  if ! reject_release_tag "$1"; then
    echo "Expected the tag to be accepted: $1" >&2
    exit 1
  fi
}

expect_reject ""
expect_reject latest
expect_reject ci
expect_reject sha-abc
expect_reject v1
expect_reject v1.2
expect_reject "v1.2.3-rc1"
expect_accept "sha-$(printf 'a%.0s' {1..40})"
expect_accept "sha-$(printf '0123456789abcdef0123456789abcdef01234567')"
expect_accept v0.1.0
echo "release tags ok"
