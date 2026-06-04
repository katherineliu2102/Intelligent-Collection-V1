#!/usr/bin/env bash
set -euo pipefail

mvn -pl collection-admin -am clean package -DskipTests
docker compose up -d --build --force-recreate
