"""Dump channel/collection section from Nacos YAML."""
import os
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
    line = line.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    k, v = line.split("=", 1)
    os.environ.setdefault(k.strip(), v.strip())

params = urllib.parse.urlencode({
    "dataId": "intelligent-collection-local.yml",
    "group": os.environ["NACOS_GROUP"],
    "tenant": os.environ["NACOS_NAMESPACE"],
    "username": os.environ["NACOS_USERNAME"],
    "password": os.environ["NACOS_PASSWORD"],
})
url = f"http://{os.environ['NACOS_SERVER_ADDR']}/nacos/v1/cs/configs?{params}"
yaml_text = urllib.request.urlopen(url, timeout=15).read().decode("utf-8")

print("--- flags ---")
for flag in (
    "sms-test-mode", "push-test-mode", "push-test-token",
    "collection-cases-ai-v1-sub", "collection-cases-test1-sub",
):
    print(f"{flag}: {flag in yaml_text}")

capture = False
for line in yaml_text.splitlines():
    if line.startswith(("channel:", "collection:")):
        capture = True
    if capture:
        # redact secrets
        if "api-key:" in line or "app-key:" in line or "password:" in line:
            key = line.split(":", 1)[0]
            print(f"{key}: <redacted>")
        else:
            print(line)
        if (
            line
            and not line.startswith((" ", "\t"))
            and not line.endswith(":")
            and line not in ("channel:", "collection:")
        ):
            capture = False
