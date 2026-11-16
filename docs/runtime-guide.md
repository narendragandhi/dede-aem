# Runtime Guide

Static analysis tells you what *could* break. This guide covers the inputs that tell dede what
*runs*, what each one adds to the report, and where each one stops.

| Input | Flag | Needs an agent? | Adds |
|---|---|---|---|
| OpenTelemetry traces | `--runtime-traces` | Yes | calls, errors and latency per class and method |
| Agent config used | `--otel-config` | — | "instrumented but never called" (dead-code evidence), method-level zeros |
| AEM request.log / access.log | `--request-log` | No | requests per servlet, component and Sling Model |
| Authored content | `--content` | No | lets requests for pages that only exist in AEM be attributed |
| AEM error.log | `--error-log` | No | WARN/ERROR entries per project class, with the URL that triggered them |
| Previous snapshot | `--snapshot` / `--compare` | — | what changed at runtime between two scans |

All of them work on AEM as a Cloud Service: Cloud Manager lets you download or forward
`request.log`, `error.log` and the CDN/dispatcher logs, and the OpenTelemetry agent can run on
the AEM SDK and on-premise instances.

## Traces

1. `dede ./project --emit-otel-config otel.properties`
   chooses up to 50 classes (`--otel-max-classes`) in this order: classes with findings, AEM
   entry points (servlets, filters, Sling Models, OSGi components, workflow processes), classes
   with fan-in, then classes with no static references (dead-code candidates).
2. Start AEM with the agent and that file. On the AEM SDK:
   `CQ_JVM_OPTS="-javaagent:opentelemetry-javaagent.jar -Dotel.javaagent.configuration-file=otel.properties -Dotel.traces.exporter=logging-otlp" crx-quickstart/bin/start`.
   No OSGi boot-delegation change was needed with agent 2.10.
3. Feed back the agent log (`logging-otlp`) or a Collector `file` exporter output:
   `dede ./project --runtime-traces spans.log --otel-config otel.properties --json report.json`

Report additions: `runtimeCalls` per finding, `runtimeMethod` / `runtimeMethodCalls` when the
finding is inside a method, a `runtime` block (hottest classes with per-method calls, hottest
findings, trace window) and `runtime.instrumentedNotCalled` with a verdict per class.

A method is reported as having **0 calls** only when `--otel-config` shows it was instrumented.
"Never called" only covers the trace window: monthly jobs may simply not have run.

## Requests

`--request-log` reads AEM's `request.log` (`-> GET /path` / `<- 200 ... 45ms` pairs) and NCSA
`access.log` lines. Traces with HTTP server spans work too; when both are given the log wins and
spans are not double-counted.

A request is attributed by following Sling:

- `@SlingServletPaths` servlets match by path (`/bin/x`, `/bin/x.json`, `/bin/x/suffix`);
- a page request renders every component on the page, **plus** its editable template's
  structure and any experience fragments it embeds;
- a servlet registered for the page's own resource type, selectors and extension takes the
  request instead of the HTL render;
- a component with no script or model of its own renders through its nearest project supertype;
  servlets on a supertype serve its subtypes;
- `sling:vanityPath` and shortened URLs (`/us/en.html` for `/content/site/us/en`) resolve to their
  page -- shortened ones only when exactly one page matches.

Counts are **estimated renders of requests that reached AEM**. On publish the dispatcher and CDN
answer cached pages without AEM, so these are cache misses, not page views.

### Pages authored in AEM

The repository holds seed content at most. Give dede the real pages with `--content`
(repeatable):

| Source | How to get it |
|---|---|
| `site-content.zip` | Package Manager: a package with filters `/content/<site>` and `/conf/<site>` |
| `nodes.json` | `/bin/querybuilder.json?path=/content/<site>&property=sling:resourceType&property.operation=exists&p.hits=selective&p.properties=jcr:path sling:resourceType cq:template fragmentVariationPath sling:vanityPath&p.limit=-1` |
| `https://author.example.com/content/<site>` | dede runs that query itself, paged, plus `/conf/<site>`; set `DEDE_AEM_TOKEN` (bearer, e.g. a Cloud Service developer token) or `DEDE_AEM_USER` / `DEDE_AEM_PASSWORD` |

Only node paths, resource types, templates, fragment references and vanity paths are read.
On very large repositories the live query can hit query limits; use a package there.

## Errors

`--error-log` reads WARN and ERROR entries with their stack traces (SDK/on-premise format and
Cloud Service downloads with the pod prefix). Each entry belongs to the **topmost stack frame in
project code** -- a model that throws inside Sling's model factory is blamed on the model -- or
to its logger when that is a project class; everything else is counted as platform noise.

The `logs` block lists, per class: ERROR/WARN counts, first/last seen, repeated messages grouped
into signatures, and the request paths that triggered them. Findings in classes that logged
errors are listed under `findingsInFailingCode`; each finding gets `logEntries`.

## Snapshots

With any runtime flag, `--snapshot dir/` stores each node's `runtime.*` values. A later
`--compare dir/dede-snapshot-....json` reports a RUNTIME CHANGES section: new errors (first, and
raising the delta severity to at least MEDIUM), code no longer called or rendered, counts that
doubled or halved by at least 10, and average latency up or down 50% by at least 5 ms. A metric
is compared only when both scans recorded it. Compare windows of similar length.

## CVE blast radius

With `--security --dependency-check-report report.json` and runtime data, CVEs are ranked by
the traffic observed at their reachable endpoints, then endpoint count, then CVSS. CVEs reach
code through OSGi bundles from built manifests: run `mvn package` before scanning.

## Graph properties

Everything above is also written onto the graph (REST, GraphQL, snapshots, MCP) as node
properties: `runtime.calls`, `runtime.errors`, `runtime.avgMs`, `runtime.maxMs` (traces),
`runtime.requests` (classes), `runtime.renders` (resource types), `runtime.logErrors`,
`runtime.logWarnings` (error.log).

## Blast radius export

```bash
dede ./project --request-log request.log --content site.zip \
     --blast-radius BylineImpl --blast-radius HelloWorldModel --blast-radius-dir out/
```

Writes `out/blast-radius-<name>.json`: everything that depends on the class, by hop, then the
pages that render the affected components and the requests each received. "Depends on" follows
each relationship's real direction -- a model's resource type depends on the model, an
implementation's callers come through its interface; the containing package and the types a
model adapts from are not dependents. On WKND: `ImageListImpl` → 12 pages, 23 requests (home 11,
adventures 6, magazine 6, matching the traces); `BylineImpl` → 21 pages, only one with traffic;
`HelloWorldModel` → no pages. Files are relative to the project; no absolute paths are written.
