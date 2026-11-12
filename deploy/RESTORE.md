# Backups and restore

## What is backed up

The `backup` service in `compose.prod.yaml` runs every night at **03:00
Morocco time** (`TZ=Africa/Casablanca`). It keeps the **last 14 nights** on
this VPS only, in `/opt/spine/backups` (root only, `chmod 700`):

```
/opt/spine/backups/
  backup.log              one line per run (OK or FAILED)
  2026-11-13/
    db.dump               the database (pg_dump custom format, compressed)
    files.tar.gz          the proof files (volume spine-files)
```

If the server was off at 03:00, the backup runs as soon as it is back.
A failed backup only writes a `FAILED` line. The app keeps running.

Note: the backups are on the same server as the app. If the server is lost,
the backups are lost too.

## Check it works

```bash
cd /opt/spine
tail -5 backups/backup.log
ls -l backups/
docker compose -f compose.prod.yaml logs --tail 20 backup
```

Back up right now (replaces today's folder):

```bash
docker compose -f compose.prod.yaml exec backup bash /usr/local/bin/spine-backup.sh now
```

## Restore one night

Run as root on the VPS. Pick the night:

```bash
cd /opt/spine
D=2026-11-13            # folder name in /opt/spine/backups
ls -l backups/$D
```

### 1. Stop the API and the backup (the database stays on)

```bash
docker compose -f compose.prod.yaml stop api backup
```

### 2. Safety copy of what is there now

```bash
mkdir -p /root/spine-before-restore && chmod 700 /root/spine-before-restore
docker compose -f compose.prod.yaml exec -T db sh -c \
  'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom' \
  > /root/spine-before-restore/db.dump
docker run --rm -v spine-files:/data:ro -v /root/spine-before-restore:/out \
  postgres:18 tar -czf /out/files.tar.gz -C /data .
```

### 3. Restore the database

First empty the database (drops ALL current tables, also ones newer than the
backup, so the next app start can run its migrations cleanly), then load the
backup.

```bash
docker compose -f compose.prod.yaml exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -c "drop schema public cascade; create schema public;"'
docker compose -f compose.prod.yaml exec -T db sh -c \
  'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner --exit-on-error' \
  < backups/$D/db.dump
```

### 4. Restore the proof files

This empties the `spine-files` volume, then unpacks the backup into it.

```bash
docker run --rm -v spine-files:/data -v /opt/spine/backups/$D:/bk:ro postgres:18 \
  sh -c 'find /data -mindepth 1 -delete && tar -xzf /bk/files.tar.gz -C /data'
```

### 5. Start again and check

```bash
docker compose -f compose.prod.yaml up -d --wait
curl -s http://127.0.0.1:8090/actuator/health/readiness
```

Log in on the app and open a few transactions and their proof files.

## Restore only one thing

- Only the database: do steps 1, 2, 3, 5.
- Only the files: do steps 1, 2, 4, 5.

## Look inside a backup without touching the app

```bash
docker run --rm -v /opt/spine/backups/$D:/bk:ro postgres:18 pg_restore --list /bk/db.dump | head
docker run --rm -v /opt/spine/backups/$D:/bk:ro postgres:18 tar -tzf /bk/files.tar.gz | head
```
