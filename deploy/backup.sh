#!/usr/bin/env bash
# Nightly backup for Spine. Runs inside the "backup" service of compose.prod.yaml.
#
#   bash spine-backup.sh        -> loop: back up once a night at BACKUP_HOUR (local time, TZ)
#   bash spine-backup.sh now    -> back up once right now, then exit
#
# One folder per night: /backups/YYYY-MM-DD/{db.dump,files.tar.gz}
#   db.dump       pg_dump custom format (compressed), restore with pg_restore
#   files.tar.gz  the proof files volume (mounted read-only at /files)
# Keeps the newest BACKUP_KEEP nights and deletes older ones.
# A failed run only writes a log line. It never stops the app.

BACKUP_DIR=/backups
FILES_DIR=/files
BACKUP_HOUR=${BACKUP_HOUR:-3}
BACKUP_KEEP=${BACKUP_KEEP:-14}
LOG="$BACKUP_DIR/backup.log"

umask 077

log() {
  local line
  line="$(date '+%Y-%m-%d %H:%M:%S %Z') $*"
  echo "$line"
  echo "$line" >> "$LOG" 2>/dev/null || true
}

# Note: "set -e" is NOT used, because bash ignores it inside "if" / "||".
# Every step checks its own result instead.
run_backup() {
  local day tmp start
  start=$(date +%s)
  day=$(date +%F)
  tmp="$BACKUP_DIR/.$day.partial"

  rm -rf "$tmp" && mkdir -p "$tmp" || { log "cannot create $tmp"; return 1; }

  # PGHOST / PGUSER / PGPASSWORD / PGDATABASE come from the environment.
  pg_dump --format=custom --compress=6 --no-owner --file="$tmp/db.dump" \
    || { log "pg_dump failed"; return 1; }
  # Check the dump can be read back (lists its contents, writes nothing).
  pg_restore --list "$tmp/db.dump" > /dev/null \
    || { log "db.dump is not readable"; return 1; }

  tar -czf "$tmp/files.tar.gz" -C "$FILES_DIR" . \
    || { log "tar of $FILES_DIR failed"; return 1; }

  # Only a complete backup gets the real dated name (replaces an older one
  # from the same day, e.g. after a manual "now" run).
  rm -rf "${BACKUP_DIR:?}/$day" && mv "$tmp" "$BACKUP_DIR/$day" \
    || { log "cannot move $tmp to $BACKUP_DIR/$day"; return 1; }

  # Retention: keep the newest $BACKUP_KEEP dated folders.
  find "$BACKUP_DIR" -mindepth 1 -maxdepth 1 -type d -name '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]' \
    | sort | head -n -"$BACKUP_KEEP" | xargs -r rm -rf

  log "OK $day db=$(du -h "$BACKUP_DIR/$day/db.dump" | cut -f1)" \
      "files=$(du -h "$BACKUP_DIR/$day/files.tar.gz" | cut -f1)" \
      "took=$(( $(date +%s) - start ))s"
}

backup_once() {
  mkdir -p "$BACKUP_DIR" && chmod 700 "$BACKUP_DIR"
  if run_backup; then
    return 0
  fi
  rm -rf "$BACKUP_DIR/.$(date +%F).partial"
  log "FAILED $(date +%F) (see the lines above in: docker compose logs backup)"
  return 1
}

if [ "${1:-}" = now ]; then
  backup_once
  exit $?
fi

# Stop fast on "docker compose down / up" (SIGTERM), even while sleeping.
trap 'log "stopping"; exit 0' TERM INT

mkdir -p "$BACKUP_DIR" && chmod 700 "$BACKUP_DIR"
log "started: nightly at $(printf '%02d' "$BACKUP_HOUR"):00 $(date +%Z) ($TZ), keep $BACKUP_KEEP nights"

# Each minute: if it is past BACKUP_HOUR today and today has no backup yet
# (and no try yet today), back up. So a run missed while the container was
# down (e.g. a deploy at 03:00) still happens as soon as it is back.
tried=""
while true; do
  today=$(date +%F)
  hour=$((10#$(date +%H)))
  if [ "$hour" -ge "$BACKUP_HOUR" ] && [ ! -d "$BACKUP_DIR/$today" ] && [ "$tried" != "$today" ]; then
    tried=$today
    backup_once || true
  fi
  sleep 60 &
  wait $!
done
