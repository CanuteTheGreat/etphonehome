"""A browser session against the pfSense web GUI (login + csrf-magic), for
the package's pages and the pages an admin uses around it."""
import http.cookiejar
import re
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request


class PfGui:
    def __init__(self, base="https://192.168.1.1", user="admin", password="pfsense"):
        self.base = base
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        self.jar = http.cookiejar.CookieJar()
        self.op = urllib.request.build_opener(urllib.request.HTTPSHandler(context=ctx),
                                              urllib.request.HTTPCookieProcessor(self.jar))
        self.csrf = ""
        self.get("/")
        page = self.post("/", {"usernamefld": user, "passwordfld": password, "login": "Sign In"})
        if "logout" not in page.lower() and "setup_wizard" not in page:
            raise RuntimeError("pfSense login failed: " + page[:300])

    def _remember(self, body):
        m = re.search(r"""name=['"]__csrf_magic['"]\s+value=['"]([^'"]+)""", body) or \
            re.search(r"""csrfMagicToken\s*=\s*["']([^"']+)""", body)
        if m:
            self.csrf = m.group(1)
        return body

    def _open(self, req, timeout):
        # interface/filter reloads (an interface coming up runs rc.newwanip
        # and restarts packages) briefly drop the LAN side: retry a while
        for attempt in range(12):
            try:
                with self.op.open(req, timeout=timeout) as r:
                    return self._remember(r.read().decode("utf-8", "replace"))
            except urllib.error.URLError as e:
                if isinstance(e, urllib.error.HTTPError) or attempt == 11:
                    raise
                time.sleep(5)

    def get(self, path):
        return self._open(urllib.request.Request(self.base + path), 120)

    def post(self, path, fields):
        data = dict(fields)
        data["__csrf_magic"] = self.csrf
        return self._open(urllib.request.Request(self.base + path, data=urllib.parse.urlencode(data).encode()), 180)
