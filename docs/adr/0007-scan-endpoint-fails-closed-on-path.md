# ADR 007: /api/graph/scan Fails Closed on Path, Not Open

## Status
Accepted

## Context
No-args "server mode" (the README's own documented way to "run as a web server for programmatic access") starts with a permanently empty graph. Nothing calls `scanner.scan()` unless the process is launched with a CLI project-path argument, and that path always exits the process afterward (or requires `--watch`) -- confirmed by starting a real no-args server and hitting every REST/GraphQL endpoint, all of which returned `nodeCount: 0`.

Adding a `POST /api/graph/scan` endpoint to close that gap introduced a new problem, found by testing the endpoint against a real running server rather than by reasoning about it: this REST API has no authentication (a separate, already-known, still-open gap). A scan endpoint that accepts an arbitrary filesystem path from the request body means any caller who can reach the API -- not just the person who started the process -- can direct the server to walk any directory it can read. Confirmed directly: `curl -X POST .../api/graph/scan -d '{"projectPath": "/etc"}'` succeeded before any restriction existed. Beyond information disclosure (subsequent unauthenticated `GET /api/graph` calls would expose whatever got scanned), pointing this at a large enough directory tree is also a plain resource-exhaustion vector, independent of whether the scanner recognizes any files there.

## Decision
The endpoint fails closed by default: `dede.scan.allowed-root` (`DEDE_SCAN_ALLOWED_ROOT`) must be explicitly set, or every scan request is rejected with 403. When set, the requested path is resolved to an absolute, normalized path and checked with `Path.startsWith(Path)` (segment-aware, not a raw string prefix match -- rejects the classic `/allowed-root-evil` bypass of a naive string check) against the same normalized allowed root, which also collapses `..` traversal attempts before the comparison. Every other endpoint on this API remains unauthenticated; this ADR does not claim to have solved that, only to have not made it worse with new functionality.

## Consequences
- **Positive**: The endpoint that closes the "empty graph" gap doesn't reopen it as an arbitrary-file-read/DoS vector. Verified against a real running server: unconfigured rejects with 403, configured-and-in-scope succeeds, configured-and-out-of-scope rejects, and a `../../` traversal attempt against the configured root rejects.
- **Negative**: Still no authentication on any endpoint, including this one within its allowed root -- anyone who can reach a server with `DEDE_SCAN_ALLOWED_ROOT` set can trigger scans and read results for anything under that root. The path restriction narrows the blast radius; it does not add access control.
- **Negative**: Fail-closed-by-default means the endpoint does nothing useful out of the box -- a deliberate tradeoff (safe-by-default over convenient-by-default) that requires reading the README or hitting the 403's message to discover the required configuration.
