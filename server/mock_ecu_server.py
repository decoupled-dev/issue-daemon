#!/usr/bin/env python3
"""Minimal ECU server for developing/testing the AAOS issue daemon. Python 3.9+, stdlib only.

Endpoints (what the daemon calls):
  POST /api/v1/issues      multipart/form-data: "metadata" (JSON) + "bundle" (zip)
  POST /api/v1/heartbeat   application/json
Convenience:
  GET  /api/v1/issues      JSON list of received reports
  GET  /api/v1/devices     last heartbeat per device

Reports are stored under ./received/<device_id>/<timestamp>_<type>_<report_id>/.

  python3 server/mock_ecu_server.py --port 8080 [--api-key SECRET]
  # emulator -> host:  adb shell am broadcast --user 0 -n com.aaos.issuedaemon/.ControlReceiver \
  #                      -a com.aaos.issuedaemon.action.SET_SERVER_URL --es url http://10.0.2.2:8080
"""
import argparse
import json
import re
import threading
import time
from email import policy
from email.parser import BytesParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

STORE = Path("received")
LOCK = threading.Lock()
REPORTS = []
HEARTBEATS = {}
API_KEY = None
SAFE = re.compile(r"[^A-Za-z0-9._-]+")


def parse_multipart(content_type, body):
    msg = BytesParser(policy=policy.default).parsebytes(
        b"Content-Type: " + content_type.encode() + b"\r\n\r\n" + body
    )
    parts = {}
    for part in msg.iter_parts():
        name = part.get_param("name", header="content-disposition")
        parts[name] = (part.get_filename(), part.get_payload(decode=True))
    return parts


class Handler(BaseHTTPRequestHandler):
    server_version = "MockEcuServer/0.1"

    def _send(self, code, obj):
        data = json.dumps(obj, indent=2).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _body(self):
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            chunks = []
            while True:
                size = int(self.rfile.readline().strip().split(b";")[0], 16)
                if size == 0:
                    self.rfile.readline()
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.readline()
            return b"".join(chunks)
        return self.rfile.read(int(self.headers.get("Content-Length", 0)))

    def _authorized(self):
        if API_KEY and self.headers.get("Authorization") != f"Bearer {API_KEY}":
            self._send(401, {"error": "unauthorized"})
            return False
        return True

    def do_POST(self):
        if not self._authorized():
            return
        body = self._body()
        device = self.headers.get("X-Device-Id", "unknown")

        if self.path == "/api/v1/heartbeat":
            hb = json.loads(body or b"{}")
            with LOCK:
                HEARTBEATS[device] = {"received_at": time.time(), **hb}
            health = hb.get("health", {})
            print(f"[heartbeat] {device} cpu={health.get('cpu_percent')}% "
                  f"mem_avail={health.get('mem_available_percent')}% queued={hb.get('queued_reports')}")
            return self._send(200, {"status": "ok"})

        if self.path == "/api/v1/issues":
            ctype = self.headers.get("Content-Type", "")
            if not ctype.startswith("multipart/form-data"):
                return self._send(415, {"error": "expected multipart/form-data"})
            parts = parse_multipart(ctype, body)
            if "metadata" not in parts:
                return self._send(400, {"error": "missing metadata part"})
            meta = json.loads(parts["metadata"][1])
            issue = meta.get("issue", {})
            out = STORE / SAFE.sub("_", device) / SAFE.sub(
                "_", f"{time.strftime('%Y%m%d-%H%M%S')}_{issue.get('type')}_{meta.get('report_id')}")
            out.mkdir(parents=True, exist_ok=True)
            (out / "metadata.json").write_text(json.dumps(meta, indent=2))
            bundle_size = 0
            if "bundle" in parts:
                (out / "bundle.zip").write_bytes(parts["bundle"][1])
                bundle_size = len(parts["bundle"][1])
            record = {
                "report_id": meta.get("report_id"),
                "device_id": device,
                "type": issue.get("type"),
                "severity": issue.get("severity"),
                "title": issue.get("title"),
                "process": issue.get("process"),
                "bundle_bytes": bundle_size,
                "path": str(out),
            }
            with LOCK:
                REPORTS.append(record)
            print(f"[issue] {device} {issue.get('severity')} {issue.get('type')} "
                  f"process={issue.get('process')} '{issue.get('title')}' bundle={bundle_size // 1024}KB -> {out}")
            return self._send(201, {"status": "stored", "report_id": meta.get("report_id")})

        self._send(404, {"error": "not found"})

    def do_GET(self):
        with LOCK:
            if self.path == "/api/v1/issues":
                return self._send(200, REPORTS)
            if self.path == "/api/v1/devices":
                return self._send(200, HEARTBEATS)
        self._send(404, {"error": "not found"})

    def log_message(self, fmt, *args):
        pass


def main():
    global API_KEY
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--api-key", help="require 'Authorization: Bearer <key>'")
    args = ap.parse_args()
    API_KEY = args.api_key
    print(f"Mock ECU server on http://{args.host}:{args.port}, storing reports in {STORE.resolve()}")
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
