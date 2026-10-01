#!/bin/sh
# Puts the project site's mirror on this box, next to the showcase:
#
#   1. the site block  → ../sites.d/unibot.cn.caddy   (imported by the Caddyfile)
#   2. sync.sh         → /usr/local/bin/unibot-site-mirror, run every minute by a systemd timer
#   3. release-sync.py → /usr/local/bin/unibot-release-mirror, every fifteen minutes: the newest
#                        releases' packages under www/dl, served at unibot.cn/dl/
#   4. traffic.py      → /usr/local/bin/unibot-traffic, every ten minutes: the site's access log
#                        counted by day into /var/lib/unibot-traffic/traffic.db (the relay's
#                        admin page reads it; cloud/deploy/unibot-hk mounts it)
#   5. a first sync of each, so Caddy has something to serve
#   6. Caddy validated and reloaded (recreated if the mounts are new to it)
#
# Run as root from anywhere: `sudo demo/showcase/mirror/install.sh`. Idempotent; run it again
# after `git pull` to pick up changes to any of these files. DNS is yours: A records for
# unibot.cn and www.unibot.cn pointing at this box, nothing in front of it.
set -eu

here=$(cd "$(dirname "$(readlink -f "$0")")" && pwd)
showcase=$(dirname "$here")

# WWW_ROOT from the environment or the showcase's .env; the default is ../www, as in docker-compose.yml.
# /etc/default/unibot-site-mirror keeps it for the timers, next to whatever else was put there
# by hand (GITHUB_TOKEN, MIRROR_RELEASES_KEEP, TRAFFIC_*): only the WWW_ROOT line is ours.
if [ -z "${WWW_ROOT:-}" ] && [ -f "$showcase/.env" ]; then
	WWW_ROOT=$(sed -n 's/^WWW_ROOT=//p' "$showcase/.env" | tail -n 1)
fi
defaults=/etc/default/unibot-site-mirror
kept=$( [ -f "$defaults" ] && grep -v '^WWW_ROOT=' "$defaults" || true)
{
	[ -n "$kept" ] && printf '%s\n' "$kept"
	[ -n "${WWW_ROOT:-}" ] && printf 'WWW_ROOT=%s\n' "$WWW_ROOT"
} >"$defaults.new"
if [ -s "$defaults.new" ]; then mv "$defaults.new" "$defaults"; else rm -f "$defaults.new" "$defaults"; fi
[ -n "${WWW_ROOT:-}" ] && export WWW_ROOT

install -d "$showcase/sites.d"
install -m 644 "$here/unibot.cn.caddy" "$showcase/sites.d/unibot.cn.caddy"

ln -sfn "$here/sync.sh" /usr/local/bin/unibot-site-mirror
ln -sfn "$here/release-sync.py" /usr/local/bin/unibot-release-mirror
ln -sfn "$here/traffic.py" /usr/local/bin/unibot-traffic
install -m 644 "$here/unibot-site-mirror.service" "$here/unibot-site-mirror.timer" \
	"$here/unibot-release-mirror.service" "$here/unibot-release-mirror.timer" \
	"$here/unibot-traffic.service" "$here/unibot-traffic.timer" /etc/systemd/system/
systemctl daemon-reload
# where Caddy writes the access log (docker-compose.yml mounts it); the relay reads the counts
install -d "$showcase/logs/caddy" /var/lib/unibot-traffic

# the first sync before the timer: an elapsed timer fires the moment it is enabled, and two
# clones into the same directory do not mix
echo "first sync"
"$here/sync.sh"
systemctl enable -q --now unibot-site-mirror.timer
# the releases take longer (a gigabyte or so the first time): the service does it in the
# background, the timer keeps it current
systemctl enable -q --now unibot-release-mirror.timer
systemctl start --no-block unibot-release-mirror.service
systemctl enable -q --now unibot-traffic.timer

cd "$showcase"
echo "caddy: validating"
docker compose run --rm --no-deps caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
echo "caddy: applying"
docker compose up -d caddy
docker compose exec caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile
echo "done: site timer $(systemctl is-active unibot-site-mirror.timer), release timer $(systemctl is-active unibot-release-mirror.timer), traffic timer $(systemctl is-active unibot-traffic.timer); https://unibot.cn once DNS points here, packages at /dl/ once the first fetch is through"
