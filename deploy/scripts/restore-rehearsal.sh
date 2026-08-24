#!/bin/sh
set -eu

: "${BACKUP_PATH:?BACKUP_PATH is required}"
test -f "$BACKUP_PATH/lims.pgdump"
test -f "$BACKUP_PATH/files.tar.gz"

stamp=$(date -u +%Y%m%d%H%M%S)
container="lims-restore-rehearsal-$stamp"
volume="lims_restore_rehearsal_$stamp"
password="restore-$stamp"

cleanup() {
  docker rm -f -v "$container" >/dev/null 2>&1 || true
  docker volume rm "$volume" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

(cd "$BACKUP_PATH" && sha256sum -c SHA256SUMS)
docker run -d --name "$container" -e POSTGRES_USER=lims -e POSTGRES_PASSWORD="$password" -e POSTGRES_DB=lims_restore postgres:18-alpine >/dev/null
for attempt in $(seq 1 60); do
  if docker exec "$container" pg_isready -U lims -d lims_restore >/dev/null 2>&1; then break; fi
  test "$attempt" -lt 60 || { echo "Restore database did not become ready" >&2; exit 1; }
  sleep 1
done
docker cp "$BACKUP_PATH/lims.pgdump" "$container:/tmp/lims.pgdump"
docker exec "$container" pg_restore -U lims -d lims_restore --clean --if-exists /tmp/lims.pgdump
docker exec "$container" psql -U lims -d lims_restore -v ON_ERROR_STOP=1 -c \
  "SELECT version,success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1; SELECT count(*) AS audit_rows FROM audit_event;"
docker volume create "$volume" >/dev/null
docker run --rm -v "$volume:/restore" -v "$BACKUP_PATH:/backup:ro" alpine:3.23 tar -C /restore -xzf /backup/files.tar.gz
docker run --rm -v "$volume:/restore:ro" alpine:3.23 sh -c 'find /restore -type f -print | head -n 20; test -d /restore'
echo "Restore rehearsal passed for $BACKUP_PATH"
