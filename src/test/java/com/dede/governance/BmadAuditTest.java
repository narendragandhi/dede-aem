package com.dede.knowledge;

import com.dede.domain.GraphService;
import com.dede.domain.GraphRepository;
import com.dede.domain.GraphAnalyzer;
import com.dede.domain.GraphExporter;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BmadAuditTest {

    @Test
    void shouldDetectBmadViolation_WhenCoreDependsOnBusiness() {
        // Arrange
        GraphRepository repo = new GraphRepository();
        GraphService graphService = new GraphService(repo, new GraphAnalyzer(repo), new GraphExporter(repo));
        GovernanceEngine engine = new GovernanceEngine();

        CodeNode coreBundle = new CodeNode("bundle:com.test.core", "Core", NodeType.BUNDLE, "core", null);
        CodeNode bizBundle = new CodeNode("bundle:com.test.biz", "Business", NodeType.BUNDLE, "biz", null);
        
        // Mark them with BMAD metadata
        coreBundle.getProperties().put("bmad-layer", "PLATFORM");
        bizBundle.getProperties().put("bmad-layer", "BUSINESS");

        // Act: Violation - Platform should NEVER depend on Business
        graphService.addEdge(coreBundle, bizBundle, RelationshipType.WIRES_TO);
        
        List<String> violations = engine.auditBmad(graphService.getGraph());

        // Assert
        assertThat(violations).anyMatch(v -> v.contains("BMAD Violation: PLATFORM layer 'Core' cannot depend on BUSINESS layer 'Business'"));
    }
}
