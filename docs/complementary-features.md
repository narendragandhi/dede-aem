# Complementary Features

Candidates that build on what dede already has: the AEM-aware graph, one set of findings, and
runtime evidence (traces, request.log, error.log, authored content). Ranked by value for the
effort, given the code as it stands. None of these are built yet.

## Build next

| # | Feature | Why it complements | Builds on | Effort |
|---|---|---|---|---|
| 1 | **Unused and legacy components** | With `--content`, dede knows every resource type used on every authored page. Components the project defines that appear on no page are removal candidates; pages still using deprecated components are migration work, counted per page. Pure static, no runtime needed. | `ContentIndex`, resource-type nodes | S |
| 2 | **Runtime evidence in SARIF and PRs** | Put `runtimeCalls` / `logEntries` into SARIF result properties so GitHub code scanning shows "964 calls" next to the finding; a GitHub Action comments on PRs that touch classes serving traffic or add findings to hot code. Brings the evidence to where developers already look. | SARIF export, JSON report | S–M |
| 3 | **CDN / dispatcher log ingestion** | Closes the biggest blind spot: request.log on publish only sees cache misses. Cloud Service CDN logs (Cloud Manager log forwarding) give real page views and cache-hit ratio per page, and so per component -- "this component is on pages with 2% cache hits". | `RequestLogReader`, `RequestAttribution` | M |

## Then

| # | Feature | Why | Effort |
|---|---|---|---|
| 4 | **Single-file HTML report** | One page for stakeholders: findings ranked by traffic, attribution, errors, dead-code verdicts. The showcase artifact, and what a consultant hands over. | M |
| 5 | **Cloud Manager log fetch** | `--cloud-manager <program>/<env>` downloads request.log / error.log / CDN logs via the Cloud Manager API, so the runtime join needs no manual step. | M |
| 6 | **Render cost per component** | Average model latency × renders = where render time goes. The trace data already has both; this is ranking and presentation. | S |
| 7 | **Headless traffic** | Attribute GraphQL persisted queries (`/graphql/execute.json/<config>/<query>`) to Content Fragment models and the code around them; `.model.json` exporter requests already work. | M |
| 8 | **Jobs and workflows evidence** | Request attribution can't see schedulers, Sling jobs or workflow steps. Their log lines can: mark them as run / not run, so "dead" verdicts cover non-request code. | M |
| 9 | **Deprecation calendar** | Tie findings to Adobe's announced removal dates and sort "runs a lot and breaks soon" first. Mostly data curation -- and curated data is defensible. | M (ongoing) |

## Longer term (where a moat would come from)

- **Continuous mode with history**: runtime data ingested daily, snapshots kept, alerts when new
  errors appear in code that has findings. Value grows with history.
- **Cross-project baselines**: false-positive tuning and "normal" runtime profiles from many real
  codebases. Not copyable from the open repository.

## Not recommended

- **Building a tracing backend or dashboards**: APMs (and New Relic, bundled with Cloud Service)
  do this; dede's value is the AEM-aware join, not storage or charts.
- **More static rules for their own sake**: the differentiator is ranking findings by evidence,
  not the length of the rule list.
