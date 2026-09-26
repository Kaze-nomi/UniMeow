#!/bin/sh
set -eu
jq -n --arg api "${FRONTEND_API_BASE:?FRONTEND_API_BASE is required}" \
  --arg media "${MINIO_PUBLIC_URL:?MINIO_PUBLIC_URL is required}" \
  '{apiBase: $api, minioPublicUrl: $media}' > /usr/share/nginx/html/runtime-config.json.tmp
mv /usr/share/nginx/html/runtime-config.json.tmp /usr/share/nginx/html/runtime-config.json
