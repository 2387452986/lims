#!/bin/sh
set -eu

: "${BACKUP_PATH:?BACKUP_PATH is required}"
test -f "$BACKUP_PATH/lims.pgdump"
test -f "$BACKUP_PATH/files.tar.gz"
(cd "$BACKUP_PATH" && sha256sum -c SHA256SUMS)
pg_restore --list "$BACKUP_PATH/lims.pgdump" >/dev/null
tar -tzf "$BACKUP_PATH/files.tar.gz" >/dev/null
