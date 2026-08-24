#!/bin/sh
set -eu

: "${BACKUP_DIR:?BACKUP_DIR is required}"
: "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}"

case "$BACKUP_DIR" in
  /|"$HOME"|"") echo "Refusing unsafe BACKUP_DIR" >&2; exit 2 ;;
esac

timestamp=$(date -u +%Y%m%dT%H%M%SZ)
destination="$BACKUP_DIR/$timestamp"
mkdir -p "$destination"

compose() {
  if docker compose version >/dev/null 2>&1; then docker compose "$@"; else docker-compose "$@"; fi
}

compose -f deploy/compose.yml exec -T postgres pg_dump -U lims -d lims -Fc > "$destination/lims.pgdump"
docker run --rm -v lims_file_store:/source:ro -v "$destination:/backup" alpine:3.23 \
  tar -C /source -czf /backup/files.tar.gz .
sha256sum "$destination/lims.pgdump" "$destination/files.tar.gz" > "$destination/SHA256SUMS"
printf '%s\n' "$timestamp" > "$destination/recovery-point.txt"
(cd "$destination" && sha256sum -c SHA256SUMS)
compose -f deploy/compose.yml exec -T postgres psql -U lims -d lims -v ON_ERROR_STOP=1 -c \
  "INSERT INTO operational_marker(marker_key,occurred_at,details) VALUES ('LAST_PAIRED_BACKUP',now(),jsonb_build_object('recoveryPoint','$timestamp')) ON CONFLICT(marker_key) DO UPDATE SET occurred_at=excluded.occurred_at,details=excluded.details"
