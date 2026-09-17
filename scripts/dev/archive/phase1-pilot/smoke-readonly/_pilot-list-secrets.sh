#!/bin/bash
# List secret filenames only; print GOOGLE_APPLICATION_CREDENTIALS path from env/container.
set -euo pipefail
echo "=== /opt/app/secrets (names only) ==="
ls -la /opt/app/secrets 2>/dev/null || echo "no secrets dir"
echo "=== env file key names ==="
grep -E 'GOOGLE|CREDENTIAL|PUBSUB' /opt/app/pilot.env | sed 's/=.*/=***/'
echo "=== container env ==="
docker inspect collection-admin --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -E 'GOOGLE|CREDENTIAL' | sed 's/=.*/=***/'
echo "=== container mounts ==="
docker inspect collection-admin --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{println}}{{end}}'
