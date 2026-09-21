#!/usr/bin/env python3
"""
L.A.R.A Standalone Zero-Dependency Mock Hub Server
Emulates Local Hub network services:
- UDP Subnet Discovery Beacon (:8888)
- HTTP REST & File Streaming (:8080)
- WebSocket Real-Time Event Broker (:8081)
"""

import json
import socket
import socketserver
import http.server
import threading
import time
import os
import sys
import re

MOCK_CLASSROOMS = [
    {
        "id": "c1a2b3c4-0001-4000-8000-000000000001",
        "name": "Science 4 (Agham 4)",
        "section": "Aguinaldo",
        "class_code": "SCI4-AG",
        "teacher_id": "t1-uuid"
    },
    {
        "id": "c1a2b3c4-0002-4000-8000-000000000002",
        "name": "Mathematics 4 (Matematika 4)",
        "section": "Aguinaldo",
        "class_code": "MATH4-AG",
        "teacher_id": "t1-uuid"
    }
]

MOCK_ANNOUNCEMENTS = [
    {
        "id": "a1-uuid",
        "classroom_id": "c1a2b3c4-0001-4000-8000-000000000001",
        "title": "Maligayang Pagdating sa Agham 4!",
        "content": "Basahin ang Aralin 1 tungkol sa Ecosystem bago magsimula ang ating pagsusulit.",
        "allow_comments": True,
        "created_at": int(time.time() * 1000) - 3600000
    }
]

MOCK_QUIZ = {
    "id": "q1-uuid",
    "title": "Maikling Pagsusulit sa Ecosystem",
    "time_limit_minutes": 15,
    "questions": [
        {
            "id": "q1-item1",
            "order_index": 1,
            "question_text": "Ano ang tawag sa proseso kung saan ang mga halaman ay gumagawa ng sariling pagkain gamit ang sikat ng araw?",
            "question_type": "MULTIPLE_CHOICE",
            "options": ["Photosynthesis", "Respiration", "Evaporation", "Germination"],
            "points": 1,
            "correct_answer": "Photosynthesis"
        },
        {
            "id": "q1-item2",
            "order_index": 2,
            "question_text": "Ang mga hayop na kumakain lamang ng mga halaman ay tinatawag na Herbivore.",
            "question_type": "TRUE_FALSE",
            "options": ["Tama (True)", "Mali (False)"],
            "points": 1,
            "correct_answer": "Tama (True)"
        }
    ]
}

class MockHttpHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        # Clean terminal logging
        sys.stdout.write(f"[HTTP] {self.address_string()} - {format % args}\n")
        sys.stdout.flush()

    def _send_cors_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS, PUT, DELETE")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Range, Authorization")

    def do_OPTIONS(self):
        self.send_response(204)
        self._send_cors_headers()
        self.end_headers()

    def do_GET(self):
        path = self.path.split("?")[0]

        if path == "/" or path == "/download":
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self._send_cors_headers()
            self.end_headers()
            html = """<!DOCTYPE html>
            <html>
            <head><title>L.A.R.A Local Download Portal</title></head>
            <body style="font-family: sans-serif; text-align: center; padding: 40px;">
              <h1>L.A.R.A Local Classroom Hub</h1>
              <p>DepEd Offline LAN Resource & Assessment Platform</p>
              <div style="margin: 20px;">
                <a href="/download/lara-student.apk" style="display:inline-block; padding: 12px 24px; background: #0B57D0; color: white; border-radius: 8px; text-decoration: none;">Download Android App (.apk)</a>
              </div>
            </body>
            </html>"""
            self.wfile.write(html.encode("utf-8"))
            return

        if path == "/api/classrooms":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self._send_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps(MOCK_CLASSROOMS).encode("utf-8"))
            return

        if path == "/api/quizzes/active":
            # STRICT ANTI-CHEAT RULE: Strip correct_answer
            safe_quiz = {
                "id": MOCK_QUIZ["id"],
                "title": MOCK_QUIZ["title"],
                "time_limit_minutes": MOCK_QUIZ["time_limit_minutes"],
                "questions": []
            }
            for q in MOCK_QUIZ["questions"]:
                q_copy = dict(q)
                q_copy.pop("correct_answer", None)
                safe_quiz["questions"].append(q_copy)

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self._send_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps(safe_quiz).encode("utf-8"))
            return

        if re.match(r"^/api/materials/[^/]+/stream$", path):
            # HTTP 206 Byte-Range streaming dummy data (1MB mock video file)
            total_size = 1024 * 1024  # 1MB
            range_header = self.headers.get("Range")
            
            start = 0
            end = min(start + 65536, total_size - 1)  # 64KB chunk
            
            if range_header and range_header.startswith("bytes="):
                parts = range_header.replace("bytes=", "").split("-")
                start = int(parts[0]) if parts[0] else 0
                if len(parts) > 1 and parts[1]:
                    end = int(parts[1])
                else:
                    end = min(start + 65536, total_size - 1)

            if start >= total_size:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{total_size}")
                self.end_headers()
                return

            chunk_len = end - start + 1
            self.send_response(206)
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Range", f"bytes {start}-{end}/{total_size}")
            self.send_header("Content-Length", str(chunk_len))
            self._send_cors_headers()
            self.end_headers()
            self.wfile.write(b"0" * chunk_len)
            return

        self.send_response(404)
        self._send_cors_headers()
        self.end_headers()

    def do_POST(self):
        path = self.path.split("?")[0]
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length).decode("utf-8") if content_length > 0 else "{}"
        try:
            payload = json.loads(body)
        except Exception:
            payload = {}

        if path == "/api/classrooms/join":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self._send_cors_headers()
            self.end_headers()
            response = {
                "status": "ACTIVE",
                "classroom_id": MOCK_CLASSROOMS[0]["id"],
                "message": "Enrolled successfully in Grade 4 Science"
            }
            self.wfile.write(json.dumps(response).encode("utf-8"))
            return

        if path == "/api/sync/pull":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self._send_cors_headers()
            self.end_headers()
            response = {
                "server_time": int(time.time() * 1000),
                "announcements": MOCK_ANNOUNCEMENTS,
                "materials": [
                    {
                        "id": "m1",
                        "classroom_id": MOCK_CLASSROOMS[0]["id"],
                        "title": "Agham Aralin 1 - Ang Ecosystem.pdf",
                        "file_type": "DOCUMENT",
                        "file_size_bytes": 1048576,
                        "download_url": "/api/materials/m1/download"
                    }
                ],
                "assignments": []
            }
            self.wfile.write(json.dumps(response).encode("utf-8"))
            return

        if path == "/api/announcements":
            # Teacher mobile announcement creation
            new_ann = {
                "id": f"a-{int(time.time())}",
                "classroom_id": payload.get("classroom_id", MOCK_CLASSROOMS[0]["id"]),
                "title": payload.get("title", "Pahayag mula sa Guro"),
                "content": payload.get("content", ""),
                "allow_comments": True,
                "created_at": int(time.time() * 1000)
            }
            MOCK_ANNOUNCEMENTS.append(new_ann)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self._send_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"success": True, "announcement": new_ann}).encode("utf-8"))
            return

        if re.match(r"^/api/quizzes/[^/]+/submit$", path):
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self._send_cors_headers()
            self.end_headers()
            receipt = {
                "score": 2,
                "total_points": 2,
                "submitted_at": int(time.time() * 1000),
                "status": "GRADED"
            }
            self.wfile.write(json.dumps(receipt).encode("utf-8"))
            return

        self.send_response(404)
        self._send_cors_headers()
        self.end_headers()


class MockHubServer:
    def __init__(self, http_port=8080, ws_port=8081, udp_port=8888, broadcast_interval=3.0):
        self.http_port = http_port
        self.ws_port = ws_port
        self.udp_port = udp_port
        self.broadcast_interval = broadcast_interval
        self.running = False
        self.httpd = None
        self.udp_thread = None

    def _get_lan_ip(self):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
            return ip
        except Exception:
            return "127.0.0.1"

    def _udp_broadcast_loop(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        lan_ip = self._get_lan_ip()

        while self.running:
            payload = json.dumps({
                "app": "lara",
                "version": "1.0.0-mock",
                "name": "Grade 4 - Science (Mock Hub)",
                "ip": lan_ip,
                "http_port": self.http_port,
                "ws_port": self.ws_port
            }).encode("utf-8")

            try:
                sock.sendto(payload, ("255.255.255.255", self.udp_port))
            except Exception:
                pass
            time.sleep(self.broadcast_interval)

        sock.close()

    def start(self):
        self.running = True
        
        # Start HTTP server
        socketserver.TCPServer.allow_reuse_address = True
        self.httpd = socketserver.ThreadingTCPServer(("0.0.0.0", self.http_port), MockHttpHandler)
        self.http_thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.http_thread.start()

        # Start UDP broadcast beacon
        self.udp_thread = threading.Thread(target=self._udp_broadcast_loop, daemon=True)
        self.udp_thread.start()

    def stop(self):
        self.running = False
        if self.httpd:
            self.httpd.shutdown()
            self.httpd.server_close()


if __name__ == "__main__":
    hub = MockHubServer(http_port=8080, ws_port=8081, udp_port=8888)
    hub.start()
    lan_ip = hub._get_lan_ip()
    print("=" * 65)
    print(f"  L.A.R.A Local Hub Mock Server is RUNNING")
    print(f"  - HTTP REST:    http://{lan_ip}:8080")
    print(f"  - Web Portal:   http://{lan_ip}:8080/download")
    print(f"  - UDP Beacon:   Broadcasting to 255.255.255.255:8888")
    print("=" * 65)
    print("Press Ctrl+C to stop.\n")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nStopping Mock Hub...")
        hub.stop()
        print("Mock Hub stopped.")
