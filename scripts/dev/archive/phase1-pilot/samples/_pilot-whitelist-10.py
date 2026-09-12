#!/usr/bin/env python3
"""Show whether the 10 missing IDs are on the Pilot whitelist env vars."""
vals = {}
for line in open("/opt/app/pilot.env"):
    line = line.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    k, v = line.split("=", 1)
    vals[k] = v.strip().strip('"').strip("'")

missing = [
    468703, 474696, 504174, 529225, 529877,
    504887, 530187, 528834, 528813, 489984,
]
for key in ("COLLECTION_PILOT_LOAN_IDS", "COLLECTION_SCAN_CASE_IDS"):
    raw = vals.get(key, "")
    ids = [int(x) for x in raw.replace(" ", "").split(",") if x]
    print(key, "count", len(ids))
    hit = [i for i in missing if i in ids]
    print("  missing-10 in this list:", hit, "n=", len(hit))
