# Demo recording

Sources of [`../demo.webp`](../demo.webp): a pretend shop catalog (`catalog/`), whose every
component is `fakesvc.py` (logs like a service, answers `/health`, `--cpu` keeps a core busy) and
whose `shop` preset has checks (`catalog/checks/`, one failing on purpose), and a Playwright
script driving the dashboard.

```sh
docs/demo/record.sh
```

Runs a zent daemon in a throwaway `$HOME` on port 8799 (`ZENT_PORT`), so your own daemon and
stacks are untouched; builds the fake repos there, each a git clone of its own origin
(catalog-api's branch behind `main`, orders-api with uncommitted work, the mailer's
`feature/smtp-tls` branch failing its build, so the notifier after it is skipped), plays the
scenario in headless Chrome, captured as PNG frames, and rewrites `docs/demo.webp`. Needs jolt,
python3, git, Node, `img2webp` (`brew install webp`), the Clojure CLI (the checks) and Google
Chrome (`CHROME=/path/to/chrome` to use another).

Edit the scenario in `record.js`; `STAGE=1 docs/demo/record.sh` sets the shop up and leaves its
daemon running, unrecorded, to try selectors against.
