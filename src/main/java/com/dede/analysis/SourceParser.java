package com.dede.analysis;

import com.dede.core.GraphService;
import com.dede.core.model.CodeNode;
import com.dede.core.model.NodeType;
import com.dede.core.model.RelationshipType;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
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
        NodeType nodeType = type.isClassOrInterfaceDeclaration() && ((com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) type).isInterface() 
                ? NodeType.INTERFACE 
                : NodeType.CLASS;

        CodeNode classNode = new CodeNode("class:" + fullSignature, typeName, nodeType, fullSignature, filePath);
        graphService.addNode(classNode);
        graphService.addEdge(parentPackage, classNode, RelationshipType.CONTAINS);

        // 1. OSGi @Component & @Designate (Configurations)
        type.getAnnotationByName("Component").ifPresent(a -> {
            classNode.setType(NodeType.OSGI_COMPONENT);
            getAttribute(a, "service").ifPresent(val -> {
                String svc = val.replace(".class", "");
                CodeNode svcNode = new CodeNode("svc:" + svc, svc, NodeType.OSGI_SERVICE, svc, null);
                graphService.addNode(svcNode);
                graphService.addEdge(classNode, svcNode, RelationshipType.PROVIDES);
            });
            // Factory detection
            getAttribute(a, "factory").ifPresent(f -> classNode.setType(NodeType.OSGI_CONFIG_FACTORY));
        });

        type.getAnnotationByName("Designate").ifPresent(a -> {
            getAttribute(a, "ocd").ifPresent(ocd -> {
                String configName = ocd.replace(".class", "");
                CodeNode configNode = new CodeNode("cfg:" + configName, configName, NodeType.OSGI_CONFIG, configName, null);
                graphService.addNode(configNode);
                graphService.addEdge(classNode, configNode, RelationshipType.CONFIG_BY);
            });
            getAttribute(a, "factory").ifPresent(f -> {
                if ("true".equals(f)) classNode.setType(NodeType.OSGI_CONFIG_FACTORY);
            });
        });

        // 2. Sling CAConfig Detection
        type.getAnnotationByName("Configuration").ifPresent(a -> {
            classNode.setType(NodeType.SLING_CACONFIG);
            getAttribute(a, "label").ifPresent(label -> classNode.setName("CAConfig: " + label));
        });

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

        // Visit methods for @Reference, @ConfigurationResolver (CAConfig)
        type.getMethods().forEach(method -> visitMethod(method, classNode, filePath));

        // Visit fields for @Reference, @Inject, @ConfigurationBuilder
        type.getFields().forEach(field -> {
            field.getVariables().forEach(v -> {
                String fieldType = v.getTypeAsString();
                
                // OSGi Reference
                boolean isOsgiSvc = field.getAnnotations().stream()
                        .anyMatch(an -> an.getNameAsString().equals("Reference") || an.getNameAsString().equals("OSGiService"));
                
                if (isOsgiSvc) {
                    CodeNode svcNode = new CodeNode("svc:" + fieldType, fieldType, NodeType.OSGI_SERVICE, fieldType, null);
                    graphService.addNode(svcNode);
                    graphService.addEdge(classNode, svcNode, RelationshipType.CONSUMES);
                }

                // CAConfig Builder/Resolver
                boolean isCaConfig = field.getAnnotations().stream()
                        .anyMatch(an -> an.getNameAsString().equals("ConfigurationBuilder") || an.getNameAsString().equals("ConfigurationResolver"));
                
                if (isCaConfig) {
                    // This node is a CAConfig consumer
                    CodeNode caSvc = new CodeNode("svc:sling.caconfig", "Sling CAConfig Resolver", NodeType.OSGI_SERVICE, "org.apache.sling.caconfig", null);
                    graphService.addNode(caSvc);
                    graphService.addEdge(classNode, caSvc, RelationshipType.CONSUMES);
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

        method.getAnnotationByName("Reference").ifPresent(a -> {
            method.getParameters().forEach(p -> {
                String svcType = p.getTypeAsString();
                CodeNode svcNode = new CodeNode("svc:" + svcType, svcType, NodeType.OSGI_SERVICE, svcType, null);
                graphService.addNode(svcNode);
                graphService.addEdge(parentClass, svcNode, RelationshipType.CONSUMES);
            });
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
