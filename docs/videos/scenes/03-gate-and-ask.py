# Video 3 -- "Gate the build, ask the graph" (~55 s)
# Gate: a real run on Adobe's aem-core-wcm-components (both findings are in its test-support module).
# MCP: real JSON-RPC responses from `dede wknd --request-log logs/request.log --mcp`, trimmed.
WINDOW_TITLE = "dede — CI gate and MCP"

SCENES = [
    ("title", "Gate the build. Ask the graph.",
     "One set of findings for CI, reports and AI agents.", 4.0),

    ("caption", "Fail the build at the severity you choose"),
    ("type", "dede aem-core-wcm-components --fail-on CRITICAL"),
    ("out", ["~...",
             "!Failing (--fail-on CRITICAL): 2 CRITICAL finding(s):",
             "  - [THREAD_UNSAFE_FIELDS] Non-thread-safe objects stored as fields of a singleton … MockConfig…",
             "  - [INTERNAL_API] Internal implementation APIs … testing/MockPersistenceStrategy.java:21"], 0.4),
    ("type", "echo $?"),
    ("out", ["!1"], 0.2),
    ("caption", "Same gate in Maven: -Ddede.failOnSeverity=HIGH"),
    ("pause", 4.0),

    ("clear",),
    ("caption", "--mcp serves the scan to Claude Code or any MCP client"),
    ("out", ["# setup:  claude mcp add dede -- java -jar dede.jar wknd --request-log logs/request.log --mcp"], 0.2),
    ("pause", 1.5),
    ("out", ["# an agent calls the tools -- real responses, trimmed:",
             "→ class_info  {\"name\": \"BylineImpl\"}",
             "  \"id\": \"class:com.adobe.aem.guides.wknd.core.models.impl.BylineImpl\",",
             "  \"type\": \"SLING_MODEL\",",
             "!  \"properties\": { \"runtime.requests\": \"9\", ... },",
             "  \"dependsOn\": { \"IMPLEMENTS\": [...], \"INJECTS_VALUE\": [...], ... }",
             "→ summary",
             "!  \"requests\": { \"byClass\": [ ImageListImpl: 23, BylineImpl: 9 ] }"], 0.45),
    ("caption", "Tools: summary · list_findings · class_info · impact · search"),
    ("pause", 7.0),

    ("title", "dede-aem",
     "Open source · Apache 2.0\ngithub.com/narendragandhi/dede-aem", 4.5),
]
