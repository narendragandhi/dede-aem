package com.dede.osgi;

import com.dede.discovery.OsgiLinker;
import com.dede.osgi.ComponentStateTracker;
import com.dede.domain.GraphAnalyzer;
import com.dede.domain.GraphExporter;
import com.dede.domain.GraphRepository;
import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Strand 2: Advanced OSGi Service Resolution.
 * Bead 2.1 (LdapFilterParser) and Bead 2.3 (ReferenceValidator).
 */
class OsgiServiceResolutionTest {

    // -----------------------------------------------------------------------
    // Bead 2.1: LdapFilterParser
    // -----------------------------------------------------------------------
    @Nested
    class LdapFilterParserTests {

        private LdapFilterParser parser;

        @BeforeEach
        void setUp() { parser = new LdapFilterParser(); }

        @Test
        void parsesSimpleEquality() {
            var node = parser.parse("(vendor=Acme)");
            assertThat(node.matches(Map.of("vendor", "Acme"))).isTrue();
            assertThat(node.matches(Map.of("vendor", "Other"))).isFalse();
        }

        @Test
        void parsesPresenceCheck() {
            var node = parser.parse("(service.ranking=*)");
            assertThat(node.matches(Map.of("service.ranking", "100"))).isTrue();
            assertThat(node.matches(Map.of("other", "value"))).isFalse();
        }

        @Test
        void parsesGteComparison() {
            var node = parser.parse("(service.ranking>=100)");
            assertThat(node.matches(Map.of("service.ranking", "200"))).isTrue();
            assertThat(node.matches(Map.of("service.ranking", "50"))).isFalse();
            assertThat(node.matches(Map.of("service.ranking", "100"))).isTrue();
        }

        @Test
        void parsesAndFilter() {
            var node = parser.parse("(&(vendor=Acme)(tier=premium))");
            assertThat(node.matches(Map.of("vendor", "Acme", "tier", "premium"))).isTrue();
            assertThat(node.matches(Map.of("vendor", "Acme", "tier", "free"))).isFalse();
        }

        @Test
        void parsesOrFilter() {
            var node = parser.parse("(|(vendor=Acme)(vendor=Other))");
            assertThat(node.matches(Map.of("vendor", "Acme"))).isTrue();
            assertThat(node.matches(Map.of("vendor", "Other"))).isTrue();
            assertThat(node.matches(Map.of("vendor", "Unknown"))).isFalse();
        }

        @Test
        void parsesNotFilter() {
            var node = parser.parse("(!(env=test))");
            assertThat(node.matches(Map.of("env", "prod"))).isTrue();
            assertThat(node.matches(Map.of("env", "test"))).isFalse();
        }

        @Test
        void parsesWildcardGlob() {
            var node = parser.parse("(objectClass=com.example.*)");
            assertThat(node.matches(Map.of("objectClass", "com.example.MyService"))).isTrue();
            assertThat(node.matches(Map.of("objectClass", "org.other.Service"))).isFalse();
        }

        @Test
        void nullFilterMatchesAnything() {
            var node = parser.parse(null);
            assertThat(node.matches(Map.of())).isTrue();
            assertThat(node.matches(Map.of("any", "value"))).isTrue();
        }

        @Test
        void malformedFilterReturnsMatchAll() {
            // Fail-open: unparsable filter = match everything (safer than silently dropping)
            var node = parser.parse("not-an-ldap-filter");
            assertThat(node.matches(Map.of())).isTrue();
        }
    }

    // -----------------------------------------------------------------------
    // Bead 2.3: ReferenceValidator
    // -----------------------------------------------------------------------
    @Nested
    class ReferenceValidatorTests {

        private GraphService graphService;
        private ReferenceValidator validator;

        @BeforeEach
        void setUp() {
            GraphRepository repo = new GraphRepository();
            graphService = new GraphService(repo, new GraphAnalyzer(repo), new GraphExporter(repo));
            OsgiLinker linker = new OsgiLinker(graphService);
            validator = new ReferenceValidator(graphService, new LdapFilterParser(), linker);
        }

        /** Adds a component + service interface + CONSUMES edge to graph. */
        private CodeNode addComponent(String id, String name) {
            CodeNode node = new CodeNode(id, name, NodeType.OSGI_COMPONENT, name, null);
            graphService.addNode(node);
            return node;
        }

        private CodeNode addService(String id, String name) {
            CodeNode node = new CodeNode(id, name, NodeType.OSGI_SERVICE, name, null);
            graphService.addNode(node);
            return node;
        }

        private void consumes(CodeNode component, CodeNode service) {
            graphService.addEdge(component, service, RelationshipType.CONSUMES);
        }

        private void provides(CodeNode component, CodeNode service) {
            graphService.addEdge(component, service, RelationshipType.PROVIDES);
        }

