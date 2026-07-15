"""Print L4b-relevant Nacos config keys."""
import os
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def load_dotenv():
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip())


def main():
    load_dotenv()
    params = urllib.parse.urlencode({
        "dataId": "intelligent-collection-local.yml",
        "group": os.environ["NACOS_GROUP"],
        "tenant": os.environ["NACOS_NAMESPACE"],
        "username": os.environ["NACOS_USERNAME"],
        "password": os.environ["NACOS_PASSWORD"],
    })
    url = f"http://{os.environ['NACOS_SERVER_ADDR']}/nacos/v1/cs/configs?{params}"
    yaml_text = urllib.request.urlopen(url, timeout=15).read().decode("utf-8")
    keys = (
        "ingestion", "sms-test", "push-test", "loan-id", "sendgrid",
        "enabled", "enrich-jpush", "plan-templates", "datasource",
        "notification", "test-mode", "test-token", "channel.",
    )
    print("=== Nacos intelligent-collection-local.yml (关键项) ===")
    in_notification = False
    for line in yaml_text.splitlines():
        s = line.strip()
        if s.startswith("notification:"):
            in_notification = True
            print(s)
            continue
        if in_notification:
            if s and not s.startswith("#") and not line.startswith(" ") and not line.startswith("\t"):
                in_notification = False
            else:
                print(line.rstrip())
                continue
        if any(k in s.lower() for k in keys):
            print(s)


if __name__ == "__main__":
    main()
