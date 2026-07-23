#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
status=0

heading_slug() {
  local heading="$1"

  printf '%s' "$heading" \
    | tr '[:upper:]' '[:lower:]' \
    | sed -E -e 's/[^[:alnum:][:space:]-]//g' -e 's/[[:space:]]+/-/g' -e 's/^-+//' -e 's/-+$//'
}

contains_heading() {
  local markdown_file="$1"
  local expected_fragment="$2"
  local heading

  while IFS= read -r heading; do
    if [[ "$(heading_slug "$heading")" == "$expected_fragment" ]]; then
      return 0
    fi
  done < <(sed -nE 's/^#{1,6}[[:space:]]+//p' "$markdown_file")

  return 1
}

extract_link_destinations() {
  local markdown_file="$1"
  local line
  local remainder
  local match
  local link_pattern='\]\(([^)]*)\)'

  while IFS= read -r line || [[ -n "$line" ]]; do
    remainder="$line"
    while [[ "$remainder" =~ $link_pattern ]]; do
      match="${BASH_REMATCH[0]}"
      printf '%s\n' "${BASH_REMATCH[1]}"
      remainder="${remainder#*"$match"}"
    done
  done < "$markdown_file"
}

declare -A visited_files=()

check_markdown_file() {
  local markdown_file="$1"
  local absolute_file
  local source_dir
  local destination
  local link_target
  local fragment
  local candidate

  if [[ ! -f "$markdown_file" ]]; then
    echo "Missing entry: $markdown_file" >&2
    status=1
    return
  fi

  absolute_file="$(cd -- "$(dirname -- "$markdown_file")" && pwd)/$(basename -- "$markdown_file")"
  if [[ -n "${visited_files[$absolute_file]:-}" ]]; then
    return
  fi
  visited_files["$absolute_file"]=1
  source_dir="$(dirname -- "$absolute_file")"

  while IFS= read -r destination; do
    destination="${destination#<}"
    destination="${destination%>}"
    destination="${destination%%[[:space:]]*}"
    [[ -z "$destination" ]] && continue

    if [[ "$destination" =~ ^[A-Za-z][A-Za-z0-9+.-]*: ]] || [[ "$destination" == //* ]]; then
      continue
    fi

    link_target="$destination"
    fragment=""
    if [[ "$destination" == *'#'* ]]; then
      link_target="${destination%%#*}"
      fragment="${destination#*#}"
    fi

    if [[ -z "$link_target" ]]; then
      candidate="$absolute_file"
    elif [[ "$link_target" == /* ]]; then
      candidate="$repo_root/${link_target#/}"
    else
      candidate="$source_dir/$link_target"
    fi

    if [[ ! -f "$candidate" ]]; then
      echo "Missing target in $absolute_file: $destination" >&2
      status=1
      continue
    fi

    if [[ -n "$fragment" ]] && ! contains_heading "$candidate" "$fragment"; then
      echo "Missing heading in $absolute_file: $destination" >&2
      status=1
      continue
    fi

    if [[ "$candidate" == *.md ]]; then
      check_markdown_file "$candidate"
    fi
  done < <(extract_link_destinations "$absolute_file")
}

if [[ $# -eq 0 ]]; then
  set -- "$repo_root/README.md" "$repo_root/services/docs/README.md"
fi

for entry_file in "$@"; do
  check_markdown_file "$entry_file"
done

exit "$status"