        @Test
        void satisfiedReferenceProducesNoViolation() {
            CodeNode comp     = addComponent("comp:A", "ComponentA");
            CodeNode svc      = addService("svc:MyService", "MyService");
            CodeNode provider = addComponent("comp:Provider", "Provider");

            consumes(comp, svc);
            provides(provider, svc);

            assertThat(validator.validate()).isEmpty();
        }

        @Test
        void unsatisfiedMandatoryReferenceIsDetected() {
            CodeNode comp = addComponent("comp:NeedsService", "NeedsService");
            CodeNode svc  = addService("svc:MissingService", "MissingService");

            consumes(comp, svc);
            // No provider registered

            List<ReferenceValidator.ReferenceViolation> violations = validator.validate();
            assertThat(violations)
                .extracting(ReferenceValidator.ReferenceViolation::type)
                .contains(ReferenceValidator.ViolationType.UNSATISFIED_MANDATORY);
        }

        @Test
        void multipleProvidersFor1to1ReferenceIsAmbiguous() {
            CodeNode comp      = addComponent("comp:Consumer", "Consumer");
            CodeNode svc       = addService("svc:SharedSvc", "SharedSvc");
            CodeNode provider1 = addComponent("comp:Prov1", "Provider1");
            CodeNode provider2 = addComponent("comp:Prov2", "Provider2");

            consumes(comp, svc);
            provides(provider1, svc);
            provides(provider2, svc);

            List<ReferenceValidator.ReferenceViolation> violations = validator.validate();
            assertThat(violations)
                .extracting(ReferenceValidator.ReferenceViolation::type)
                .contains(ReferenceValidator.ViolationType.AMBIGUOUS_REFERENCE);
        }

        @Test
        void emptyGraphProducesNoViolations() {
            assertThat(validator.validate()).isEmpty();
        }

        @Test
        void violationMessageContainsComponentAndServiceName() {
            CodeNode comp = addComponent("comp:X", "ComponentX");
            CodeNode svc  = addService("svc:Y", "ServiceY");
            consumes(comp, svc);

            var violations = validator.validate();
            assertThat(violations).isNotEmpty();
            assertThat(violations.get(0).message())
                .contains("ComponentX")
                .contains("ServiceY");
        }

        // --- Bead 2.2: Service Ranking Resolution ---

        @Test
        void highestRankedProviderResolvesAmbiguity() {
            CodeNode comp      = addComponent("comp:C",    "Consumer");
            CodeNode svc       = addService("svc:S",       "SharedSvc");
            CodeNode lowRank   = addComponent("comp:Low",  "LowRankProvider");
            CodeNode highRank  = addComponent("comp:High", "HighRankProvider");

            lowRank.getProperties().put("service.ranking",  "0");
            highRank.getProperties().put("service.ranking", "100");

            consumes(comp, svc);
            provides(lowRank,  svc);
            provides(highRank, svc);

            // With a clear ranking winner, no AMBIGUOUS violation should be raised
            assertThat(validator.validate())
                .extracting(ReferenceValidator.ReferenceViolation::type)
                .doesNotContain(ReferenceValidator.ViolationType.AMBIGUOUS_REFERENCE);
        }

        @Test
        void tiedRankingStillRaisesAmbiguousViolation() {
            CodeNode comp  = addComponent("comp:C2",   "Consumer2");
            CodeNode svc   = addService("svc:S2",      "TiedSvc");
            CodeNode provA = addComponent("comp:PA",   "ProviderA");
            CodeNode provB = addComponent("comp:PB",   "ProviderB");

            // Same ranking AND same service.id → genuinely ambiguous
            provA.getProperties().put("service.ranking", "50");
            provA.getProperties().put("service.id",      "10");
            provB.getProperties().put("service.ranking", "50");
            provB.getProperties().put("service.id",      "10");

            consumes(comp, svc);
            provides(provA, svc);
            provides(provB, svc);

            assertThat(validator.validate())
                .extracting(ReferenceValidator.ReferenceViolation::type)
                .contains(ReferenceValidator.ViolationType.AMBIGUOUS_REFERENCE);
        }

        @Test
        void resolveByRankingPicksHighest() {
            GraphRepository repo2 = new GraphRepository();
            GraphService gs2 = new GraphService(repo2, new GraphAnalyzer(repo2), new GraphExporter(repo2));
            OsgiLinker linker2 = new OsgiLinker(gs2);

            CodeNode a = new CodeNode("a", "A", NodeType.OSGI_COMPONENT, "A", null);
            CodeNode b = new CodeNode("b", "B", NodeType.OSGI_COMPONENT, "B", null);
            CodeNode c = new CodeNode("c", "C", NodeType.OSGI_COMPONENT, "C", null);
            a.getProperties().put("service.ranking", "10");
            b.getProperties().put("service.ranking", "200");
            c.getProperties().put("service.ranking", "50");

            var result = linker2.resolveByRanking(List.of(a, b, c));
            assertThat(result).isPresent();
            assertThat(result.get().provider().getName()).isEqualTo("B");
            assertThat(result.get().ranking()).isEqualTo(200);
        }

