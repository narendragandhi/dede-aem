package com.dede.discovery;

import com.dede.domain.GraphService;
import com.dede.domain.model.*;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

@Component
public class SourceParser implements ProjectParser {

    private final GraphService graphService;
    private final JavaParser javaParser;
    private final ObjectMapper objectMapper;
    private final List<AnnotationMapping.Mapping> activeMappings = new ArrayList<>();

    public SourceParser(GraphService graphService) {
        this.graphService = graphService;
        this.javaParser = new JavaParser();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public boolean supports(Path path) {
        return path.toString().endsWith(".java");
    }

    /**
     * Load multiple profiles and merge their mappings.
     */
    public void loadProfiles(String[] profileNames) {
        activeMappings.clear();
        for (String name : profileNames) {
            try {
                File profileFile = new File("profiles/" + name + ".json");
                if (!profileFile.exists()) {
                    profileFile = new File(name); // Try direct path
                }
                
                if (profileFile.exists()) {
                    AnnotationMapping mapping = objectMapper.readValue(profileFile, AnnotationMapping.class);
                    activeMappings.addAll(mapping.getMappings());
                    System.out.println("Loaded profile: " + name + " (" + mapping.getMappings().size() + " mappings)");
                } else {
                    System.err.println("Profile not found: " + name);
                }
            } catch (IOException e) {
                System.err.println("Failed to load profile " + name + ": " + e.getMessage());
            }
        }
    }

    @Override
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

        String imports = cu.getImports().stream()
                .map(i -> i.getNameAsString())
                .collect(java.util.stream.Collectors.joining(","));

        cu.getTypes().forEach(type -> {
            visitType(type, packageNode, filePath);
            // Attach imports to the class node for legacy analysis
            String fullSignature = packageName + "." + type.getNameAsString();
            graphService.findNodeById("class:" + fullSignature).ifPresent(n -> {
                n.getProperties().put("imports", imports);
            });
        });
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

        // 1. Core OSGi (always on)
        type.getAnnotationByName("Component").ifPresent(a -> {
            classNode.setType(NodeType.OSGI_COMPONENT);
            getAttribute(a, "service").ifPresent(val -> {
                String svc = val.replace(".class", "");
                CodeNode svcNode = new CodeNode("svc:" + svc, svc, NodeType.OSGI_SERVICE, svc, null);
                graphService.addNode(svcNode);
                graphService.addEdge(classNode, svcNode, RelationshipType.PROVIDES);
            });
        });

        // 2. Composed Profile Mappings
        for (AnnotationMapping.Mapping m : activeMappings) {
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

        // Visit methods & fields...
        type.getMethods().forEach(method -> visitMethod(method, classNode, filePath));

        // Detect manual adaptTo() and getService() calls
        type.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).forEach(call -> {
            String methodName = call.getNameAsString();
            if (methodName.equals("adaptTo") || methodName.equals("getService")) {
                call.getArguments().stream()
                    .filter(arg -> arg.toString().contains(".class"))
                    .findFirst()
                    .ifPresent(arg -> {
                        String target = arg.toString().replace(".class", "");
                        RelationshipType relType = methodName.equals("adaptTo") 
                            ? RelationshipType.DYNAMIC_ADAPTS_TO 
                            : RelationshipType.DYNAMIC_CONSUMES;

                        CodeNode targetNode = new CodeNode("svc:" + target, target, 
                            methodName.equals("adaptTo") ? NodeType.SLING_MODEL : NodeType.OSGI_SERVICE, 
                            target, null);
                        graphService.addNode(targetNode);
                        graphService.addEdge(classNode, targetNode, relType, 70); // Heuristic confidence
                    });
            }
        });

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
