#!/usr/bin/env bash
# Re-captures the output the demo scenes quote, so the videos can be checked against reality
# after a change. Usage:
#   record-demo.sh <aem-guides-wknd> <aem-core-wcm-components> [traces] [request.log] [content.zip] [otel.properties]
# The trace and request log come from a local AEM SDK run with the OpenTelemetry agent; without
# them only the static and gate scenes are re-captured.
set -euo pipefail

wknd=${1:?path to aem-guides-wknd}
core=${2:?path to aem-core-wcm-components}
traces=${3:-}
requests=${4:-}
content=${5:-}
otel=${6:-}
jar=$(cd "$(dirname "$0")/../.." && pwd)/target/dede-java-0.0.1-SNAPSHOT-exec.jar
out=$(mktemp -d)
dede() { java -jar "$jar" "$@" 2>&1 | sed -E 's/^[0-9-]+ [0-9:.]+ \[main\] (INFO|WARN|ERROR) +[a-zA-Z.]+ - //'; }

echo "== 01: static scan"
dede "$wknd" --json "$out/report.json" | grep -E "Scan Summary|Cloud Readiness Analysis complete"
echo "== 01: agent config"
dede "$wknd" --emit-otel-config "$out/otel.properties" | grep -E "OpenTelemetry agent config"

if [[ -n "$traces" && -n "$requests" && -n "$otel" ]]; then
  echo "== 01: traces + request log"
  dede "$wknd" --runtime-traces "$traces" --otel-config "$otel" --request-log "$requests" \
    | grep -E "Runtime traces|Instrumented|never invoked|Findings in code|calls  |Requests: [0-9]|requests  "
fi

if [[ -n "$requests" && -n "$content" ]]; then
  echo "== 02: code only, then with content (run on a copy of the project without ui.content*)"
  dede "$wknd" --request-log "$requests" --content "$content" | grep -E "Content:|Requests: [0-9]|requests  "
fi

echo "== 03: gate"
set +e
dede "$core" --fail-on CRITICAL | grep -A2 "Failing"
echo "exit: ${PIPESTATUS[0]}"
