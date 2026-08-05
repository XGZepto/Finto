#!/usr/bin/env bash
set -euo pipefail

database_url="${POSTGRES_URL_NON_POOLING:-${POSTGRES_URL:-${DATABASE_URL:-}}}"
if [[ -z "$database_url" ]]; then
  echo "POSTGRES_URL_NON_POOLING, POSTGRES_URL, or DATABASE_URL must be set" >&2
  exit 2
fi

# -X ignores a developer's ~/.psqlrc; ON_ERROR_STOP makes scripts atomic when
# paired with --single-transaction instead of continuing after the first error.
exec psql -X -v ON_ERROR_STOP=1 "$database_url" "$@"
