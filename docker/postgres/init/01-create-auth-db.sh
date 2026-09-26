#!/bin/bash
# Creates auth-service's own role and database (database-per-service): the role
# $AUTH_DB_USER owns auth_db and has no access to patient_db beyond PUBLIC defaults.
#
# The postgres image runs /docker-entrypoint-initdb.d scripts ONLY when the data
# volume is empty (first start). An existing patient-db-data volume skips this
# script; create the role and database once by hand instead, from the repo root
# (you will be prompted for the new role's password; nothing lands in shell history):
#
#   docker exec -it pms-postgres psql -U "$POSTGRES_USER" -d postgres \
#     -c "CREATE ROLE <auth_db_user> LOGIN" -c "\password <auth_db_user>" \
#     -c "CREATE DATABASE auth_db OWNER <auth_db_user>"
#
# Replace <auth_db_user> with the AUTH_DB_USER value from .env and enter
# AUTH_DB_PASSWORD at the prompt.
#
# No `set -u`/`pipefail` here: the entrypoint sources non-executable scripts, and
# those options would leak into it. The entrypoint already runs with `set -e`, and
# the `:?` checks below abort initialization whether sourced or executed.

: "${AUTH_DB_USER:?AUTH_DB_USER must be set}"
: "${AUTH_DB_PASSWORD:?AUTH_DB_PASSWORD must be set}"

# Values are passed as psql variables and interpolated by psql itself
# (:"ident" quotes an identifier, :'literal' quotes a string), never spliced
# into the SQL text by the shell.
psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname postgres \
  -v auth_user="$AUTH_DB_USER" \
  -v auth_password="$AUTH_DB_PASSWORD" <<'EOSQL'
CREATE ROLE :"auth_user" LOGIN PASSWORD :'auth_password';
CREATE DATABASE auth_db OWNER :"auth_user";
EOSQL
