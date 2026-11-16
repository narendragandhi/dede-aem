# Getting Started

Five minutes from clone to a ranked list of AEM findings. Every command here was run on Adobe's
[WKND](https://github.com/adobe/aem-guides-wknd) sample; the numbers are what it printed.

## Prerequisites

- Java 21 and Maven 3.9
- An AEM project checkout (any Maven AEM archetype project)
- Optional, for runtime evidence: AEM's `request.log` / `error.log`, or an AEM SDK you can start
  with the [OpenTelemetry Java agent](https://github.com/open-telemetry/opentelemetry-java-instrumentation)

## Build

```bash
mvn clean package -DskipTests
alias dede='java -jar target/dede-java-0.0.1-SNAPSHOT-exec.jar'
```

## 1. Static scan

```bash
dede ./aem-guides-wknd --json report.json
```

`report.json` holds every finding (Cloud Service readiness, forbidden APIs, graph checks) with
severity, file, line and message. WKND: one MEDIUM, score 90%.

Gate a build on it:

```bash
dede ./my-project --fail-on HIGH        # exit 1 on any HIGH or CRITICAL finding
```

Or in Maven, with the `dede-maven-plugin` and `-Ddede.failOnSeverity=HIGH`.

## 2. Add what actually runs (no agent needed)

Download AEM's logs (Cloud Manager → Environments → Download Logs, or `crx-quickstart/logs`
locally) and join them:

```bash
dede ./aem-guides-wknd --request-log request.log --error-log error.log --json report.json
```

Requests are mapped to the servlets, components and Sling Models that served them; error.log
entries to the project classes they came from. WKND: 35 requests, 32 attributed --
`ImageListImpl` 23, `BylineImpl` 9.

If your pages are authored in AEM (they usually are), add the site's content:

```bash
dede ./my-project --request-log request.log --content my-site-content.zip
```

## 3. Add traces (method-level evidence)

```bash
dede ./my-project --emit-otel-config otel.properties      # the graph picks what to instrument
# start AEM with:  -javaagent:opentelemetry-javaagent.jar -Dotel.javaagent.configuration-file=otel.properties
#                  -Dotel.traces.exporter=logging-otlp   (or send to a Collector)
dede ./my-project --runtime-traces spans.log --otel-config otel.properties --json report.json
```

Each finding gets `runtimeCalls` (and `runtimeMethodCalls` when it sits in a method); classes that
were instrumented but never called are listed with a verdict.

## 4. Ask from an AI agent

```bash
claude mcp add dede -- java -jar /path/to/dede.jar /path/to/my-project --request-log request.log --mcp
```

Tools: `summary`, `list_findings`, `class_info`, `impact`, `search`.

## Next

- [Runtime guide](runtime-guide.md): every runtime input, what it adds, and its limits
- [Showcase](showcase.md): the story, with the evidence
- [Limitations](LIMITATIONS.md): what dede can't see
- `dede --help`: every option
