# Dede AEM Bundle

An OSGi bundle that provides architectural intelligence and dependency analysis directly within AEM.

## Features

- **Live JCR Scanning**: Analyze components, templates, and content directly from the repository
- **Dependency Graph**: Build and query a graph of relationships between AEM artifacts
- **Circular Dependency Detection**: Find OSGi/component cycles that cause startup issues
- **Refactoring Suggestions**: AI-driven recommendations for improving architecture
- **REST API**: Query the graph via Sling Servlets

## Installation

### Method 1: Maven Install

```bash
# Build the bundle
cd dede-aem-bundle
mvn clean install

# Deploy to local AEM (requires running instance on localhost:4502)
mvn clean install -PautoInstallBundle
```

### Method 2: Manual Install

1. Build: `mvn clean package`
2. Go to: http://localhost:4502/system/console/bundles
3. Upload: `target/dede-aem-bundle-1.0.0-SNAPSHOT.jar`

## Configuration

### Service User Mapping

The bundle requires a service user to access the JCR. Create the following configuration:

**PID**: `org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended-dede`

```json
{
  "user.mapping": [
    "com.dede.dede-aem-bundle:dede-scanner=dede-service-user"
  ]
}
```

### Create System User

1. Go to: http://localhost:4502/crx/explorer/index.jsp
2. Create user: `dede-service-user`
3. Grant read access to `/apps` and `/conf`

Or use rep:policy:

```xml
<jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0"
          xmlns:rep="internal">
    <rep:policy jcr:primaryType="rep:ACL">
        <allow jcr:primaryType="rep:GrantACE"
               rep:principalName="dede-service-user"
               rep:privileges="{Name}[jcr:read]"/>
    </rep:policy>
</jcr:root>
```

### OSGi Configuration

**PID**: `com.dede.aem.services.impl.DedeGraphServiceImpl`

| Property | Default | Description |
|----------|---------|-------------|
| `scanPaths` | `/apps,/conf` | JCR paths to scan |
| `maxDepth` | 10 | Maximum traversal depth |
| `excludedPaths` | `/apps/cq,/apps/dam,/apps/wcm` | Paths to skip |

## API Endpoints

All endpoints are under `/bin/dede/`:

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/bin/dede/graph` | Full graph JSON |
| GET | `/bin/dede/graph/stats` | Node/edge counts |
| GET | `/bin/dede/graph/nodes?type=CLASS` | Filter by type |
| GET | `/bin/dede/graph/cycles` | Circular dependencies |
| GET | `/bin/dede/graph/suggestions` | Refactoring tips |
| POST | `/bin/dede/graph/scan?path=/apps/mysite` | Scan JCR path |
| DELETE | `/bin/dede/graph` | Clear graph |

## Usage Examples

### Scan Your Project

```bash
# Scan /apps/wknd
curl -X POST -u admin:admin \
  "http://localhost:4502/bin/dede/graph/scan?path=/apps/wknd&clear=true"

# Response:
# {
#   "success": true,
#   "scannedPath": "/apps/wknd",
#   "nodeCount": 142,
#   "edgeCount": 87,
#   "durationMs": 234
# }
```

### Check for Cycles

```bash
curl -u admin:admin "http://localhost:4502/bin/dede/graph/cycles"

# Response:
# {
#   "count": 0,
#   "cycles": []
# }
```

### Get Refactoring Suggestions

```bash
curl -u admin:admin "http://localhost:4502/bin/dede/graph/suggestions"

# Response:
# {
#   "count": 2,
#   "suggestions": [
#     "REFACTOR [High Coupling]: Component 'page' has 25 connections...",
#     "REVIEW [Orphan Component]: 'test-component' has no connections..."
#   ]
# }
```

### Get Statistics

```bash
curl -u admin:admin "http://localhost:4502/bin/dede/graph/stats"

# Response:
# {
#   "nodeCount": 142,
#   "edgeCount": 87,
#   "cycleCount": 0,
#   "nodesByType": {
#     "JCR_RESOURCE_TYPE": 45,
#     "HTL_FILE": 62,
#     ...
#   }
# }
```

## Node Types

| Type | Description |
|------|-------------|
| `JCR_RESOURCE_TYPE` | Sling resource types (components) |
| `HTL_FILE` | HTL/Sightly templates |
| `SLING_MODEL` | Sling Model classes |
| `OSGI_COMPONENT` | OSGi DS components |
| `OSGI_SERVICE` | OSGi services |
| `JCR_TEMPLATE` | Page templates |
| `CLIENTLIB` | Client libraries |

## Relationship Types

| Type | Description |
|------|-------------|
| `EXTENDS` | Component extends another (proxy) |
| `DEFINES` | Component defines HTL |
| `REFERENCES` | Content references another |
| `ADAPTS_TO` | Sling Model adaptation |
| `PROVIDES` | OSGi service provision |
| `CONSUMES` | OSGi service consumption |

## Compatibility

- AEM 6.5 SP9+
- AEM as a Cloud Service
- Java 11+

## Differences from Standalone

| Feature | Standalone | AEM Bundle |
|---------|------------|------------|
| Source code analysis | ✅ File system | ❌ Not supported |
| JCR content analysis | ❌ Requires export | ✅ Live access |
| Dispatcher analysis | ✅ | ❌ |
| GraphQL API | ✅ | ❌ |
| Web UI | ✅ | ❌ (use REST API) |
| Real-time scanning | ❌ | ✅ |

## Troubleshooting

### Bundle Not Starting

Check: `/system/console/bundles` for import errors.

Common issues:
- Missing service user mapping
- Missing ResourceResolverFactory

### No Nodes Found

Check:
1. Service user has read permissions
2. Scan path exists
3. Components have `jcr:primaryType=cq:Component`

### Access Denied

Ensure authenticated user has access to `/bin/dede/*` paths.

Add to dispatcher config:
```
/filter {
  /0100 { /type "allow" /url "/bin/dede/*" }
}
```

## License

Apache 2.0
