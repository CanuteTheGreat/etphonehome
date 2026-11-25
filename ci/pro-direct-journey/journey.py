#!/usr/bin/env python3
"""ETPH Pro direct purchase, Stripe TEST mode, end to end against a running errant-pro (LICENSE_MODE=test).

  STRIPE_SECRET_KEY=sk_test_... python3 journey.py --service http://127.0.0.1:18080 --whsec whsec_... \
      --email agent-test-etph-pro-20261124@agent.errant.solutions --out key.txt [--refund-id EP-... --refund-pi pi_...]

Purchase: POST /api/pro/checkout (the site's Buy button) -> the Checkout Session it redirects to is paid
server-side with Stripe's test card token (payment_pages confirm, consent accepted) -> the real
checkout.session.completed event is fetched from /v1/events and delivered signed to the service webhook (twice:
replay must not issue a second key) -> the success page shows the key once -> the key email arrives in the
agent inbox. Refund mode: refunds the payment in Stripe, delivers charge.refunded, checks the signed revocation
list. Never prints the Stripe key. Stdlib only.
"""
import argparse, hashlib, hmac, json, os, re, subprocess, sys, time, urllib.error, urllib.parse, urllib.request

KEY = os.environ["STRIPE_SECRET_KEY"].strip()
assert KEY.startswith("sk_test_"), "test mode only"
UA = {"User-Agent": "etph-pro-journey/1.0"}


def stripe(method, path, params=None):
    data = urllib.parse.urlencode(params or {}, doseq=True).encode() if method == "POST" else None
    url = "https://api.stripe.com" + path + ("?" + urllib.parse.urlencode(params, doseq=True) if params and method == "GET" else "")
    r = urllib.request.Request(url, data=data, method=method, headers={"Authorization": "Bearer " + KEY, **UA})
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            return json.load(resp)
    except urllib.error.HTTPError as e:
        raise SystemExit(f"stripe {method} {path}: {e.code} {e.read()[:400]}")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


