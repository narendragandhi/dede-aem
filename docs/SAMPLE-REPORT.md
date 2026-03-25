# Cloud Readiness Analysis Report

**Project:** acme-aem-project
**Analysis Date:** 2024-01-15 14:32:00 UTC
**Dede Version:** 1.0.0

---

## Executive Summary

| Metric | Value |
|--------|-------|
| **Overall Score** | 62/100 |
| **Cloud Ready** | No |
| **Total Violations** | 47 |
| **Critical/Severe** | 8 |
| **Major/High** | 23 |
| **Minor/Medium** | 16 |

### Deployment Risk Assessment

```
████████████████████░░░░░░░░░░ 62%
```

**Recommendation:** Address all SEVERE/CRITICAL issues before Cloud Manager deployment. MAJOR issues should be resolved for stable operation.

---

## Violation Summary by Category

| Category | Count | Severity | Status |
|----------|-------|----------|--------|
| Legacy Paths | 12 | SEVERE/MAJOR | Blocking |
| Forbidden APIs | 8 | CRITICAL/HIGH | Blocking |
| OSGi Configuration | 15 | MAJOR/MINOR | Warning |
| Package Structure | 7 | MAJOR | Warning |
| Security (ACL) | 5 | MAJOR | Warning |

---

## SEVERE / CRITICAL Issues (Must Fix)

### 1. Forbidden Path: /libs Modification

**Location:** `ui.apps/src/main/content/jcr_root/libs/cq/gui/components/authoring`
**Severity:** SEVERE
**Check:** CloudServicePathCheck

```
Forbidden path in Cloud Service: /libs/cq/gui/components/authoring.
Modification of /libs is forbidden - use /apps overlays instead.
```

**Remediation:**
1. Move content to `/apps/acme/components/authoring`
2. Use Sling Resource Merger for overlays
3. Update component references

---

### 2. Admin Session Usage

**Location:** `core/src/main/java/com/acme/core/services/ContentService.java:145`
**Severity:** CRITICAL
**Check:** ForbiddenApiAnalyzer

```java
// Line 145 - VIOLATION
ResourceResolver resolver = resourceResolverFactory.getAdministrativeResourceResolver(null);
```

**Remediation:**
```java
// Use service user instead
Map<String, Object> params = Map.of(
    ResourceResolverFactory.SUBSERVICE, "acme-content-service"
);
ResourceResolver resolver = resourceResolverFactory.getServiceResourceResolver(params);
```

**Required:** Create service user mapping in OSGi config.

---

### 3. Cloud-Managed OSGi PID

**Location:** `ui.config/src/main/content/jcr_root/apps/acme/osgiconfig/config/org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.cfg.json`
**Severity:** SEVERE
**Check:** OsgiConfigCheck

```
Cloud-managed configuration cannot be customized:
org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl
```

