package com.dede.analysis;

import com.dede.core.GraphService;
import com.dede.core.model.CodeNode;
import com.dede.core.model.NodeType;
import com.dede.core.model.RelationshipType;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

@Component
public class SourceParser {

    private final GraphService graphService;
    private final JavaParser javaParser;

    public SourceParser(GraphService graphService) {
        this.graphService = graphService;
        this.javaParser = new JavaParser();
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
        NodeType nodeType = type.isClassOrInterfaceDeclaration() && ((ClassOrInterfaceDeclaration) type).isInterface() 
                ? NodeType.INTERFACE 
                : NodeType.CLASS;

        CodeNode classNode = new CodeNode("class:" + fullSignature, typeName, nodeType, fullSignature, filePath);
        graphService.addNode(classNode);
        graphService.addEdge(parentPackage, classNode, RelationshipType.CONTAINS);

        // 1. OSGi R7 @Component & @Designate
        type.getAnnotationByName("Component").ifPresent(a -> {
            classNode.setType(NodeType.OSGI_COMPONENT);
            // Detect provided services
            type.getAnnotationByName("Component").ifPresent(comp -> {
                getAttribute(comp, "service").ifPresent(val -> {
                    String svc = val.replace(".class", "");
                    CodeNode svcNode = new CodeNode("svc:" + svc, svc, NodeType.OSGI_SERVICE, svc, null);
                    graphService.addNode(svcNode);
                    graphService.addEdge(classNode, svcNode, RelationshipType.PROVIDES);
                });
            });
        });

        type.getAnnotationByName("Designate").ifPresent(a -> {
            getAttribute(a, "ocd").ifPresent(ocd -> {
                String configName = ocd.replace(".class", "");
                CodeNode configNode = new CodeNode("cfg:" + configName, configName, NodeType.OSGI_CONFIG, configName, null);
                graphService.addNode(configNode);
                graphService.addEdge(classNode, configNode, RelationshipType.CONFIG_BY);
            });
        });

        // 2. OSGi @ObjectClassDefinition
        type.getAnnotationByName("ObjectClassDefinition").ifPresent(a -> {
            classNode.setType(NodeType.OSGI_CONFIG);
        });

        // 3. Sling Model
        type.getAnnotationByName("Model").ifPresent(a -> {
            classNode.setType(NodeType.SLING_MODEL);
            getAttribute(a, "resourceType").ifPresent(resType -> {
                CodeNode resNode = new CodeNode("res:" + resType, resType, NodeType.JCR_RESOURCE_TYPE, resType, null);
                graphService.addNode(resNode);
                graphService.addEdge(resNode, classNode, RelationshipType.ADAPTS_TO);
            });
        });

        // Visit methods for @Reference and lifecycle
        type.getMethods().forEach(method -> visitMethod(method, classNode, filePath));

        // Visit fields for @Reference, @Inject, @OSGiService
        type.getFields().forEach(field -> {
            field.getVariables().forEach(v -> {
                String fieldType = v.getTypeAsString();
                boolean isInjected = field.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("OSGiService") 
                                || a.getNameAsString().equals("Reference")
                                || a.getNameAsString().equals("Inject"));

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

        // Detect Method-based @Reference (DS Bind methods)
        method.getAnnotationByName("Reference").ifPresent(a -> {
            method.getParameters().forEach(p -> {
                String svcType = p.getTypeAsString();
                CodeNode svcNode = new CodeNode("svc:" + svcType, svcType, NodeType.OSGI_SERVICE, svcType, null);
                graphService.addNode(svcNode);
                graphService.addEdge(parentClass, svcNode, RelationshipType.CONSUMES);
            });
        });

        // Trace Method Calls
        method.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).forEach(mc -> {
            try {
                String calleeSignature = mc.resolve().getQualifiedSignature();
                CodeNode calleeNode = new CodeNode("method:" + calleeSignature, mc.getNameAsString(), NodeType.METHOD, calleeSignature, null);
                graphService.addNode(calleeNode);
                graphService.addEdge(methodNode, calleeNode, RelationshipType.CALLS);
            } catch (Exception e) {
                // Fallback to simple name for unresolved
                String simpleCallee = mc.getNameAsString();
                CodeNode calleeNode = new CodeNode("method:unresolved." + simpleCallee, simpleCallee, NodeType.METHOD, simpleCallee, null);
                graphService.addNode(calleeNode);
                graphService.addEdge(methodNode, calleeNode, RelationshipType.CALLS);
            }
        });
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
