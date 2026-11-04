#!/usr/bin/env bash
# Clone or update the reference repos listed in reference/repos.txt at their pinned refs.
#   reference/sync.sh          default set
#   reference/sync.sh --all    also the opt-in ("!") entries
set -euo pipefail
dir="$(cd "$(dirname "$0")" && pwd)"
all=0; [[ "${1:-}" == "--all" ]] && all=1
grep -vE '^\s*(#|$)' "$dir/repos.txt" | while read -r name url ref; do
  if [[ "$name" == !* ]]; then
    name="${name#!}"
    if (( ! all )); then echo "skip   $name (opt-in, use --all)"; continue; fi
  fi
  dest="$dir/$name"
  if [[ ! -d "$dest/.git" ]]; then
    git init -q "$dest"
    git -C "$dest" remote add origin "$url"
  fi
  if git -C "$dest" fetch -q --depth 1 origin "$ref" 2>/dev/null; then
    target=FETCH_HEAD
  else
    git -C "$dest" fetch -q origin   # abbreviated SHAs can't be fetched directly
    target="$ref"
  fi
  git -C "$dest" -c advice.detachedHead=false checkout -q --force "$target"
  echo "ok     $name @ $ref ($(git -C "$dest" rev-parse --short HEAD))"
done