        @Test
        void resolveByRankingTieUsesServiceId() {
            GraphRepository repo2 = new GraphRepository();
            GraphService gs2 = new GraphService(repo2, new GraphAnalyzer(repo2), new GraphExporter(repo2));
            OsgiLinker linker2 = new OsgiLinker(gs2);

            CodeNode a = new CodeNode("a", "A", NodeType.OSGI_COMPONENT, "A", null);
            CodeNode b = new CodeNode("b", "B", NodeType.OSGI_COMPONENT, "B", null);
            a.getProperties().put("service.ranking", "0");
            b.getProperties().put("service.ranking", "0");
            a.getProperties().put("service.id", "5");   // lower id wins
            b.getProperties().put("service.id", "10");

            var result = linker2.resolveByRanking(List.of(a, b));
            assertThat(result).isPresent();
            assertThat(result.get().provider().getName()).isEqualTo("A");
        }

        @Test
        void resolveByRankingEmptyListReturnsEmpty() {
            GraphRepository repo2 = new GraphRepository();
            GraphService gs2 = new GraphService(repo2, new GraphAnalyzer(repo2), new GraphExporter(repo2));
            OsgiLinker linker2 = new OsgiLinker(gs2);
            assertThat(linker2.resolveByRanking(List.of())).isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // Bead 2.4: ComponentStateTracker
    // -----------------------------------------------------------------------
    @Nested
    class ComponentStateTrackerTests {

        private GraphService graphService;
        private ReferenceValidator validator;
        private ComponentStateTracker tracker;

        @BeforeEach
        void setUp() {
            GraphRepository repo = new GraphRepository();
            graphService = new GraphService(repo, new GraphAnalyzer(repo), new GraphExporter(repo));
            OsgiLinker linker = new OsgiLinker(graphService);
            validator = new ReferenceValidator(graphService, new LdapFilterParser(), linker);
            tracker = new ComponentStateTracker(validator);
        }

        private CodeNode addComponent(String id, String name) {
            CodeNode node = new CodeNode(id, name, NodeType.OSGI_COMPONENT, name, null);
            graphService.addNode(node);
            return node;
        }

        private CodeNode addService(String id, String name) {
            CodeNode node = new CodeNode(id, name, NodeType.OSGI_SERVICE, name, null);
            graphService.addNode(node);
            return node;
        }

        @Test
        void satisfiedComponentIsActive() {
            CodeNode comp     = addComponent("comp:A", "ComponentA");
            CodeNode svc      = addService("svc:S",   "ServiceS");
            CodeNode provider = addComponent("comp:P", "Provider");
            graphService.addEdge(comp, svc, RelationshipType.CONSUMES);
            graphService.addEdge(provider, svc, RelationshipType.PROVIDES);

            var states = tracker.computeStates();
            assertThat(states.get(comp.getId())).isEqualTo(ComponentStateTracker.ComponentState.ACTIVE);
        }

        @Test
        void unsatisfiedComponentIsDetected() {
            CodeNode comp = addComponent("comp:B", "ComponentB");
            CodeNode svc  = addService("svc:M",   "Missing");
            graphService.addEdge(comp, svc, RelationshipType.CONSUMES);

            var states = tracker.computeStates();
            assertThat(states.get(comp.getId())).isEqualTo(ComponentStateTracker.ComponentState.UNSATISFIED);
        }

        @Test
        void componentWithNoReferencesIsActive() {
            CodeNode comp = addComponent("comp:NoRefs", "Standalone");
            var states = tracker.computeStates();
            assertThat(states.get(comp.getId())).isEqualTo(ComponentStateTracker.ComponentState.ACTIVE);
        }

        @Test
        void emptyGraphReturnsEmptyStates() {
            assertThat(tracker.computeStates()).isEmpty();
        }

        @Test
        void summaryReflectsCorrectCounts() {
            CodeNode comp1    = addComponent("comp:1", "Good");
            CodeNode comp2    = addComponent("comp:2", "Bad");
            CodeNode svc1     = addService("svc:1",   "SvcOk");
            CodeNode svc2     = addService("svc:2",   "SvcMissing");
            CodeNode provider = addComponent("comp:P", "Provider");

            graphService.addEdge(comp1, svc1, RelationshipType.CONSUMES);
            graphService.addEdge(provider, svc1, RelationshipType.PROVIDES);
            graphService.addEdge(comp2, svc2, RelationshipType.CONSUMES);

            var summary = tracker.summarize();
            assertThat(summary.active()).isGreaterThanOrEqualTo(2); // comp1 + provider
            assertThat(summary.unsatisfied()).isEqualTo(1);
        }
    }
}
