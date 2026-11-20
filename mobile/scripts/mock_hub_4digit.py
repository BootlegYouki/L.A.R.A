#!/usr/bin/env python3
"""Run the repository Mock Hub with four-digit Mobile test PINs in memory."""

import os
import sys
import time


REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(REPO_ROOT, "scripts"))

import mock_hub  # noqa: E402


def main():
    # Use isolated ports so an already-running development Hub is untouched.
    hub = mock_hub.MockHubServer(http_port=38080, ws_port=38081, udp_port=38888)
    hub.start()

    # MockHubServer.start() resets the seeded state, so apply this test-only
    # override afterwards. The normal shared Mock Hub and its seed remain intact.
    for user in mock_hub.STATE.users.values():
        if user["role"] in ("TEACHER", "STUDENT"):
            user["pin_hash"] = mock_hub.hash_pin("1234")

    lan_ip = hub._get_lan_ip()
    print("L.A.R.A Mock Hub is running for Mobile four-digit PIN testing.")
    print(f"Hub: http://{lan_ip}:38080  |  WebSocket: ws://{lan_ip}:38081")
    print("Teacher: T-0001 / PIN 1234 (Science 4)")
    print("Enrolled learner: 123456789012 / PIN 1234")
    print("Join-code learner: 123456789013 / PIN 1234 (class code K7M4QX)")
    print("This PIN change exists only in memory and resets when the Hub stops.")
    print("Press Ctrl+C to stop.")

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nStopping Mock Hub...")
        hub.stop()
        print("Mock Hub stopped.")


if __name__ == "__main__":
    main()
