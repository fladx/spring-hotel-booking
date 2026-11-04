#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .run
export EUREKA_ENABLED=true DEMO_USERS=true
for module in eureka-server hotel-service booking-service gateway; do
 jar="$module/target/$module-1.0.0-SNAPSHOT.jar"
 [[ -f "$jar" ]] || { echo "Missing $jar; run ./mvnw package first" >&2; exit 1; }
 if [[ -f ".run/$module.pid" ]] && kill -0 "$(cat ".run/$module.pid")" 2>/dev/null; then
   echo "$module already running; stop it first" >&2; exit 1
 fi
 nohup java -Xms64m -Xmx256m -jar "$jar" > ".run/$module.log" 2>&1 &
 echo "$!" > ".run/$module.pid"
 echo "$module started pid=$!"
done
echo 'Wait for registration. Gateway: http://localhost:8080; Eureka: http://localhost:8761'
