"""Minimal GitHub Actions API client for the hosted Nexus jobs."""

import json
import random
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


class GitHubError(RuntimeError):
    def __init__(self, status):
        self.status = status
        super().__init__(f"GitHub API HTTP {status}")


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class GitHub:
    def __init__(self, token):
        if not token:
            raise ValueError("GitHub credential missing")
        self.token = token
        self.opener = build_opener(NoRedirects())

    def request(self, path, payload=None):
        parsed = urlsplit(path)
        if not path.startswith("/") or parsed.netloc or parsed.scheme or parsed.fragment:
            raise ValueError("Expected a GitHub API path")
        headers = {
            "Authorization": f"Bearer {self.token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2026-03-10",
            "User-Agent": "MTX-Nexus",
        }
        data = None
        if payload is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(payload).encode()
        attempts = 1 if data is not None else 3
        for attempt in range(attempts):
            try:
                with self.opener.open(
                    Request("https://api.github.com" + path, data, headers), timeout=10
                ) as response:
                    raw = response.read()
                    return json.loads(raw) if raw else None
            except HTTPError as exc:
                retry_after = exc.headers.get("Retry-After")
                reset = exc.headers.get("X-RateLimit-Reset", "")
                limited = exc.code == 403 and (
                    retry_after or exc.headers.get("X-RateLimit-Remaining") == "0"
                )
                delay = None
                if retry_after is not None:
                    delay = int(retry_after) if retry_after.isdecimal() else 60
                elif limited and reset.isdecimal():
                    delay = max(0, int(reset) - time.time())
                if (
                    not (limited or exc.code in (429, 500, 502, 503, 504))
                    or attempt + 1 == attempts
                    or (delay is not None and delay > 15)
                ):
                    raise GitHubError(exc.code) from None
            except (URLError, TimeoutError):
                if attempt + 1 == attempts:
                    raise GitHubError(0) from None
                delay = None
            time.sleep(max(delay or 0, 2**attempt) + random.random())

    def pages(self, path, field=None):
        page = 1
        while True:
            separator = "&" if "?" in path else "?"
            response = self.request(path + separator + urlencode({"per_page": 100, "page": page}))
            items = response[field] if field else response
            if not isinstance(items, list):
                raise ValueError("Invalid paginated GitHub response")
            yield from items
            if len(items) < 100:
                return
            page += 1
