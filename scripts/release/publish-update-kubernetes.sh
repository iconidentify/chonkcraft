#!/usr/bin/env bash
# Publishes an already-built update directory to the retained update volume.
# The immutable JAR lands first and the single authenticated catalog lands last.

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source_dir="${CHONKCRAFT_UPDATE_OUTPUT:-${root}/desktop/target/dist/update}"
namespace="${CHONKCRAFT_UPDATE_NAMESPACE:-chonkcraft-updates}"
selector="app.kubernetes.io/name=chonkcraft-updates"

catalog="${source_dir}/latest.properties"
if [[ ! -s "$catalog" ]]; then
  echo "Signed update catalog not found: $catalog" >&2
  exit 1
fi
mapfile -t jars < <(find "${source_dir}/releases" -type f -name 'chonkcraft-game-*.jar' | sort)
if [[ "${#jars[@]}" -ne 1 ]]; then
  echo "Expected exactly one immutable game JAR, found ${#jars[@]}." >&2
  exit 1
fi
jar="${jars[0]}"
mapfile -t histories < <(find "${source_dir}/releases/release-notes" -type f \
  -name 'chonkcraft-release-notes-*.properties' | sort)
if [[ "${#histories[@]}" -ne 1 ]]; then
  echo "Expected exactly one immutable release-note history, found ${#histories[@]}." >&2
  exit 1
fi
history="${histories[0]}"
relative="${jar#${source_dir}/}"
history_relative="${history#${source_dir}/}"
remote_root="/usr/share/nginx/html"
remote_jar="${remote_root}/${relative}"
remote_history="${remote_root}/${history_relative}"
pod="$(kubectl -n "$namespace" get pod -l "$selector" \
  --field-selector=status.phase=Running -o jsonpath='{.items[0].metadata.name}')"
if [[ -z "$pod" ]]; then
  echo "No running update server pod was found." >&2
  exit 1
fi

expected="$(shasum -a 256 "$jar" 2>/dev/null | awk '{print $1}' || \
  sha256sum "$jar" | awk '{print $1}')"
expected_history="$(shasum -a 256 "$history" 2>/dev/null | awk '{print $1}' || \
  sha256sum "$history" | awk '{print $1}')"
incoming="${remote_root}/.incoming-${GITHUB_RUN_ID:-$$}-${GITHUB_RUN_ATTEMPT:-0}"
trap 'kubectl -n "$namespace" exec "$pod" -- rm -rf "$incoming" >/dev/null 2>&1 || true' EXIT

kubectl -n "$namespace" exec "$pod" -- mkdir -p "$incoming" \
  "$(dirname "$remote_jar")" "$(dirname "$remote_history")"
kubectl -n "$namespace" cp "$jar" "$pod:$incoming/game.jar"
kubectl -n "$namespace" cp "$history" "$pod:$incoming/release-notes.properties"
kubectl -n "$namespace" cp "$catalog" "$pod:$incoming/latest.properties"
actual="$(kubectl -n "$namespace" exec "$pod" -- sha256sum "$incoming/game.jar" \
  | awk '{print $1}')"
if [[ "$actual" != "$expected" ]]; then
  echo "The uploaded JAR failed its SHA-256 check." >&2
  exit 1
fi
actual_history="$(kubectl -n "$namespace" exec "$pod" -- \
  sha256sum "$incoming/release-notes.properties" | awk '{print $1}')"
if [[ "$actual_history" != "$expected_history" ]]; then
  echo "The uploaded release-note history failed its SHA-256 check." >&2
  exit 1
fi

kubectl -n "$namespace" exec "$pod" -- sh -eu -c \
  'jar="$1"; notes="$2"; incoming="$3"; mkdir -p "$(dirname "$jar")" "$(dirname "$notes")"; if [ -f "$jar" ]; then cmp -s "$incoming/game.jar" "$jar"; else mv "$incoming/game.jar" "$jar"; fi; if [ -f "$notes" ]; then cmp -s "$incoming/release-notes.properties" "$notes"; else mv "$incoming/release-notes.properties" "$notes"; fi; mv "$incoming/latest.properties" /usr/share/nginx/html/latest.properties; rm -f "$incoming/game.jar" "$incoming/release-notes.properties"; rmdir "$incoming"' \
  sh "$remote_jar" "$remote_history" "$incoming"
trap - EXIT

curl --fail --silent --show-error --retry 12 --retry-all-errors --retry-delay 5 \
  https://updates.chonkbase.net/latest.properties >/dev/null
curl --fail --silent --show-error --retry 12 --retry-all-errors --retry-delay 5 \
  "https://updates.chonkbase.net/${relative}" -o /dev/null
curl --fail --silent --show-error --retry 12 --retry-all-errors --retry-delay 5 \
  "https://updates.chonkbase.net/${history_relative}" -o /dev/null
printf 'Published %s\n' "$relative"
