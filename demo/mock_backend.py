"""Deterministic Jagatlabs fixture. No provider calls or real credentials required."""
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

SERVICE = {"id": 1, "name": "Garden Image AI", "creator": "Jagatlabs Demo",
           "description": "Generate a garden illustration in the local demo.",
           "category": "image", "tags": ["image", "demo"], "is_active": True,
           "is_featured": True, "price": 1, "created_at": None, "updated_at": None}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # Do not log prompts, credentials, or request URLs.

    def send_json(self, status, body):
        payload = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def authenticated(self):
        if self.headers.get("Authorization") != "Bearer demo-token":
            self.send_json(401, {"detail": "Demo token required"})
            return False
        return True

    def do_GET(self):
        route = urlsplit(self.path)
        if route.path == "/health":
            return self.send_json(200, {"status": "ok"})
        if route.path == "/api/v1/ai/":
            query = parse_qs(route.query)
            try:
                page = int(query.get("page", ["1"])[0])
                limit = int(query.get("limit", ["10"])[0])
                if page < 1 or not 1 <= limit <= 100:
                    raise ValueError()
            except ValueError:
                return self.send_json(422, {"detail": "Invalid pagination"})
            search = query.get("search", [""])[0].lower()
            category = query.get("category", [""])[0]
            matches = (search in SERVICE["name"].lower() or search in SERVICE["creator"].lower()) and (not category or category == "image")
            total = int(matches)
            return self.send_json(200, {"ok": True, "data": [SERVICE] if matches and page == 1 else [],
                                       "meta": {"page": page, "limit": limit, "total": total,
                                                "total_pages": total, "has_next": False, "has_prev": page > 1}})
        if route.path == "/api/v1/ai/1":
            return self.send_json(200, {"ok": True, "data": SERVICE})
        if route.path == "/api/v1/replicate/model-info":
            return self.send_json(200, {"model": "hidream-l1-fast", "description": "Local fixture; no generation charges"})
        if route.path == "/api/v1/replicate/status/demo-prediction-1":
            if self.authenticated():
                return self.send_json(200, {"id": "demo-prediction-1", "status": "succeeded", "progress": 1.0,
                                           "urls": ["https://example.com/demo-image.webp"]})
            return
        self.send_json(404, {"detail": "Not found"})

    def do_POST(self):
        if urlsplit(self.path).path != "/api/v1/replicate/create-prediction":
            return self.send_json(404, {"detail": "Not found"})
        if not self.authenticated():
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 32768:
                raise ValueError()
            body = json.loads(self.rfile.read(length))
            if not isinstance(body, dict) or not isinstance(body.get("prompt"), str) or not body["prompt"].strip():
                raise ValueError()
        except (ValueError, json.JSONDecodeError):
            return self.send_json(422, {"detail": "Invalid request"})
        self.send_json(200, {"id": "demo-prediction-1", "status": "starting", "urls": []})


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8090)
    args = parser.parse_args()
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()
