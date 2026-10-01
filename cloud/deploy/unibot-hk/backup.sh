#!/bin/sh
# Daily copy of the relay's SQLite database, taken with SQLite's own backup
# (consistent under WAL, no need to stop the container), gzipped, kept for 30
# days. Run by unibot-relay-backup.timer; harmless to run by hand.
#
#   RELAY_DIR   where docker-compose.yml lives (default /opt/unibot/relay)
#   BACKUP_DIR  where copies go (default /opt/unibot/backups)
#   KEEP_DAYS   how many days of copies to keep (default 30)
#
# The copies are only useful together with CLOUD_SECRET from .env (the
# hashes and the encrypted identifiers are keyed by it), so .env is copied
# alongside — once, and again whenever it changes — with the same 0600 mode.
set -eu

relay=${RELAY_DIR:-/opt/unibot/relay}
out=${BACKUP_DIR:-/opt/unibot/backups}
keep=${KEEP_DAYS:-30}
stamp=$(date -u +%Y%m%d-%H%M%S)

umask 077
mkdir -p "$out"

# sqlite3 inside the relay image (python:3.12-slim has the module, not the CLI):
# python's sqlite3 backup API writes a consistent snapshot to a new file.
docker exec -i unibot-relay python - "$stamp" <<'EOF'
import sqlite3, sys
src = sqlite3.connect("/srv/unibot-cloud/data/cloud.db")
dst = sqlite3.connect(f"/srv/unibot-cloud/data/.backup-{sys.argv[1]}.db")
with dst:
    src.backup(dst)
dst.close(); src.close()
EOF
mv "$relay/data/.backup-$stamp.db" "$out/cloud-$stamp.db"
gzip -f "$out/cloud-$stamp.db"
chmod 600 "$out/cloud-$stamp.db.gz"

if [ -f "$relay/.env" ] && ! cmp -s "$relay/.env" "$out/env.latest" 2>/dev/null; then
	cp "$relay/.env" "$out/env-$stamp"
	cp "$relay/.env" "$out/env.latest"
fi

find "$out" -name 'cloud-*.db.gz' -mtime +"$keep" -delete
echo "backup: $out/cloud-$stamp.db.gz ($(du -h "$out/cloud-$stamp.db.gz" | cut -f1))"
