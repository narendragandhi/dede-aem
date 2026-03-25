package com.dede.discovery;

import com.dede.domain.GraphService;
import com.dede.domain.model.*;
import com.dede.exception.ErrorCode;
import com.dede.exception.ParseException;
import com.dede.exception.ConfigurationException;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;

@Component
public class SourceParser implements ProjectParser {

    private static final Logger log = LoggerFactory.getLogger(SourceParser.class);

    private final GraphService graphService;
    private final JavaParser javaParser;
    private final ObjectMapper objectMapper;
    private final List<AnnotationMapping.Mapping> activeMappings = new ArrayList<>();

    public SourceParser(GraphService graphService) {
        this.graphService = graphService;
        this.javaParser = new JavaParser();
        this.objectMapper = new ObjectMapper();
        loadDefaultProfile();
    }

    /**
     * Load the default AEM profile from classpath if profiles directory doesn't exist.
     */
    private void loadDefaultProfile() {
        try {
            File profileFile = new File("profiles/aem.json");
            if (profileFile.exists()) {
                AnnotationMapping mapping = objectMapper.readValue(profileFile, AnnotationMapping.class);
                activeMappings.addAll(mapping.getMappings());
                log.info("Loaded default profile from file: aem ({} mappings)", mapping.getMappings().size());
            } else {
                // Try to load from classpath
                try (InputStream is = getClass().getResourceAsStream("/profiles/aem.json")) {
                    if (is != null) {
                        AnnotationMapping mapping = objectMapper.readValue(is, AnnotationMapping.class);
                        activeMappings.addAll(mapping.getMappings());
                        log.info("Loaded default profile from classpath: aem ({} mappings)", mapping.getMappings().size());
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Could not load default profile: {}", e.getMessage());
        }
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
                    log.info("Loaded profile: {} ({} mappings)", name, mapping.getMappings().size());
                } else {
                    log.warn("Profile not found: {}", name);
                }
            } catch (IOException e) {
                log.error("Failed to load profile {}: {}", name, e.getMessage(), e);
                throw new ConfigurationException(ErrorCode.PROFILE_PARSE_ERROR,
                    "Failed to load profile: " + name, name, e);
            }
        }
    }

