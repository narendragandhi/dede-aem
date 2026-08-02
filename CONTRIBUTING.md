# Contributing to Dede-Java

Thank you for your interest in contributing to Dede-Java! This document provides guidelines and instructions for contributing.

## Getting Started

### Prerequisites

- Java 21 or higher
- Maven 3.8+
- Docker (optional, for containerized builds)

### Building from Source

```bash
# Clone the repository
git clone https://github.com/narendragandhi/dede-aem.git
cd dede-aem

# Build with Maven
mvn clean package

# Run tests
mvn test

# Build Docker image
docker build -t dede-java .
```

## Development Workflow

### Branch Naming

- `feature/` - New features
- `fix/` - Bug fixes
- `docs/` - Documentation changes
- `refactor/` - Code refactoring

### Commit Messages

Follow conventional commits format:

```
type(scope): description

[optional body]

[optional footer]
```

Types: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`

Example:
```
feat(parser): add HTL template expression parsing

- Parse data-sly-template definitions
- Extract embedded JavaScript expressions
- Link templates to clientlib dependencies
```

## Code Style

### Java Conventions

- Use 4 spaces for indentation
- Follow Oracle Java naming conventions
- Add Javadoc for public APIs
- Maximum line length: 120 characters

### Testing

- Write unit tests for new functionality
- Maintain test coverage above 70%
- Use JUnit 5 and AssertJ

```java
@Test
void shouldDetectOsgiComponent() {
    // Given
    Path testFile = Path.of("src/test/resources/TestComponent.java");

    // When
    List<CodeNode> nodes = scanner.scan(testFile);

    // Then
    assertThat(nodes).hasSize(1);
    assertThat(nodes.get(0).getType()).isEqualTo(NodeType.OSGI_COMPONENT);
}
```

## Adding New Analyzers

### 1. Create the Parser

```java
@Component
public class MyNewParser {

    private final GraphService graphService;

    public MyNewParser(GraphService graphService) {
        this.graphService = graphService;
    }

    public void parse(Path file) {
        // Analysis logic
        CodeNode node = new CodeNode(
            "unique-id",
            "Node Name",
            NodeType.MY_TYPE,
            "Description",
            file.toString()
        );
        graphService.addNode(node);
    }
}
```

### 2. Add Node Types (if needed)

Edit `src/main/java/com/dede/domain/model/NodeType.java`:

```java
public enum NodeType {
    // ... existing types
    MY_NEW_TYPE
}
```

### 3. Add to Scanner

Register your parser in `DedeJavaScanner.java` to be invoked during analysis.

### 4. Add Cloud Readiness Rules (if applicable)

Edit `src/main/resources/forbidden-apis.json`:

```json
{
  "pattern": "my.problematic.Pattern",
  "severity": "CRITICAL",
  "message": "Description of the issue",
  "cloudManagerRule": "CST-XX",
  "remediation": "How to fix it"
}
```

## Adding Security Rules

Security rules are defined in `ForbiddenApiCatalog.java`. Add patterns for:

- Dangerous API usage
- Injection vulnerabilities
- Authentication bypasses
- Cryptographic weaknesses

## Pull Request Process

1. Create a feature branch from `main`
2. Make your changes with tests
3. Run `mvn verify` to ensure all checks pass
4. Update documentation if needed
5. Submit PR with clear description
6. Address review feedback
7. Squash and merge when approved

### PR Checklist

- [ ] Tests added/updated
- [ ] Documentation updated
- [ ] Changelog entry added
- [ ] No new warnings/errors
- [ ] PR description explains the change

## Reporting Issues

### Bug Reports

Include:
- Dede-Java version
- Java version
- Steps to reproduce
- Expected vs actual behavior
- Sample code/project if possible

### Feature Requests

Include:
- Use case description
- Expected behavior
- Any implementation ideas

## Architecture Overview

See [docs/architecture.md](docs/architecture.md) for detailed architecture documentation.

### Key Components

```
src/main/java/com/dede/
├── discovery/        # Parsers and scanners
├── domain/           # Core domain model
│   └── model/        # Node, Relationship types
├── cloud/            # Cloud readiness analysis
├── security/         # Security scanning
└── web/              # REST API and Web UI
```

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.

## Questions?

- Open a GitHub Discussion for general questions
- Open an Issue for bugs or feature requests
- Tag maintainers for urgent matters

Thank you for contributing!
