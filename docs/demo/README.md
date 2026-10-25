# Demo recording

Sources of [`../demo.webp`](../demo.webp): a pretend shop catalog (`catalog/`), whose every
component is `fakesvc.py` (logs like a service, answers `/health`) and whose `shop` preset has
checks (`catalog/checks/`, one failing on purpose), and a Playwright script driving the dashboard.

```sh
docs/demo/record.sh
```

Runs a zent daemon in a throwaway `$HOME` on port 8799 (`ZENT_PORT`), so your own daemon and
stacks are untouched; builds the fake repos there (the mailer's `feature/smtp-tls` branch fails
its build), plays the scenario in headless Chrome and rewrites `docs/demo.webp`. Needs jolt,
python3, git, Node, `ffmpeg`, `img2webp` (`brew install webp`), the Clojure CLI (the checks) and
Google Chrome (`CHROME=/path/to/chrome` to use another).

Edit the scenario in `record.js`.
