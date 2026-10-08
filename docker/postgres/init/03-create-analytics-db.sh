#!/bin/bash
# Creates analytics-service's own role and database (database-per-service): the role
# $ANALYTICS_DB_USER owns analytics_db and has no access to patient_db beyond PUBLIC defaults.
#
# The postgres image runs /docker-entrypoint-initdb.d scripts ONLY when the data
# volume is empty (first start). An existing patient-db-data volume skips this
# script; run it once by hand instead, from the repo root, after
# `docker compose up -d` has (re)created the container with this mount and the
# ANALYTICS_DB_* variables from .env:
#
#   docker exec pms-postgres bash /docker-entrypoint-initdb.d/03-create-analytics-db.sh
#
# On Git Bash (Windows), prefix it with MSYS_NO_PATHCONV=1 so the container path
# is not rewritten into a Windows path. The password is read from the container's
# environment, so it never appears on a command line or in shell history.
#
# No `set -u`/`pipefail` here: the entrypoint sources non-executable scripts, and
# those options would leak into it. The entrypoint already runs with `set -e`, and
# the `:?` checks below abort initialization whether sourced or executed.

: "${ANALYTICS_DB_USER:?ANALYTICS_DB_USER must be set}"
: "${ANALYTICS_DB_PASSWORD:?ANALYTICS_DB_PASSWORD must be set}"

# Values are passed as psql variables and interpolated by psql itself
# (:"ident" quotes an identifier, :'literal' quotes a string), never spliced
# into the SQL text by the shell.
psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname postgres \
  -v analytics_user="$ANALYTICS_DB_USER" \
  -v analytics_password="$ANALYTICS_DB_PASSWORD" <<'EOSQL'
CREATE ROLE :"analytics_user" LOGIN PASSWORD :'analytics_password';
CREATE DATABASE analytics_db OWNER :"analytics_user";
EOSQL