def svc(method, url, body=None, headers=None):
    op = urllib.request.build_opener(NoRedirect)
    r = urllib.request.Request(url, data=body, method=method, headers={**UA, **(headers or {})})
    try:
        with op.open(r, timeout=30) as resp:
            return resp.status, resp.read().decode(), dict(resp.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(), dict(e.headers)


def deliver(base, whsec, event):
    body = json.dumps(event).encode()
    ts = int(time.time())
    sig = hmac.new(whsec.encode(), f"{ts}.".encode() + body, hashlib.sha256).hexdigest()
    return svc("POST", base + "/api/pro/stripe/webhook", body, {"Stripe-Signature": f"t={ts},v1={sig}", "Content-Type": "application/json"})


def wait_event(etype, match, since, timeout=120):
    end = time.time() + timeout
    while time.time() < end:
        evs = stripe("GET", "/v1/events", {"type": etype, "created[gte]": since - 5, "limit": 50})["data"]
        for e in evs:
            if match(e["data"]["object"]):
                return e
        time.sleep(3)
    raise SystemExit(f"no {etype} event within {timeout}s")


def ok(msg):
    print("PASS", msg, flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--service", required=True)
    ap.add_argument("--whsec", required=True)
    ap.add_argument("--email", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--inbox", default=os.path.expanduser("~/.hermes/scripts/agent-inbox.sh"))
    ap.add_argument("--refund-pi")
    ap.add_argument("--refund-id")
    a = ap.parse_args()
    base = a.service.rstrip("/")
    start = int(time.time())

    if a.refund_pi:
        stripe("POST", "/v1/refunds", {"payment_intent": a.refund_pi, "metadata[purpose]": "agent-test etph-pro journey"})
        ev = wait_event("charge.refunded", lambda o: o.get("payment_intent") == a.refund_pi, start)
        code, body, _ = deliver(base, a.whsec, ev)
        assert code == 200 and ("revoked " + a.refund_id) in body, body
        ok(f"refund in Stripe -> charge.refunded {ev['id']} -> service revoked {a.refund_id}")
        code, rev, _ = svc("GET", base + "/api/pro/revocations")
        import base64
        part = rev.split(".")[1]
        payload = json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))
        assert a.refund_id in payload["ids"], payload
        ok(f"signed revocation list now lists {a.refund_id} (mode {payload['mode']})")
        return

    code, _, h = svc("POST", base + "/api/pro/checkout", b"")
    assert code == 303 and "checkout.stripe.com" in h.get("Location", ""), (code, h)
    cs = re.search(r"cs_test_[A-Za-z0-9]+", h["Location"]).group(0)
    sess = stripe("GET", f"/v1/checkout/sessions/{cs}")
    assert sess["amount_total"] == 1500 and sess["currency"] == "usd" and sess["metadata"].get("app") == "etph-pro", sess
    assert (sess.get("consent_collection") or {}).get("terms_of_service") == "required"
    ok(f"Buy -> Checkout Session {cs[:20]}... US$15.00, metadata app=etph-pro, withdrawal-waiver box required")

    pm = stripe("POST", "/v1/payment_methods", {"type": "card", "card[token]": "tok_visa",
                                                "billing_details[name]": "Agent Test ETPH Pro", "billing_details[email]": a.email})
    paid = stripe("POST", f"/v1/payment_pages/{cs}/confirm", {"payment_method": pm["id"], "expected_amount": sess["amount_total"],
                                                              "consent[terms_of_service]": "accepted"})
    sess = stripe("GET", f"/v1/checkout/sessions/{cs}")
    assert sess["payment_status"] == "paid", sess["payment_status"]
    ok(f"paid with the Visa test card; session status={sess['status']} payment_status={sess['payment_status']}")

    ev = wait_event("checkout.session.completed", lambda o: o.get("id") == cs, start)
    code, body, _ = deliver(base, a.whsec, ev)
    assert code == 200 and "issued EP-" in body, body
    pid = re.search(r"issued (EP-[0-9A-Z]+)", body).group(1)
    ok(f"webhook {ev['id']} (signed) -> issued {pid}")
    code, body2, _ = deliver(base, a.whsec, ev)
    assert code == 200 and "duplicate" in body2, body2
    ok("same event replayed -> 'duplicate', no second key")

    code, page, _ = svc("GET", base + "/etphonehome/pro/key?s=" + cs)
    m = re.search(r'<code id="key">(ETPH1\.[^<]+)</code>', page)
    assert code == 200 and m, page[:300]
    key = m.group(1)
    ok(f"success page shows the key ({key[:14]}...{key[-6:]})")
    code, page2, _ = svc("GET", base + "/etphonehome/pro/key?s=" + cs)
    assert "ETPH1." not in page2 and "already shown" in page2
    ok("second visit: key not shown again")

    found = False
    end = time.time() + 180
    while time.time() < end and not found:
        lst = subprocess.run([a.inbox, "list", a.email], capture_output=True, text=True).stdout
        for line in lst.splitlines():
            k = line.split("|")[0].strip()
            if not k or "ETPH Pro license key" not in line:
                continue
            raw = subprocess.run([a.inbox, "read", k], capture_output=True, text=True).stdout
            import quopri
            txt = quopri.decodestring(raw.encode()).decode("utf-8", "replace")
            if key[-20:] in re.sub(r"\s+", "", txt):
                found = True
                ok(f"key email arrived in {a.email} (inbox key {k})")
                break
        if not found:
            time.sleep(10)
    assert found, "key email not found"

    with open(a.out, "w") as f:
        f.write(json.dumps({"key": key, "purchase_id": pid, "payment_intent": sess["payment_intent"], "session": cs}) + "\n")
    os.chmod(a.out, 0o600)
    ok(f"wrote {a.out}")


if __name__ == "__main__":
    main()
