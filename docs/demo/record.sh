#!/usr/bin/env bash
# Re-records docs/demo.gif: a zent daemon on the pretend catalog (catalog/), in a throwaway
# $HOME and on its own port - your own daemon and stacks are never touched - driven in headless
# Chrome (record.js), the video converted to a GIF.
# Needs: jolt, python3, git, node, ffmpeg, Google Chrome (or CHROME=/path/to/chrome).
set -euo pipefail
cd "$(dirname "$0")"
demo=$PWD
export ZENT_PORT=${ZENT_PORT:-8799}
SVC_PORTS=(7101 7102 7103 7104 7105 7106 7107)

for port in "$ZENT_PORT" "${SVC_PORTS[@]}"; do
  if nc -z 127.0.0.1 "$port" 2>/dev/null; then echo "port $port already in use" >&2; exit 1; fi
done
[ -d node_modules/playwright-core ] || npm install --silent
rm -rf out

# a home of its own (~ on screen is this one), sharing the real one's dependency caches
home=$(mktemp -d)
for d in .jolt .gitlibs .m2; do [ -e "$HOME/$d" ] && ln -s "$HOME/$d" "$home/$d"; done
zent() { HOME="$home" ZENT_CATALOG="$demo/catalog" "$demo/../../bin/zent" "$@"; }
cleanup() {
  zent stop postgres >/dev/null 2>&1 || true   # :permanent: only its own stop reaches it
  zent shutdown >/dev/null 2>&1 || true
  for port in "${SVC_PORTS[@]}"; do lsof -ti "tcp:$port" -sTCP:LISTEN | xargs kill 2>/dev/null || true; done
  rm -rf "$home"
}
trap cleanup EXIT

# the component repos: fakesvc.py as each one's svc.py, plus what the kinds run
ws=$home/workspace
for repo in shop-db shop-broker catalog-api orders-api storefront payments mailer; do
  mkdir -p "$ws/$repo" && cp fakesvc.py "$ws/$repo/svc.py"
done
script() { printf '#!/bin/sh\n%s\n' "$2" > "$1" && chmod +x "$1"; }
script "$ws/shop-db/migrate.sh" 'echo "applying 14 migrations"; sleep 2; echo "schema at v14"'
script "$ws/shop-db/load-fixtures.sh" 'echo "loading 2,000 orders"; sleep 2; echo done'
# the mailer's feature branch doesn't build: a worktree, and a failed card
git() { command git -c user.name=demo -c user.email=demo@example.com -c init.defaultBranch=main "$@"; }
(cd "$ws/mailer" &&
  script build.sh 'echo "compiling mailer"; sleep 1; echo "build ok"' &&
  git init -q && git add . && git commit -qm init &&
  git init -q --bare ../mailer-origin.git && git remote add origin ../mailer-origin.git && git push -q origin main &&
  git checkout -qb feature/smtp-tls &&
  script build.sh 'echo "compiling mailer"; sleep 1; echo "src/smtp.py:42: ERROR TlsContext: unknown cipher suite" >&2; exit 1' &&
  git commit -qam "smtp: tls" && git push -q origin feature/smtp-tls && git checkout -q main)
# payments as if run from an IDE: the checkout preset only probes it
python3 -u fakesvc.py payments 7106 > "$home/payments.log" 2>&1 &

zent serve > serve.log 2>&1 &
until [ -s "$home/.cache/zent/daemon.edn" ]; do sleep 0.5; done

video=$(node record.js "http://127.0.0.1:$ZENT_PORT/" out)
ffmpeg -v error -y -ss 0.3 -i "$video" -loop 0 -vf \
  "fps=6,scale=800:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=96:stats_mode=diff[p];[b][p]paletteuse=dither=none:diff_mode=rectangle" \
  ../demo.gif
# optional: another ~20% off, no visible loss
if command -v gifsicle >/dev/null; then gifsicle -b -O3 --lossy=30 ../demo.gif; fi
echo "wrote docs/demo.gif ($(du -h ../demo.gif | cut -f1))"
