"""Run the repository's unchanged Mock Hub on isolated authoring test ports."""
import importlib.util
from pathlib import Path
import time

root = Path(__file__).resolve().parents[4]
spec = importlib.util.spec_from_file_location("mock_hub", root / "scripts/mock_hub.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
hub = module.MockHubServer(http_port=38080, ws_port=38081, udp_port=38888)
hub.start()
print("Unchanged Mock Hub on HTTP 38080 / WS 38081", flush=True)
try:
    while True:
        time.sleep(1)
except KeyboardInterrupt:
    pass
finally:
    hub.stop()
