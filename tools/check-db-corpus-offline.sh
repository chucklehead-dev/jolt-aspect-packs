#!/usr/bin/env bash
# Run only after dependencies have been provisioned. No native installation.
set -euo pipefail

case "${1:-}" in
  bb) command=(bb --config corpus-bb.edn -m) ;;
  jolt) command=(jolt -M:db-corpus-offline:db-corpus-command) ;;
  jvm) command=(clojure -M:db-corpus-offline:db-corpus-command) ;;
  *) echo 'usage: check-db-corpus-offline.sh bb|jolt|jvm' >&2; exit 2 ;;
esac

case "$(uname -s)" in
  Linux) isolation=(unshare --user --map-root-user --net) ;;
  Darwin) isolation=(/usr/bin/sandbox-exec -p '(version 1) (allow default) (deny network*)') ;;
  *) echo 'unsupported isolation host' >&2; exit 2 ;;
esac

export HEGEL_LIBHEGEL_LIBRARY=deliberately-missing-db-corpus-engine
"${command[@]}" jolt.aspect-packs.db.network-probe open
"${isolation[@]}" "${command[@]}" jolt.aspect-packs.db.network-probe blocked
"${isolation[@]}" "${command[@]}" jolt.aspect-packs.db.corpus-offline-runner
"${command[@]}" jolt.aspect-packs.db.network-probe open
