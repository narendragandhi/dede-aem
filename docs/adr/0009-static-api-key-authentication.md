# ADR 009: Static API Key Authentication, Fail-Closed Always

## Status
Accepted

## Context
The REST/GraphQL API had no authentication at all. This was flagged repeatedly across this session -- in ADR 007 (the scan-endpoint path restriction explicitly notes it narrows blast radius but "does not add access control"), and in direct conversation as one of exactly two things blocking this project from being usable as anything beyond a local single-user CLI tool. Confirmed concretely earlier this session: every read endpoint, and briefly an unrestricted scan endpoint accepting an arbitrary filesystem path, were reachable by any caller who could reach the port.

The deployment model that matters here (confirmed directly rather than assumed): a single-operator tool run locally or on a trusted network -- not a multi-tenant service needing per-user identity, roles, or an audit trail. That rules out pulling in `spring-boot-starter-security` and building out user accounts / OAuth2 / OIDC, which would add real weight and its own configuration surface for a threat model this project doesn't have.

## Decision
A single `OncePerRequestFilter` (`ApiKeyAuthFilter`), Spring Boot's default mechanism for registering a servlet filter across an entire application without extra configuration. Requires a key on every request via `X-Dede-Api-Key` or `Authorization: Bearer <key>`, checked with `MessageDigest.isEqual()` (constant-time; a plain `String.equals()` would let response timing leak how many leading characters of a guessed key are correct -- a real side-channel against the one thing gating this API). `/actuator/health` is the only exemption, since the Dockerfile's `HEALTHCHECK` calls it with plain `curl` and has no way to supply a key.

**Fails closed always, with an ergonomic escape hatch**: if `DEDE_API_KEY` isn't configured, the filter generates a random key at startup and logs it once, rather than either (a) rejecting all requests until a key is manually generated and set before first run, or (b) staying open until someone opts in to auth. This is the same pattern Jenkins and Home Assistant use for first-run setup. The API is never silently wide open in any configuration state, but nobody is hard-blocked from a quick local session either.

**Scoped to servlet web applications** (`@ConditionalOnWebApplication(type = SERVLET)`): the Maven plugin bootstraps its own headless Spring context (`MojoBootstrapConfig`, `WebApplicationType.NONE`) that still component-scans `com.dede`. Without this guard, every Maven build would construct the filter bean anyway, generate a key, and print an irrelevant warning about an HTTP server that doesn't exist in that context. Found by actually running the plugin after the first version of this filter, not anticipated in advance.

## Consequences
- **Positive**: Closes the concrete gap demonstrated earlier this session -- verified against a real running server: `/actuator/health` open without a key, every other endpoint (REST, GraphQL, GraphiQL, Swagger UI) rejects with 401 without a valid key, accepts with either header style, `DEDE_API_KEY` overrides key generation correctly.
- **Positive**: Zero new dependencies. `OncePerRequestFilter` and `ConditionalOnWebApplication` are already transitively available via `spring-boot-starter-web`.
- **Negative**: A single shared secret, not per-caller identity -- there's no way to tell which caller made a request, or to revoke one caller's access without rotating the key for everyone. Acceptable for the confirmed single-operator deployment model; would need real user accounts if that model changes.
- **Negative**: No rate limiting on failed auth attempts. A key is 32 random bytes (256 bits of entropy), so brute-forcing it is not practically feasible, but repeated failed attempts aren't throttled or logged anywhere beyond a per-request WARN line.
- **Negative**: The Web UI (static HTML/JS served at `/`) has no login flow to collect and store the key in the browser -- it will load but show empty/broken data until the key is supplied some other way (browser extension, manual header injection). Not fixed here; a real login flow is separate scope.
- **Negative**: Swagger UI and GraphiQL now require the key to actually execute requests (both tools support custom request headers, so this is usable, just not zero-friction) -- deliberately not exempted, since exempting "just the interactive query tools" would have been exactly the kind of partial, easy-to-reason-about-wrong carve-out this session has been finding bugs in elsewhere.
