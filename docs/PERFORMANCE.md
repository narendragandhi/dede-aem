# Performance & Scale

This document reports actual measured performance, not projections. Every number here comes from running dede-java against real codebases and a synthetic worst-case harness; none of it is estimated from source reading. Where the results are good, that's stated plainly; where they're not, that's stated too.

## Methodology

- **Hardware**: Apple M1, 8 GB RAM, macOS 26.2, single developer laptop -- not representative of production server infrastructure. Numbers here are directional, not a guarantee of behavior on other hardware.
- **JVM**: OpenJDK 21.0.8 (Temurin/Homebrew build), default heap settings (no `-Xmx` tuning) -- what a real user gets running `java -jar dede.jar` without configuration.
- **Timing**: `/usr/bin/time -l`, wall-clock (`real`) and peak resident set size (`maximum resident set size`).
- **Test corpora**: three real, unmodified AEM codebases already checked out locally, not synthetic fixtures:

| Tier | Project | Java files | What it is |
|------|---------|-----------|------------|
| 1 | `dede-java` itself | 73 | Baseline |
| 2 | [Adobe AEM Core WCM Components](https://github.com/adobe/aem-core-wcm-components) | 579 | Official Adobe component library |
| 3 | [ACS AEM Commons](https://github.com/Adobe-Consulting-Services/acs-aem-commons) | 1,433 | Widely-used third-party AEM utility library |

## Scan Performance (source → dependency graph)

`java -jar dede.jar <project> --profiles aem`, one run per tier, cold JVM:

| Tier | Files | Nodes | Edges | Wall time | Peak RSS |
|------|-------|-------|-------|-----------|----------|
| 1 | 73 | 3,042 | 4,885 | 3.57s | 257 MB |
| 2 | 579 (7.9x) | 12,889 (4.2x) | 25,147 (5.1x) | 6.64s (1.9x) | 283 MB |
| 3 | 1,433 (19.6x) | 25,278 (8.3x) | 49,284 (10.1x) | 10.18s (2.9x) | 292 MB |

**Scan time scales sub-linearly with file count** (19.6x more files → 2.9x more time) and **memory stays flat** (257 → 292 MB, a 14% increase for a 20x larger codebase). At this scale, on this hardware, source scanning is not a bottleneck: a 1,433-file real-world AEM library scans in about 10 seconds with well under 300 MB of heap.

This does not extrapolate indefinitely -- these are three data points spanning roughly 20x, not a proof of linear behavior at 10x or 100x this scale. Nobody has run this against a 10,000-file monorepo.

## Security Reachability Audit (`--security`)

Running `--security` against the same three tiers added negligible time, because none of them produced any `VULNERABILITY` graph nodes to check reachability for -- the loop in `VulnerabilityService.audit()` had nothing to iterate over. That's not a meaningful performance signal; it's an empty test.

To actually exercise the code path, `VulnerabilityServiceBenchmark` (checked into `src/test/java/com/dede/intelligence/`, disabled by default -- run with `-Dtest=VulnerabilityServiceBenchmark`) constructs a graph directly against `VulnerabilityService`: N vulnerability nodes, each connected via a 5-hop `WIRES_TO` bundle chain to M endpoint nodes, and times `audit()` in isolation from scanning/JVM startup.

**First run found a genuine super-linear scaling bug**, since fixed:

| Vulnerabilities × Endpoints | Dijkstra calls | Before | After | Speedup |
|---|---|---|---|---|
| 20 × 500 | 10,000 | 0.93s (93 μs/call) | 0.12s (12.3 μs/call) | 7.5x |
| 50 × 1,000 | 50,000 | 8.43s (169 μs/call) | 0.51s (10.2 μs/call) | 16.6x |
| 100 × 2,000 | 200,000 | 68.92s (345 μs/call) | 2.92s (14.6 μs/call) | 23.6x |

**Root cause:** `audit()` called `dijkstra.getPath(vuln, endpoint)` once per (vulnerability, endpoint) pair -- a full Dijkstra run from scratch every single call. Going from 10,000 to 200,000 checks was 20x more work but took 74x longer, and per-call cost grew with scale (93 → 345 μs), which shouldn't happen if each check were O(1) amortized.

**Fix:** `dijkstra.getPaths(vuln)` computes the shortest-path tree from a source once and returns a `SingleSourcePaths` object; querying it per endpoint (`.getPath(endpoint)`) is a cheap tree lookup instead of a fresh algorithm run. Changed the shape from O(vulnerabilities × endpoints × Dijkstra) to O(vulnerabilities × Dijkstra + vulnerabilities × endpoints × path-length). Post-fix, per-call cost is roughly flat (10-15 μs regardless of scale) -- the actual signature of a fixed algorithmic shape, not just a constant-factor speedup. All pre-existing correctness tests (`VulnerabilityServiceTest`) pass unchanged: same findings, same blast-radius ranking, before and after.

**Practical read:** the pessimistic scenario (100 CVEs, 2,000 endpoints) went from over a minute to under 3 seconds. This was a real, reproducible bug, found and fixed in the same session by actually measuring instead of assuming.

## What This Does *Not* Cover

- **GraphQL/REST API under concurrent load.** Nothing here tests multiple simultaneous requests, connection pooling, or Tomcat thread exhaustion.
- **The Maven plugin's overhead** beyond the single-project smoke test done earlier this session.
- **Disk I/O patterns** on a project with a much larger file count, or on spinning disk / network storage rather than local SSD.
- **Startup time** in a resource-constrained container (Docker's default memory limits, CI runner-class hardware) rather than a dev laptop.
- **The OSGi bundle deployed inside a running AEM instance** -- entirely different runtime and constraints from the standalone JAR measured here.

Anyone citing this document as evidence the tool is "production-scale-ready" is overstating what it shows: it shows source scanning holds up reasonably well at ~1,400 files on one laptop, and it shows one specific, real algorithmic bottleneck in the security audit at pessimistic CVE/endpoint counts. That's the actual scope of the evidence.
