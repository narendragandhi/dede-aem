# dede: which AEM code actually matters

## The problem

An AEM migration or audit produces hundreds of findings. ACS AEM Commons alone gets 164 HIGH and
788 MEDIUM from dede's Cloud Service readiness rules -- all real, none ranked by whether the code
runs. Teams fix in file order, or by CVSS, or not at all.

APM tools see the other half: Java methods, call counts, errors. They don't know that
`/content/wknd/us/en.html` renders the byline through an experience fragment, or which Cloud
Service finding sits in the model that serves every article.

## What dede does differently

It builds an AEM-aware code graph -- Java, OSGi, Sling Models, servlets, HTL, resource types,
content, editable templates, experience fragments -- and joins runtime evidence onto it:

- **Requests → components → code.** A URL from AEM's own `request.log` is followed through the
  page's content, its template structure and its experience fragments to the resource types it
  renders, then to the Sling Models and servlets bound to them -- honoring servlet selectors and
  extensions, resource supertypes and vanity URLs. No agent needed, so it works on Cloud Service.
- **Traces → findings.** OpenTelemetry spans give calls and errors per class and method; each
  finding is ranked by the calls to the method it sits in.
- **Dead code, confirmed.** The graph chooses what to instrument -- including classes with no
  static references -- so "instrumented but never called" is evidence, not a guess.
- **Errors → code → findings.** error.log entries are blamed on the topmost project frame in
  their stack, with the URL that triggered them.
- **One set of findings** for the CLI, the Maven gate, SARIF, JSON reports and an MCP server for
  AI agents.

## Evidence

| Claim | How it was checked | Result |
|---|---|---|
| Request attribution is right | AEM SDK + OpenTelemetry agent 2.10 + Adobe's WKND; trace ID → URL → model spans as ground truth | Every browsed page matched: image list on home/adventures/magazine (23), byline via experience fragment on articles (9), nothing for 404s and login |
| It works without the content in Git | WKND with its content modules removed, plus a content package | 0 → 32 of 35 requests attributed, identical to content-in-repo |
| The agent works inside AEM | Generated config on the AEM SDK | WKND's Sling Models instrumented inside their OSGi bundle, no boot-delegation change |
| Rules don't cry wolf | Re-run on WKND, Core Components and ACS AEM Commons after every rule change | WKND 1 MEDIUM; Core Components 2 CRITICAL (both real, in test support); ACS 0 CRITICAL |
| error.log parsing holds up | A real 10 MB AEM SDK error.log | 1,218 WARN/ERROR entries parsed; platform noise kept apart from project code |

## See it

Three short terminal videos, rendered from real runs (`docs/videos/`):

1. **Which findings actually matter?** (~40 s) -- static scan, generated agent config, traces and
   request.log joined.
2. **Works on real sites** (~30 s) -- 0 → 32 of 35 requests once the authored content is added.
3. **Gate the build, ask the graph** (~30 s) -- `--fail-on` in CI; the MCP server answering an agent.

## Try it

```bash
mvn clean package -DskipTests
java -jar target/dede-java-0.0.1-SNAPSHOT-exec.jar ./your-aem-project --request-log request.log --json report.json
```

See [Getting started](getting-started.md) and the [Runtime guide](runtime-guide.md).

## What it doesn't do

- It is not a tracing backend or an APM: it reads telemetry, it doesn't store or chart it.
- Request counts are requests that reached AEM. On publish, cached pages never do.
- Only project code is attributed; Core Components and product code are counted as platform.
- More in [Limitations](LIMITATIONS.md).