**Remediation:**
- Use `org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended-<name>` instead
- Reference: [Service User Mapping](https://experienceleague.adobe.com/docs/experience-manager-cloud-service/content/security/service-users.html)

---

### 4. Replication Agent Configuration

**Location:** `ui.config/.../com.day.cq.replication.impl.ReplicationAgentImpl-publish.cfg.json`
**Severity:** SEVERE
**Check:** OsgiConfigCheck

```
Deprecated OSGi configuration: com.day.cq.replication.impl.ReplicationAgentImpl.
Replication agents are managed by Cloud Service.
```

**Remediation:**
- Remove this configuration entirely
- Cloud Service manages replication automatically
- Use Sling Content Distribution for custom needs

---

## MAJOR / HIGH Issues (Should Fix)

### 5. Legacy Path: /etc/designs

**Location:** `ui.content/src/main/content/jcr_root/etc/designs/acme`
**Severity:** MAJOR
**Check:** CloudServicePathCheck

```
Legacy path requires migration: /etc/designs/acme -> /apps/acme/clientlibs.
Design pages are deprecated - use clientlibs in /apps.
```

**Remediation:**
1. Create clientlib structure under `/apps/acme/clientlibs`
2. Migrate CSS/JS to new location
3. Update page templates to reference new clientlibs
4. Remove `/etc/designs` content

---

### 6. Deprecated Workflow API

**Location:** `core/src/main/java/com/acme/core/workflow/AssetWorkflowProcess.java:78`
**Severity:** HIGH
**Check:** ForbiddenApiAnalyzer

```java
// Line 78 - VIOLATION
import com.day.cq.workflow.exec.WorkflowProcess;
```

**Remediation:**
```java
// Use Sling Jobs instead
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer;

@Component(service = JobConsumer.class, property = {
    JobConsumer.PROPERTY_TOPICS + "=acme/asset/process"
})
public class AssetJobConsumer implements JobConsumer {
    // Implementation
}
```

---

### 7. Hardcoded Secret in OSGi Config

**Location:** `ui.config/.../com.acme.core.services.ApiServiceImpl.cfg.json`
**Severity:** MAJOR
**Check:** OsgiConfigCheck

```json
{
  "api.key": "sk-1234567890abcdef"  // VIOLATION: Hardcoded secret
}
```

**Remediation:**
```json
{
  "api.key": "$[env:ACME_API_KEY]"
}
```

Then configure in Cloud Manager: Environment > Environment Variables

---

### 8. Service User Mapping to Admin

**Location:** `ui.config/.../org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended-acme.cfg.json`
**Severity:** MAJOR
**Check:** OsgiConfigCheck

```json
{
  "user.mapping": ["com.acme.core:acme-service=admin"]  // VIOLATION
}
```

**Remediation:**
1. Create dedicated service user with minimal permissions
2. Use repo init to create user and set ACLs:

```
create service user acme-content-reader
set ACL for acme-content-reader
    allow jcr:read on /content/acme
end
```

---

## MINOR Issues (Recommended)

### 9. OSGi Config Without Run Mode

**Location:** `ui.config/.../config/com.acme.core.SchedulerConfig.cfg.json`
**Severity:** MINOR
**Check:** OsgiConfigCheck

```
OSGi config without run mode specificity.
Consider using config.author, config.publish, or config.prod folders.
```

**Remediation:**
- Move to `config.publish` if publish-only
- Move to `config.author` if author-only
- Use `config.prod` for production-specific settings

---

### 10. Deprecated Felix SCR Annotation

**Location:** `core/src/main/java/com/acme/core/servlets/DataServlet.java:25`
**Severity:** MINOR
**Check:** DeprecatedAnnotationChecker

```java
// Line 25 - VIOLATION
@org.apache.felix.scr.annotations.Component
```

**Remediation:**
```java
@org.osgi.service.component.annotations.Component(
    service = Servlet.class
)
```

---

## Package Validation Results

| Package | Status | Violations |
|---------|--------|------------|
| acme.ui.apps-1.0.0.zip | Invalid | 5 |
| acme.ui.content-1.0.0.zip | Invalid | 4 |
| acme.ui.config-1.0.0.zip | Warning | 3 |
| acme.all-1.0.0.zip | Invalid | 12 |

### Package: acme.ui.apps-1.0.0.zip

| Path | Severity | Issue |
|------|----------|-------|
| /libs/cq/gui/... | SEVERE | /libs modification forbidden |
| /apps/acme/install/... | MINOR | Consider config folder |

---

## Dependency Analysis

### Forbidden API Usage by Module

```
core/
├── ContentService.java
│   └── getAdministrativeResourceResolver() [CRITICAL]
├── AssetWorkflowProcess.java
│   └── com.day.cq.workflow.* [HIGH]
└── ReplicationHelper.java
    └── com.day.cq.replication.Replicator [HIGH]

ui.apps/
└── /libs/cq/gui/components/authoring [SEVERE]

ui.config/
├── ReplicationAgentImpl [SEVERE]
└── ServiceUserMapperImpl [SEVERE]
```

### Migration Impact Graph

```
/etc/designs/acme (MAJOR)
├── Used by: /content/acme/templates/page
├── Used by: /content/acme/templates/article
└── Affects: 127 pages

/etc/clientlibs/acme (MAJOR)
├── Used by: /apps/acme/components/page
└── Affects: All pages
```

---

## Recommended Migration Order

Based on dependency analysis, migrate in this order:

1. **Week 1: Critical Security**
   - [ ] Replace `loginAdministrative()` with service users
   - [ ] Remove `/libs` modifications

2. **Week 2: OSGi Configuration**
   - [ ] Remove cloud-managed PID configs
   - [ ] Move secrets to Cloud Manager env vars
   - [ ] Fix service user mappings

3. **Week 3: Path Migration**
   - [ ] `/etc/designs` -> `/apps/*/clientlibs`
   - [ ] `/etc/clientlibs` -> `/apps/*/clientlibs`
   - [ ] `/etc/workflow/models` -> `/var/workflow/models`

4. **Week 4: API Migration**
   - [ ] Workflow API -> Sling Jobs
   - [ ] Replication API -> Sling Content Distribution
   - [ ] Felix SCR -> OSGi R7 annotations

---

## Appendix: All Violations

<details>
<summary>Click to expand full violation list (47 items)</summary>

| # | Severity | Category | Location | Description |
|---|----------|----------|----------|-------------|
| 1 | SEVERE | Path | /libs/cq/gui/... | /libs modification |
| 2 | CRITICAL | API | ContentService.java:145 | loginAdministrative |
| 3 | SEVERE | OSGi | ServiceUserMapperImpl.cfg.json | Cloud-managed PID |
| ... | ... | ... | ... | ... |

</details>

---

## Report Metadata

```yaml
analyzer_version: 1.0.0
analysis_duration_ms: 4532
files_scanned: 847
packages_validated: 4
graph_nodes_created: 1247
graph_edges_created: 3891
catalog_version: "1.1"
```

---

*Generated by Dede AEM Cloud Service Readiness Analyzer*
