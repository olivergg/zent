"""A pretend service for the demo catalog: logs like a real one, answers GET /health.

    python3 -u svc.py <name> <port> [--startup SECONDS]
"""
import http.server, random, sys, threading, time

name, port = sys.argv[1], int(sys.argv[2])
startup = float(sys.argv[sys.argv.index("--startup") + 1]) if "--startup" in sys.argv else 2
COLORS = {"INFO": "\033[32m", "WARN": "\033[33m", "ERROR": "\033[31m", "DEBUG": "\033[36m"}

CHATTER = {
    "postgres": [("INFO", "checkpoint complete: wrote {n} buffers"), ("INFO", "connection authorized: user=shop"),
                 ("WARN", "duration: {n}2 ms  statement: SELECT * FROM orders WHERE status = 'open'")],
    "broker": [("INFO", "partition orders-{d} leader elected"), ("INFO", "consumer group storefront rebalanced"),
               ("DEBUG", "fetch session {n} ok")],
    "catalog-api": [("INFO", "GET /products?page={d} 200 {n}ms"), ("INFO", "GET /products/{n} 200 4ms"),
                    ("WARN", "cache miss ratio {d}0% above threshold")],
    "orders-api": [("INFO", "POST /orders 201 {n}ms - order #{n}4 created"), ("INFO", "GET /orders/{n}4 200 3ms"),
                   ("INFO", "published order.created #{n}4"), ("WARN", "slow query on orders: {n}0 ms"),
                   ("ERROR", "payment gateway timeout for order #{n}4 - retrying")],
    "storefront": [("INFO", "GET / 200 {d}ms"), ("INFO", "GET /cart 200 {d}ms"), ("DEBUG", "hmr update /src/Cart.tsx")],
    "mailer": [("INFO", "sent order confirmation to customer-{n}@example.com")],
    "payments": [("INFO", "POST /charges 200 {n}ms"), ("DEBUG", "breakpoint hit in ChargeService.capture")],
}


def log(level, msg):
    print(f"{time.strftime('%H:%M:%S')} {COLORS[level]}{level:5}\033[0m [{name}] {msg}", flush=True)


def chatter():
    lines = CHATTER.get(name, [("INFO", "tick")])
    while True:
        time.sleep(random.uniform(1.5, 4))
        level, msg = random.choice(lines)
        log(level, msg.format(n=random.randint(10, 99), d=random.randint(1, 9)))


class Health(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200 if self.path == "/health" else 404)
        self.end_headers()
        self.wfile.write(b"ok\n")

    def log_message(self, *_):
        pass


log("INFO", f"starting {name}")
time.sleep(startup / 2)
log("INFO", "configuration loaded (profile=dev)")
time.sleep(startup / 2)
server = http.server.ThreadingHTTPServer(("127.0.0.1", port), Health)
log("INFO", f"listening on :{port}")
threading.Thread(target=chatter, daemon=True).start()
server.serve_forever()
