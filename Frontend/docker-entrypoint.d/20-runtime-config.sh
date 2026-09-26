#!/bin/sh
set -eu
: "${FRONTEND_API_BASE:?FRONTEND_API_BASE must be set}"
: "${MINIO_PUBLIC_URL:?MINIO_PUBLIC_URL must be set}"
# jq encodes quotes, backslashes and control characters as JSON data, never code.
# Only the two allowlisted public settings enter this file.
jq -en --arg api "$FRONTEND_API_BASE" --arg media "$MINIO_PUBLIC_URL" '
  def valid: test("^https?://[^/@[:space:]]+(:[0-9]+)?(/[^?#[:space:]]*)?$");
  if ($api | valid) and ($media | valid)
  then {apiBase: ($api | sub("/+$"; "")), minioPublicUrl: ($media | sub("/+$"; ""))}
  else error("Invalid public frontend URL") end
' > /usr/share/nginx/html/runtime-config.json.tmp
mv /usr/share/nginx/html/runtime-config.json.tmp /usr/share/nginx/html/runtime-config.json
