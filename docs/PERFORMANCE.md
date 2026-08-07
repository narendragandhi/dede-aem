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

To actually exercise the code path, a standalone harness (`BenchHarness.java`, not checked into the repo -- see below) constructs a graph directly against `VulnerabilityService`: N vulnerability nodes, each connected via a 5-hop `WIRES_TO` bundle chain to M endpoint nodes, and times `audit()` in isolation from scanning/JVM startup.

| Vulnerabilities × Endpoints | Dijkstra calls | Wall time | Time / call |
|---|---|---|---|
| 20 × 500 | 10,000 | 0.93s | 93 μs |
| 50 × 1,000 | 50,000 | 8.43s | 169 μs |
| 100 × 2,000 | 200,000 | 68.92s | 345 μs |

**This is super-linear, not linear.** Going from 10,000 to 200,000 checks is 20x more work; it took 74x longer. The cost *per check* grew from 93 μs to 345 μs as scale increased -- if each check were independent and O(1) amortized, per-call cost would stay flat. It doesn't.

**Root cause (inferred, not yet fixed):** `VulnerabilityService.audit()` calls `dijkstra.getPath(vuln, endpoint)` once per (vulnerability, endpoint) pair, inside a nested loop. If a fresh shortest-path tree from `vuln` isn't cached and reused across all its endpoint checks, every one of those calls repeats work that only needs to happen once per vulnerability. The fix (not implemented here) would be computing one shortest-path tree per vulnerability source and querying endpoint distances against it, changing the shape from O(vulnerabilities × endpoints × Dijkstra) toward O(vulnerabilities × Dijkstra).

**Practical read:** at counts plausible for a real audit (tens of CVEs, hundreds of endpoints) this is fine -- under a second. At the pessimistic end (100 CVEs, 2,000 endpoints, which is not an unreasonable count for a large enterprise AEM instance with many resource types and servlets), it's over a minute for the security audit alone. That's a real, reproducible finding, not a hypothetical one -- and it's exactly the kind of thing this whole session has been about surfacing rather than assuming away.

## What This Does *Not* Cover

- **GraphQL/REST API under concurrent load.** Nothing here tests multiple simultaneous requests, connection pooling, or Tomcat thread exhaustion.
- **The Maven plugin's overhead** beyond the single-project smoke test done earlier this session.
- **Disk I/O patterns** on a project with a much larger file count, or on spinning disk / network storage rather than local SSD.
- **Startup time** in a resource-constrained container (Docker's default memory limits, CI runner-class hardware) rather than a dev laptop.
- **The OSGi bundle deployed inside a running AEM instance** -- entirely different runtime and constraints from the standalone JAR measured here.

Anyone citing this document as evidence the tool is "production-scale-ready" is overstating what it shows: it shows source scanning holds up reasonably well at ~1,400 files on one laptop, and it shows one specific, real algorithmic bottleneck in the security audit at pessimistic CVE/endpoint counts. That's the actual scope of the evidence.
