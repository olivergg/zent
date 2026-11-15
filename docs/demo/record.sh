#!/usr/bin/env bash
# Re-records docs/demo.webp: a zent daemon on the pretend catalog (catalog/), in a throwaway
# $HOME and on its own port - your own daemon and stacks are never touched - driven in headless
# Chrome (record.js), its frames assembled into an animated WebP.
# Needs: jolt, python3, git, node, img2webp (brew install webp), clojure, Google Chrome (or CHROME=/path/to/chrome).
# STAGE=1 sets the shop up and leaves its daemon running, unrecorded, until ctrl-c (to work on record.js).
set -euo pipefail
cd "$(dirname "$0")"
demo=$PWD
export ZENT_PORT=${ZENT_PORT:-8799}
SVC_PORTS=(7101 7102 7103 7104 7105 7106 7107 7108)

for port in "$ZENT_PORT" "${SVC_PORTS[@]}"; do
  if nc -z 127.0.0.1 "$port" 2>/dev/null; then echo "port $port already in use" >&2; exit 1; fi
done
[ -d node_modules/playwright-core ] || npm install --silent

# a home of its own (~ on screen is this one), sharing the real one's dependency caches
home=$(mktemp -d)
for d in .jolt .gitlibs .m2; do [ -e "$HOME/$d" ] && ln -s "$HOME/$d" "$home/$d"; done
# who the daemon's git commits as (the card's rebase)
printf '[user]\n\tname = demo\n\temail = demo@example.com\n' > "$home/.gitconfig"
zent() { HOME="$home" ZENT_CATALOG="$demo/catalog" "$demo/../../bin/zent" "$@"; }
cleanup() {
  zent stop postgres >/dev/null 2>&1 || true   # :permanent: only its own stop reaches it
  zent shutdown >/dev/null 2>&1 || true
  for port in "${SVC_PORTS[@]}"; do lsof -ti "tcp:$port" -sTCP:LISTEN | xargs kill 2>/dev/null || true; done
  rm -rf "$home"
}
trap cleanup EXIT

# the component repos: fakesvc.py as each one's svc.py, plus what the kinds run - each a git
# clone of its own origin, so the cards' git rows have something to say
ws=$home/workspace
script() { printf '#!/bin/sh\n%s\n' "$2" > "$1" && chmod +x "$1"; }
git() { command git -c user.name=demo -c user.email=demo@example.com -c init.defaultBranch=main "$@"; }
repos=(shop-db shop-broker catalog-api orders-api storefront payments mailer notifier)
for repo in "${repos[@]}"; do mkdir -p "$ws/$repo" && cp fakesvc.py "$ws/$repo/svc.py"; done
script "$ws/shop-db/migrate.sh" 'echo "applying 14 migrations"; sleep 1; echo "schema at v14"'
script "$ws/shop-db/load-fixtures.sh" 'echo "loading 2,000 orders"; sleep 1; echo done'
script "$ws/mailer/build.sh" 'echo "compiling mailer"; sleep 1; echo "build ok"'
for repo in "${repos[@]}"; do
  (cd "$ws/$repo" && git init -q && git add . && git commit -qm init &&
    git init -q --bare "$home/origins/$repo.git" && git remote add origin "$home/origins/$repo.git" &&
    git push -q -u origin main && git remote set-head origin main)
done
# catalog-api: a feature branch three commits behind origin/main - a rebase a click away
git -C "$ws/catalog-api" checkout -qb feature/search
git -C "$ws/catalog-api" commit -q --allow-empty -m "search: by brand"
git clone -q "$home/origins/catalog-api.git" "$home/teammate"
for msg in "catalog: price filter" "catalog: paging cursor" "catalog: cache headers"; do
  git -C "$home/teammate" commit -q --allow-empty -m "$msg"
done
git -C "$home/teammate" push -q origin main && git -C "$ws/catalog-api" fetch -q origin
# orders-api: work in progress; storefront: a commit not pushed yet
echo "# retry payment timeouts" >> "$ws/orders-api/svc.py"
git -C "$ws/storefront" commit -q --allow-empty -m "cart: free shipping banner"
# the mailer's feature branch doesn't build: a worktree, and a failed card (the notifier, skipped)
(cd "$ws/mailer" && git checkout -qb feature/smtp-tls &&
  script build.sh 'echo "compiling mailer"; sleep 1; echo "src/smtp.py:42: ERROR TlsContext: unknown cipher suite" >&2; exit 1' &&
  git commit -qam "smtp: tls" && git push -q origin feature/smtp-tls && git checkout -q main)
# payments as if run from an IDE: the checkout preset only probes it
python3 -u fakesvc.py payments 7106 > "$home/payments.log" 2>&1 &

zent serve > serve.log 2>&1 &
until [ -s "$home/.cache/zent/daemon.edn" ]; do sleep 0.5; done

if [ -n "${STAGE:-}" ]; then
  echo "staged: http://127.0.0.1:$ZENT_PORT/ (HOME=$home) - ctrl-c ends it"
  while :; do sleep 3600; done
fi

frames=$home/frames
node record.js "http://127.0.0.1:$ZENT_PORT/" "$frames"
# animated WebP, not GIF: the dashboard's motion repaints most of each frame, a quarter the size;
# at the page's own width, each frame held as long as it stayed on screen
args=()
while read -r file ms; do args+=(-d "$ms" "$frames/$file"); done < "$frames/frames.txt"
img2webp -loop 0 -lossy -q 70 -m 6 "${args[@]}" -o ../demo.webp >/dev/null
echo "wrote docs/demo.webp ($(du -h ../demo.webp | cut -f1))"
