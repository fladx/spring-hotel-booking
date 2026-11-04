#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
for module in gateway booking-service hotel-service eureka-server; do
 file=".run/$module.pid"
 [[ -f "$file" ]] || continue
 pid=$(cat "$file")
 # Guard against PID reuse: terminate only our exact module jar.
 if ps -p "$pid" -o args= 2>/dev/null | grep -Fq "$module/target/$module-1.0.0-SNAPSHOT.jar"; then
   kill "$pid"
 fi
 rm -f "$file"
done
