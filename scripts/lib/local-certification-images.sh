#!/usr/bin/env bash

# Read-only image lock check for retained CDC focused diagnostics.

certification_validate_image_lock() {
  local directory="$1" lock="$2" expected actual

  [[ -f "$lock" && ! -L "$lock" && -f "$directory/image-lock.sha256" && \
    ! -L "$directory/image-lock.sha256" ]] || return 1
  simplematch_local_image_lock_validate_file "$lock" || return 1
  expected="$(cat "$directory/image-lock.sha256")" || return 1
  [[ "$expected" =~ ^[0-9a-f]{64}$ ]] || return 1
  actual="$(sha256sum "$lock" | awk '{print $1}')" || return 1
  [[ "$actual" == "$expected" ]]
}
