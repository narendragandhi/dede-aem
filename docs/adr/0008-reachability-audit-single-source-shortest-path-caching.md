# ADR 008: Cache the Shortest-Path Tree Per Vulnerability, Not Per Pair

## Status
Accepted

## Context
`docs/PERFORMANCE.md` documented a measured, reproducible finding: `VulnerabilityService.audit()`'s reachability check scaled super-linearly. Going from 10,000 to 200,000 (vulnerability, endpoint) pair checks -- 20x more work -- took 74x longer (0.93s to 68.92s), and the cost per individual check grew with scale (93 μs to 345 μs) rather than staying flat. That growth is the signature of redundant work, not just a slow constant factor.

The cause was straightforward once measured: `audit()` called `dijkstra.getPath(vuln, endpoint)` inside a nested loop over every (vulnerability, endpoint) pair. `DijkstraShortestPath.getPath(source, target)` runs the full algorithm from `source` on every call; with the loop structure unchanged, every endpoint check for the same vulnerability re-ran Dijkstra from that same vulnerability node from scratch, discarding the shortest-path tree it had just computed for the previous endpoint.

## Decision
Call `dijkstra.getPaths(vuln)` once per vulnerability, outside the endpoint loop. This returns a `ShortestPathAlgorithm.SingleSourcePaths<CodeNode, Relationship>` -- the shortest-path tree computed once from that source -- and each endpoint's reachability check becomes `pathsFromVuln.getPath(endpoint)`, a lookup against the already-computed tree rather than a fresh algorithm run. This changes the overall shape from O(vulnerabilities × endpoints × Dijkstra) to O(vulnerabilities × Dijkstra + vulnerabilities × endpoints × path-reconstruction), where the second term is cheap relative to a full algorithm run.

Verified two ways before considering this done:
1. **Correctness preserved**: `VulnerabilityServiceTest`'s four pre-existing tests pass unchanged -- same findings, same blast-radius ranking, before and after.
2. **The fix addresses the actual algorithmic shape, not just a constant factor**: post-fix, per-call cost is roughly flat (10-15 μs) regardless of scale (10K vs 200K checks), versus growing 93 → 345 μs before. A pure constant-factor speedup would still show the same growth curve, just shifted down; this doesn't.

## Consequences
- **Positive**: 23.6x faster at the pessimistic benchmark scale (100 CVEs × 2,000 endpoints: 68.9s → 2.9s), with the improvement growing at larger scale rather than shrinking -- confirms the fix scales correctly rather than just optimizing the specific benchmark sizes tested.
- **Positive**: No behavior change visible to callers -- same `BlastRadiusFinding` output, same `report` list contents, same ranking. This was purely an internal algorithm change.
- **Negative**: `SingleSourcePaths` for a given vulnerability is still recomputed on every call to `audit()`; if `audit()` is called repeatedly against a graph that hasn't changed (e.g. from a long-running server process), there's no cross-call caching. Not measured or addressed here -- `audit()` is currently only called once per CLI/Maven-plugin invocation, so this wasn't the bottleneck under test.
- **Negative**: Real-world (vulnerability, endpoint) counts at actual customer scale are still unmeasured -- the benchmark uses a synthetic 5-hop chain topology, not the branching/cyclic structure of a real AEM dependency graph. The fix's algorithmic complexity is sound regardless of topology, but the specific timing numbers in `docs/PERFORMANCE.md` are still a synthetic worst-case, not a real customer scan.
