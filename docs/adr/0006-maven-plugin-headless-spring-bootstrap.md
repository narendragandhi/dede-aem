# ADR 006: Maven Plugin Bootstraps Its Own Headless Spring Context

## Status
Accepted

## Context
Dede's ~20-bean object graph (parsers, `GraphService`, analyzers, report generators) is wired via Spring constructor injection, with `DedeApplication` as the `@SpringBootApplication` entry point. A Maven plugin (`dede-maven-plugin`) needs the same object graph to run the same analysis as a build step (`mvn verify`), replacing the shell-scripted `java -jar dede.jar ... | grep CRITICAL` CI pattern the README documents with a real Maven goal.

Two options: hand-wire the ~20 beans manually in the Mojo, or reuse Spring to construct them as it already does for the CLI and REST API. Hand-wiring was rejected -- it would silently drift out of sync as new beans are added, exactly the kind of untested path this session repeatedly found broken elsewhere (JavaParser language level, the reachability engine's edge direction, the SARIF NPE below).

Reusing Spring is not simply a matter of calling `SpringApplication.run(DedeApplication.class, args)` from the Mojo, though. Two collisions surfaced only by actually running the plugin end-to-end, not by reasoning about it:

1. `DedeApplication` defines a `CommandLineRunner` bean that calls `System.exit()` on completion (a fix from earlier this session). If the Mojo's Spring context scanned and picked up `DedeApplication` itself, that bean would run on context startup and `System.exit()` would kill the **consuming project's entire Maven JVM** mid-build, not fail the one Mojo cleanly.
2. `dede-java` pulls in Logback via `spring-boot-starter-logging`. Maven plugins run inside Maven's own SLF4J binding (`maven-slf4j-provider`); Spring's logging bootstrap hard-fails immediately when it finds a competing SLF4J implementation already active (confirmed by actually running `mvn dede:check`, not anticipated in advance).

## Decision
- `MojoBootstrapConfig`: a separate `@SpringBootApplication` class used only by the plugin, with an explicit `@ComponentScan(excludeFilters = ... DedeApplication.class)` so `DedeApplication`'s `@Bean` methods -- including the `System.exit()`-calling `CommandLineRunner` -- are never registered.
- `DedeCheckMojo.execute()` boots this config with `WebApplicationType.NONE`, pulls the needed beans via `context.getBean(...)`, runs the same scan/link/analyze sequence `DedeApplication`'s CLI runner uses, and closes the context in a `finally` block.
- The `dede-java` dependency in `dede-maven-plugin/pom.xml` excludes `spring-boot-starter-logging` to stop Logback from competing with Maven's own SLF4J binding.
- The fail-the-build decision (`checkCritical()`) is extracted into a small method taking a `CloudReadinessReport`, independently unit-testable against a hand-built report -- getting a real forbidden-API rule to trigger through a synthetic project turned out to depend on graph-building details unrelated to whether the Mojo's own gating logic is correct.

## Consequences
- **Positive**: The plugin can't drift out of sync with the real bean graph -- it's the same Spring wiring the CLI and REST API use, not a hand-maintained parallel copy.
- **Positive**: A `System.exit()` call added to any future bean (accidentally or otherwise) can only affect the Mojo's own JVM state via context closure, not kill the consumer's build, as long as that bean lives outside `DedeApplication` -- which is true for every bean except the CLI runner itself.
- **Negative**: Two ways to accidentally reintroduce a hard crash: adding a `System.exit()`-calling bean directly to `MojoBootstrapConfig`-scanned code (not just `DedeApplication`), or removing the logging exclusion. Neither is enforced by a test; both were found empirically, not designed around in advance.
- **Negative**: Booting a full Spring context (even with `WebApplicationType.NONE`) still initializes GraphQL schema inspection and other auto-configuration irrelevant to a one-shot Mojo execution -- real but unmeasured startup cost per Maven build. See also the note on whether Spring Boot was the right architectural choice for a tool with this many embedding contexts (CLI, REST/GraphQL server, Maven plugin, OSGi bundle) -- a plain-library core with thin adapters per context would not have needed either exclusion in the first place.
