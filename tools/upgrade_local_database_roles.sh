#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 2 ]]; then
  printf 'Usage: bash tools/upgrade_local_database_roles.sh <identity-bootstrap-user> <catalog-bootstrap-user>\n' >&2
  exit 2
fi

migrate_database() {
  local service="$1"
  local legacy_user="$2"
  docker compose exec --no-TTY "$service" sh -ec '
    psql --set=ON_ERROR_STOP=1 \
      --set=legacy_user="$1" \
      --username "$1" \
      --dbname "$POSTGRES_DB" \
      --file /docker-entrypoint-initdb.d/10-restrict-application-role.sql
  ' sh "$legacy_user"
  printf '%s database roles and object ownership upgraded\n' "$service"
}

migrate_database identity-db "$1"
migrate_database catalog-db "$2"
