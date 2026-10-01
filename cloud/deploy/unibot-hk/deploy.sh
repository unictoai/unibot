#!/bin/sh
# Ship the relay to unibot-hk and (re)start it. Run from a checkout, on the
# machine that has the `unibot-hk` SSH alias:
#
#   cloud/deploy/unibot-hk/deploy.sh            # sync, build, restart, health check
#   cloud/deploy/unibot-hk/deploy.sh --no-build # sync only what changed, no restart
#   SKIP_CADDY=1 cloud/deploy/unibot-hk/deploy.sh # everything but the Caddy site
#
# What it does, idempotently:
#   1. rsync cloud/ → /opt/unibot/relay/src (tests, caches and .env left out)
#   2. put this directory's compose file, backup script and systemd units in
#      place; enable the backup timer
#   3. drop cloud.unibot.cn.caddy into the showcase's sites.d/ and reload Caddy
#      (only when the file changed)
#   4. docker compose up -d --build, then curl /healthz through the public name
#
# It never writes .env: secrets are made once on the box (README.md) and stay
# there. The first run stops with instructions when .env is missing.
set -eu

host=${RELAY_HOST:-unibot-hk}
remote=${RELAY_DIR:-/opt/unibot/relay}
showcase=${SHOWCASE_DIR:-/opt/unibot/unibot/demo/showcase}
public=${PUBLIC_BASE:-https://cloud.unibot.cn}
here=$(cd "$(dirname "$0")" && pwd)
cloud=$(cd "$here/../.." && pwd)
build=1
[ "${1:-}" = "--no-build" ] && build=

ssh "$host" "sudo mkdir -p $remote/src $remote/data /opt/unibot/backups && sudo chown -R \$(id -u):\$(id -g) $remote /opt/unibot/backups"

rsync -az --delete \
	--exclude '.env' --exclude 'data/' --exclude 'tests/' --exclude '__pycache__/' \
	--exclude '.pytest_cache/' --exclude '.ruff_cache/' --exclude 'deploy/' \
	"$cloud/" "$host:$remote/src/"
rsync -az "$here/docker-compose.yml" "$here/backup.sh" "$host:$remote/"
rsync -az "$here/unibot-relay-backup.service" "$here/unibot-relay-backup.timer" "$host:/tmp/"
ssh "$host" "sudo install -m 644 /tmp/unibot-relay-backup.service /tmp/unibot-relay-backup.timer /etc/systemd/system/ \
	&& rm -f /tmp/unibot-relay-backup.service /tmp/unibot-relay-backup.timer \
	&& sudo systemctl daemon-reload && sudo systemctl enable --now unibot-relay-backup.timer >/dev/null"

if ! ssh "$host" "test -s $remote/.env"; then
	echo "no $remote/.env on $host yet — create it first (see README.md in this directory), then run again" >&2
	exit 2
fi

# The Caddy site: only reload when it changed, so a routine deploy leaves TLS
# alone. SKIP_CADDY=1 leaves it out altogether (before the DNS record exists,
# say — Caddy would otherwise start asking for a certificate it cannot get yet).
if [ -z "${SKIP_CADDY:-}" ]; then
	rsync -az "$here/cloud.unibot.cn.caddy" "$host:/tmp/cloud.unibot.cn.caddy"
	ssh "$host" "if ! cmp -s /tmp/cloud.unibot.cn.caddy $showcase/sites.d/cloud.unibot.cn.caddy 2>/dev/null; then \
			sudo install -m 644 /tmp/cloud.unibot.cn.caddy $showcase/sites.d/cloud.unibot.cn.caddy \
			&& docker exec showcase-caddy-1 caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile \
			&& echo 'caddy: reloaded with cloud.unibot.cn'; fi; rm -f /tmp/cloud.unibot.cn.caddy"
fi

if [ -n "$build" ]; then
	ssh "$host" "cd $remote && docker compose up -d --build --remove-orphans 2>&1 | tail -3"
	sleep 3
	ssh "$host" "docker exec unibot-relay python -c \"import urllib.request; print('relay:', urllib.request.urlopen('http://127.0.0.1:8787/healthz', timeout=5).read().decode()[:160])\""
	printf 'public: '
	curl -fsS -m 15 "$public/healthz" && echo || echo "(not reachable yet through $public — DNS or certificate still on the way)"
fi
