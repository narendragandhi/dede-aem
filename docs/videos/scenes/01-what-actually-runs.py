# Video 1 -- "Which findings actually matter?" (~80 s)
# Output lines are from real runs on Adobe's WKND sample (see ../record-demo.sh).
# "..." marks trimmed output; long package paths are shortened with "…".
WINDOW_TITLE = "dede — WKND"

SCENES = [
    ("title", "Which AEM findings actually matter?",
     "Static analysis says what could break.\nTraces and AEM's own logs say what runs.", 4.5),

    ("caption", "1 · A static scan finds code that could break on AEM as a Cloud Service"),
    ("pause", 1.0),
    ("type", "alias dede='java -jar dede.jar'"),
    ("type", "dede wknd --json report.json"),
    ("out", ["~...",
             "Scan Summary: 237 nodes, 201 edges",
             "!Cloud Readiness Analysis complete: 1 issues found, score: 90%",
             "Writing JSON report: report.json"], 0.25),
    ("type", "jq '.violations[0] | {severity, category, message}' report.json"),
    ("out", ["{",
             '  "severity": "MEDIUM",',
             '  "category": "DEPRECATED_API",',
             '  "message": "Sling Model uses direct JCR API. Prefer Resource API for cloud portability."',
             "}"], 0.12),
    ("pause", 3.5),

    ("caption", "2 · The code graph picks what to instrument: findings, entry points, fan-in"),
    ("type", "dede wknd --emit-otel-config otel.properties"),
    ("out", ["~...", "!OpenTelemetry agent config for 3 class(es) written to otel.properties"], 0.3),
    ("pause", 2.5),
    ("out", ["# Run AEM with the OpenTelemetry Java agent and this config, browse the site, then:"], 0.1),
    ("pause", 3.0),

    ("clear",),
    ("caption", "3 · Join the traces and AEM's request.log back onto the graph"),
    ("type", "dede wknd --runtime-traces traces/spans.log --otel-config otel.properties \\"),
    ("type", "     --request-log logs/request.log"),
    ("out", ["~...",
             "Runtime traces: 6698 span(s), 1009 matched to 2 project class(es), 42 without code attributes",
             "Instrumented but never called in a 1 min trace window: 1 class(es)",
             "!  com.adobe.aem.guides.wknd.core.models.HelloWorldModel -- entry point never invoked in the window",
             "Findings in code that ran (1), most-called first:",
             "!  964 calls  MEDIUM [DEPRECATED_API] wknd/core/…/models/impl/ImageListImpl.java",
             "Requests: 35 total, 32 attributed to project code (223 content page(s) indexed)",
             "!  23 requests  com.adobe.aem.guides.wknd.core.models.impl.ImageListImpl",
             "!  9 requests  com.adobe.aem.guides.wknd.core.models.impl.BylineImpl",
             "~  (counts requests that reached AEM: on publish, dispatcher/CDN cache hits are not included)"], 0.35),
    ("caption", "Findings ranked by real calls · dead code confirmed · requests mapped to models"),
    ("pause", 8.0),

    ("title", "Checked against trace ground truth",
     "AEM SDK + OpenTelemetry agent 2.10 + Adobe's WKND:\nevery browsed page attributed to the right Sling Model.", 5.0),
]
