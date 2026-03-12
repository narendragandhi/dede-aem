package com.dede.analysis;

import com.dede.core.GraphService;
import com.dede.core.model.CodeNode;
import com.dede.core.model.NodeType;
import com.dede.core.model.RelationshipType;
import com.dede.core.model.AnnotationMapping;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;

@Component
public class SourceParser {

    private final GraphService graphService;
    private final JavaParser javaParser;
    private final ObjectMapper objectMapper;
    private AnnotationMapping customMappings;

    public SourceParser(GraphService graphService) {
        this.graphService = graphService;
        this.javaParser = new JavaParser();
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void init() {
        try {
            File configFile = new File("dede-annotations.json");
            if (configFile.exists()) {
                this.customMappings = objectMapper.readValue(configFile, AnnotationMapping.class);
                System.out.println("Loaded " + customMappings.getMappings().size() + " custom annotation mappings.");
            }
        } catch (IOException e) {
            System.err.println("Failed to load custom annotation mappings: " + e.getMessage());
        }
    }

    public void parse(Path filePath) {
        try {
            ParseResult<CompilationUnit> result = javaParser.parse(filePath);
            if (result.isSuccessful() && result.getResult().isPresent()) {
                CompilationUnit cu = result.getResult().get();
                visitCompilationUnit(cu, filePath.toString());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void visitCompilationUnit(CompilationUnit cu, String filePath) {
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("default");

        CodeNode packageNode = new CodeNode("pkg:" + packageName, packageName, NodeType.PACKAGE, packageName, null);
        graphService.addNode(packageNode);

        cu.getTypes().forEach(type -> visitType(type, packageNode, filePath));
    }

    private void visitType(TypeDeclaration<?> type, CodeNode parentPackage, String filePath) {
        String typeName = type.getNameAsString();
        String fullSignature = parentPackage.getName() + "." + typeName;
        NodeType nodeType = type.isClassOrInterfaceDeclaration() && ((com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) type).isInterface() 
                ? NodeType.INTERFACE 
                : NodeType.CLASS;

        CodeNode classNode = new CodeNode("class:" + fullSignature, typeName, nodeType, fullSignature, filePath);
        graphService.addNode(classNode);
        graphService.addEdge(parentPackage, classNode, RelationshipType.CONTAINS);

        // 1. Core OSGi Support
        type.getAnnotationByName("Component").ifPresent(a -> {
            classNode.setType(NodeType.OSGI_COMPONENT);
            getAttribute(a, "service").ifPresent(val -> {
                String svc = val.replace(".class", "");
                CodeNode svcNode = new CodeNode("svc:" + svc, svc, NodeType.OSGI_SERVICE, svc, null);
                graphService.addNode(svcNode);
                graphService.addEdge(classNode, svcNode, RelationshipType.PROVIDES);
            });
        });

        // 2. Configurable Dynamic Support
        if (customMappings != null) {
            for (AnnotationMapping.Mapping m : customMappings.getMappings()) {
                type.getAnnotationByName(m.getAnnotationName()).ifPresent(a -> {
                    getAttribute(a, m.getAttributeName()).ifPresent(val -> {
                        for (String part : val.split(",")) {
                            String cleanVal = part.trim().replace("\"", "");
                            CodeNode dynamicNode = new CodeNode(m.getIdPrefix() + cleanVal, cleanVal, NodeType.valueOf(m.getNodeType()), cleanVal, null);
                            graphService.addNode(dynamicNode);
                            graphService.addEdge(classNode, dynamicNode, RelationshipType.valueOf(m.getRelationship()));
                        }
                    });
                });
            }
        }

        // 3. Sling Model
        type.getAnnotationByName("Model").ifPresent(a -> {
            classNode.setType(NodeType.SLING_MODEL);
            getAttribute(a, "resourceType").ifPresent(resType -> {
                for (String rt : resType.split(",")) {
                    rt = rt.trim().replace("\"", "");
                    CodeNode resNode = new CodeNode("res:" + rt, rt, NodeType.JCR_RESOURCE_TYPE, rt, null);
                    graphService.addNode(resNode);
                    graphService.addEdge(resNode, classNode, RelationshipType.ADAPTS_TO);
                }
            });
        });

        // Visit methods & fields...
        type.getMethods().forEach(method -> visitMethod(method, classNode, filePath));
        type.getFields().forEach(field -> {
            field.getVariables().forEach(v -> {
                String fieldType = v.getTypeAsString();
                boolean isInjected = field.getAnnotations().stream()
                        .anyMatch(an -> an.getNameAsString().matches("Reference|OSGiService|Inject"));
                if (isInjected) {
                    CodeNode svcNode = new CodeNode("svc:" + fieldType, fieldType, NodeType.OSGI_SERVICE, fieldType, null);
                    graphService.addNode(svcNode);
                    graphService.addEdge(classNode, svcNode, RelationshipType.CONSUMES);
                }
            });
        });
    }

    private void visitMethod(MethodDeclaration method, CodeNode parentClass, String filePath) {
        String methodName = method.getNameAsString();
        String fullSignature = parentClass.getSignature() + "." + methodName + "()";
        CodeNode methodNode = new CodeNode("method:" + fullSignature, methodName, NodeType.METHOD, fullSignature, filePath);
        graphService.addNode(methodNode);
        graphService.addEdge(parentClass, methodNode, RelationshipType.DECLARES);
    }

    private Optional<String> getAttribute(AnnotationExpr a, String attrName) {
        if (a.isNormalAnnotationExpr()) {
            return a.asNormalAnnotationExpr().getPairs().stream()
                    .filter(p -> p.getNameAsString().equals(attrName))
                    .map(p -> p.getValue().toString().replace("\"", ""))
                    .findFirst();
        } else if (a.isSingleMemberAnnotationExpr() && attrName.equals("value")) {
            return Optional.of(a.asSingleMemberAnnotationExpr().getMemberValue().toString().replace("\"", ""));
        }
        return Optional.empty();
    }
}