    @Override
    public void parse(Path filePath) {
        log.debug("Parsing Java file: {}", filePath);
        try {
            ParseResult<CompilationUnit> result = javaParser.parse(filePath);
            if (result.isSuccessful() && result.getResult().isPresent()) {
                CompilationUnit cu = result.getResult().get();
                visitCompilationUnit(cu, filePath.toString());
                log.trace("Successfully parsed: {}", filePath);
            } else {
                log.warn("Failed to parse Java file: {} - {}", filePath,
                    result.getProblems().isEmpty() ? "Unknown error" : result.getProblems().get(0).getMessage());
            }
        } catch (IOException e) {
            log.error("I/O error while parsing {}: {}", filePath, e.getMessage(), e);
            throw new ParseException(ErrorCode.JAVA_PARSE_ERROR,
                "Failed to parse Java file: " + e.getMessage(), filePath, e);
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
        boolean isInterface = type.isClassOrInterfaceDeclaration() &&
                ((ClassOrInterfaceDeclaration) type).isInterface();
        NodeType nodeType = isInterface ? NodeType.INTERFACE : NodeType.CLASS;

        CodeNode classNode = new CodeNode("class:" + fullSignature, typeName, nodeType, fullSignature, filePath);
        graphService.addNode(classNode);
        graphService.addEdge(parentPackage, classNode, RelationshipType.CONTAINS);

        // Detect extends/implements relationships
        if (type.isClassOrInterfaceDeclaration()) {
            ClassOrInterfaceDeclaration coid = (ClassOrInterfaceDeclaration) type;

            // Handle extends (class inheritance or interface extension)
            for (ClassOrInterfaceType extendedType : coid.getExtendedTypes()) {
                String extendedName = extendedType.getNameAsString();
                CodeNode superNode = new CodeNode("class:" + extendedName, extendedName,
                        isInterface ? NodeType.INTERFACE : NodeType.CLASS, extendedName, null);
                graphService.addNode(superNode);
                graphService.addEdge(classNode, superNode, RelationshipType.EXTENDS);
            }

            // Handle implements (class implementing interfaces)
            for (ClassOrInterfaceType implementedType : coid.getImplementedTypes()) {
                String interfaceName = implementedType.getNameAsString();
                CodeNode interfaceNode = new CodeNode("interface:" + interfaceName, interfaceName,
                        NodeType.INTERFACE, interfaceName, null);
                graphService.addNode(interfaceNode);
                graphService.addEdge(classNode, interfaceNode, RelationshipType.IMPLEMENTS);
            }
        }

        // 1. Core OSGi @Component (always on) - with enhanced service detection
        type.getAnnotationByName("Component").ifPresent(a -> {
            classNode.setType(NodeType.OSGI_COMPONENT);
            // Handle both service = X.class and service = {X.class, Y.class}
            getAttributeValues(a, "service").forEach(svc -> {
                CodeNode svcNode = new CodeNode("svc:" + svc, svc, NodeType.OSGI_SERVICE, svc, null);
                graphService.addNode(svcNode);
                graphService.addEdge(classNode, svcNode, RelationshipType.PROVIDES);
            });
        });

        // 2. Sling @Model annotation - special handling for AEM
        type.getAnnotationByName("Model").ifPresent(a -> {
            classNode.setType(NodeType.SLING_MODEL);

            // Handle resourceType (singular - common in Core Components)
            getAttributeValues(a, "resourceType").forEach(resType -> {
                CodeNode resTypeNode = new CodeNode("resType:" + resType, resType,
                        NodeType.JCR_RESOURCE_TYPE, resType, null);
                graphService.addNode(resTypeNode);
                graphService.addEdge(classNode, resTypeNode, RelationshipType.INSTANTIATED_BY);
            });

            // Handle adapters - what this model can be adapted to
            getAttributeValues(a, "adapters").forEach(adapter -> {
                CodeNode adapterNode = new CodeNode("interface:" + adapter, adapter,
                        NodeType.INTERFACE, adapter, null);
                graphService.addNode(adapterNode);
                graphService.addEdge(classNode, adapterNode, RelationshipType.IMPLEMENTS);
            });

            // Handle adaptables - what can adapt TO this model
            getAttributeValues(a, "adaptables").forEach(adaptable -> {
                CodeNode adaptableNode = new CodeNode("adaptable:" + adaptable, adaptable,
                        NodeType.CLASS, adaptable, null);
                graphService.addNode(adaptableNode);
                graphService.addEdge(adaptableNode, classNode, RelationshipType.ADAPTS_TO);
            });
        });

        // 3. Composed Profile Mappings (for other annotations)
        for (AnnotationMapping.Mapping m : activeMappings) {
            type.getAnnotationByName(m.getAnnotationName()).ifPresent(a -> {
                // If classType is specified, update the class node's type
                if (m.getClassType() != null && !m.getClassType().isEmpty()) {
                    classNode.setType(NodeType.valueOf(m.getClassType()));
                }

                getAttributeValues(a, m.getAttributeName()).forEach(cleanVal -> {
                    if (!cleanVal.isEmpty()) {
                        CodeNode dynamicNode = new CodeNode(m.getIdPrefix() + cleanVal, cleanVal,
                                NodeType.valueOf(m.getNodeType()), cleanVal, null);
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
                String fieldName = v.getNameAsString();

                // OSGi service injections
                boolean isOsgiInjected = field.getAnnotations().stream()
                        .anyMatch(an -> an.getNameAsString().matches("Reference|OSGiService"));
                if (isOsgiInjected) {
                    CodeNode svcNode = new CodeNode("svc:" + fieldType, fieldType, NodeType.OSGI_SERVICE, fieldType, null);
                    graphService.addNode(svcNode);
                    graphService.addEdge(classNode, svcNode, RelationshipType.CONSUMES);
                }

                // Sling Model field injections
                field.getAnnotations().forEach(ann -> {
                    String annName = ann.getNameAsString();
                    switch (annName) {
                        case "ChildResource" -> {
                            String childName = getAttribute(ann, "name").orElse(fieldName);
                            CodeNode childNode = new CodeNode("child:" + childName, childName, NodeType.JCR_RESOURCE_TYPE, childName, null);
                            graphService.addNode(childNode);
                            graphService.addEdge(classNode, childNode, RelationshipType.INJECTS_CHILD);
                        }
                        case "ValueMapValue" -> {
                            String propName = getAttribute(ann, "name").orElse(fieldName);
                            CodeNode propNode = new CodeNode("prop:" + propName, propName, NodeType.FIELD, propName, null);
                            graphService.addNode(propNode);
                            graphService.addEdge(classNode, propNode, RelationshipType.INJECTS_VALUE);
                        }
                        case "Self", "SlingObject", "ScriptVariable" -> {
                            CodeNode reqNode = new CodeNode("sling:" + fieldType, fieldType, NodeType.SLING_MODEL, fieldType, null);
                            graphService.addNode(reqNode);
                            graphService.addEdge(classNode, reqNode, RelationshipType.INJECTS_REQUEST);
                        }
                        case "Inject" -> {
                            // Generic @Inject - could be OSGi service or Sling Model injection
                            CodeNode injectNode = new CodeNode("inject:" + fieldType, fieldType, NodeType.OSGI_SERVICE, fieldType, null);
                            graphService.addNode(injectNode);
                            graphService.addEdge(classNode, injectNode, RelationshipType.CONSUMES, 60); // Lower confidence
                        }
                    }
                });
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

    /**
     * Get all values from an annotation attribute, handling:
     * - String literals: "value"
     * - Class literals: SomeClass.class
     * - Field access: ClassName.CONSTANT
     * - Arrays: {value1, value2}
     */
    private List<String> getAttributeValues(AnnotationExpr a, String attrName) {
        List<String> values = new ArrayList<>();

        Expression expr = null;
        if (a.isNormalAnnotationExpr()) {
            for (var pair : a.asNormalAnnotationExpr().getPairs()) {
                if (pair.getNameAsString().equals(attrName)) {
                    expr = pair.getValue();
                    break;
                }
            }
        } else if (a.isSingleMemberAnnotationExpr() && attrName.equals("value")) {
            expr = a.asSingleMemberAnnotationExpr().getMemberValue();
        }

        if (expr != null) {
            extractValuesFromExpression(expr, values);
        }

        return values;
    }

    /**
     * Extract values from various expression types.
     */
    private void extractValuesFromExpression(Expression expr, List<String> values) {
        if (expr.isArrayInitializerExpr()) {
            // Handle arrays: {X.class, Y.class} or {"value1", "value2"}
            expr.asArrayInitializerExpr().getValues().forEach(v -> extractValuesFromExpression(v, values));
        } else if (expr.isClassExpr()) {
            // Handle class literals: SomeClass.class
            String className = expr.asClassExpr().getType().asString();
            values.add(cleanClassName(className));
        } else if (expr.isFieldAccessExpr()) {
            // Handle field access: ClassName.CONSTANT or SomeClass.class
            FieldAccessExpr fae = expr.asFieldAccessExpr();
            if (fae.getNameAsString().equals("class")) {
                // It's a class literal like TransformerFactory.class
                values.add(cleanClassName(fae.getScope().toString()));
            } else {
                // It's a constant reference like PageImpl.RESOURCE_TYPE
                // Use the full expression as the value (will be a symbolic reference)
                values.add(fae.toString());
            }
        } else if (expr.isStringLiteralExpr()) {
            // Handle string literals: "value"
            values.add(expr.asStringLiteralExpr().getValue());
        } else if (expr.isNameExpr()) {
            // Handle direct name references: CONSTANT
            values.add(expr.asNameExpr().getNameAsString());
        } else {
            // Fallback: use toString and clean up
            String raw = expr.toString();
            String cleaned = raw.replace(".class", "")
                    .replace("\"", "")
                    .replace("{", "")
                    .replace("}", "")
                    .trim();
            if (!cleaned.isEmpty()) {
                values.add(cleaned);
            }
        }
    }

    /**
     * Clean up a class name by removing package qualifiers if present.
     */
    private String cleanClassName(String className) {
        // Remove .class suffix if present
        String clean = className.replace(".class", "");
        // Keep the simple name for readability, but we could keep full name
        return clean;
    }
}
