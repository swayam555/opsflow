#!/bin/bash
# One PostgreSQL server; each service that keeps data gets its own database and login.
set -euo pipefail
for svc in tickets orchestrator notifications deployments tracker; do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<SQL
CREATE ROLE ${svc}_svc LOGIN PASSWORD '${OPSFLOW_DB_PASSWORD}';
CREATE DATABASE ${svc} OWNER ${svc}_svc;
REVOKE ALL ON DATABASE ${svc} FROM PUBLIC;
SQL
done
