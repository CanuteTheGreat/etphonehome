"""A browser session against the OPNsense web GUI (login + CSRF), for the
plugin's page and API the way the GUI's own JavaScript calls it."""
import http.cookiejar
import json
import re
import ssl
import urllib.parse
import urllib.request


class OpnGui:
    def __init__(self, base="https://192.168.1.1", user="root", password="opnsense"):
        self.base = base
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        self.jar = http.cookiejar.CookieJar()
        self.op = urllib.request.build_opener(urllib.request.HTTPSHandler(context=ctx),
                                              urllib.request.HTTPCookieProcessor(self.jar))
        self.csrf = None
        page = self.get("/")
        fields = dict(re.findall(r'<input type="hidden" name="([^"]+)" value="([^"]*)"', page))
        fields.update({"usernamefld": user, "passwordfld": password, "login": "1"})
        page = self.get("/", data=urllib.parse.urlencode(fields).encode())
        if "logout" not in page.lower() and "Logout" not in page:
            raise RuntimeError("OPNsense login failed")

    def get(self, path, data=None, headers=None):
        req = urllib.request.Request(self.base + path, data=data, headers=headers or {})
        with self.op.open(req, timeout=60) as r:
            return r.read().decode("utf-8", "replace")

    def page(self, path):
        body = self.get(path)
        m = re.search(r'X-CSRFToken["\']?\s*[:,]\s*["\']([^"\']+)', body)
        if m:
            self.csrf = m.group(1)
        return body

    def api(self, path, payload=None):
        h = {"X-CSRFToken": self.csrf or "", "Content-Type": "application/json"}
        data = json.dumps(payload).encode() if payload is not None else (b"{}" if path.endswith(
            ("reconfigure", "start", "stop", "restart")) else None)
        return json.loads(self.get(path, data=data, headers=h) or "{}")
