"""Read-only media probe against a fresh, unchanged shared mock. Never prints credentials."""
import contextlib
import io
import json
from pathlib import Path
import sys
import urllib.error
import urllib.request

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
from mock_hub import MockHubServer


def main():
    hub = MockHubServer(http_port=28080, ws_port=28081, udp_port=28888)
    with contextlib.redirect_stdout(io.StringIO()):
        hub.start()
    base = "http://127.0.0.1:28080"
    try:
        request = urllib.request.Request(base + "/api/auth/login", data=json.dumps(
            {"lrn_or_id": "123456789012", "pin": "1234"}).encode(), headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request) as response:
            token = json.load(response)["token"]
        print("Authenticated documented seeded learner; credential output suppressed.")
        material = "m2000000-0000-4000-8000-000000000002"
        for start, end in ((0, 65535), (200000, 209999), (1000, 1999)):
            request = urllib.request.Request(base + f"/api/materials/{material}/stream", headers={
                "Authorization": "Bearer " + token, "Range": f"bytes={start}-{end}"})
            with urllib.request.urlopen(request) as response:
                body = response.read()
                assert response.status == 206 and len(body) == end - start + 1
                print(f"Range bytes={start}-{end}: HTTP {response.status}; Content-Range={response.headers['Content-Range']}; bytes={len(body)}")
                assert set(body) == {ord("0")}
        print("DISCREPANCY: stream bytes are ASCII zero placeholders, not a decodable video.")
        request = urllib.request.Request(base + f"/api/materials/{material}/download", headers={
            "Authorization": "Bearer " + token, "Range": "bytes=100-199"})
        with urllib.request.urlopen(request) as response:
            body = response.read()
            print(f"Download resume request: HTTP {response.status}; bytes={len(body)}; Content-Range={response.headers.get('Content-Range')}; PDF placeholder={body.startswith(b'%PDF-')}")
            print("DISCREPANCY: video download returns PDF placeholder and ignores Range; real video saving/resume cannot be verified against this mock.")
        for headers, expected in (({}, 401), ({"Authorization": "Bearer " + token, "Range": "bytes=999999999-999999999"}, 416)):
            try:
                urllib.request.urlopen(urllib.request.Request(base + f"/api/materials/{material}/stream", headers=headers))
                raise AssertionError("Expected media refusal")
            except urllib.error.HTTPError as error:
                assert error.code == expected
                print(f"Refusal probe: HTTP {error.code}")
    finally:
        hub.stop()


if __name__ == "__main__":
    main()
