# Security Policy

## Supported Versions

We actively support the following versions of inqwise-context with security updates:

| Version | Supported          |
| ------- | ------------------ |
| 1.1.x   | :white_check_mark: |
| 1.0.x   | :white_check_mark: |
| < 1.0   | :x:                |

## Security Scanning

This project uses multiple security scanning tools to identify and address vulnerabilities:

### 🔍 **Automated Security Scans**

- **Snyk**: Dependency scanning is configured in [the dedicated workflow](.github/workflows/snyk.yml). Scans are skipped when `SNYK_TOKEN` is not configured.
- **OWASP Dependency Check**: [The CI workflow](.github/workflows/ci.yml) explicitly includes test, provided, and runtime dependencies, with a configured CVSS failure threshold of 7. Scanner errors fail the job; missing vulnerability data is not a clean scan result. When configured, the scanner reads the NVD key from its environment rather than from Maven command-line arguments.
- **GitHub Dependabot**: [The repository configuration](.github/dependabot.yml) schedules weekly Maven and GitHub Actions update checks.
- **GitHub CodeQL**: [The CodeQL workflow](.github/workflows/codeql.yml) configures source-code analysis.

A passing build or test suite is not evidence that every security scan ran or that no vulnerabilities exist. Review scan reports and skipped jobs separately.

Product-identity corrections are recorded in [the scan triage](docs/security-scans.adoc) and [the exact-version CPE rules](.github/dependency-check-suppressions.xml). They expire and are checked for unused entries in the all-scope advisory scan. They do not suppress the actual Hazelcast member or its bundled Jackson findings. Runtime and provided dependency findings at CVSS 7 or higher block CI; test-only findings remain reported but do not block delivery.

### 📊 **Security Badges**

- [![Known Vulnerabilities](https://snyk.io/test/github/inqwise/inqwise-context/badge.svg)](https://snyk.io/test/github/inqwise/inqwise-context)
- CI pipeline includes security checks on every pull request

## Reporting a Vulnerability

We take security vulnerabilities seriously. If you discover a security vulnerability, please follow these steps:

### 🔒 **For Security-Sensitive Issues**

1. **DO NOT** open a public GitHub issue
2. Email us directly at: **security@inqwise.com**
3. Include the following information:
   - Description of the vulnerability
   - Steps to reproduce the issue
   - Potential impact
   - Suggested fix (if any)

### ⏱️ **Response Timeline**

- **Acknowledgment**: Within 48 hours
- **Initial Assessment**: Within 5 business days
- **Status Updates**: Weekly until resolved
- **Resolution**: Target 30 days for critical issues

### 🏆 **Responsible Disclosure**

- We will acknowledge your contribution in our security advisories
- We may offer recognition on our website or documentation
- For significant vulnerabilities, we may provide a monetary reward

## Security Best Practices

When using inqwise-context in your projects:

### ✅ **Recommended Practices**

1. **Keep Dependencies Updated**
   - Regularly update to the latest version
   - Monitor security advisories
   - Use dependency scanning tools

2. **Input Validation**
   - Validate incoming context metadata at application trust boundaries
   - Sanitize data from untrusted sources
   - Implement proper error handling

3. **Access Control**
   - Limit access to sensitive object structures
   - Use appropriate authentication and authorization; context metadata is not authenticated identity or permission
   - Follow principle of least privilege

### ⚠️ **Security Considerations**

1. **Performance Impact**
   - Bound metadata sizes and the work performed by application-owned codecs
   - Implement appropriate timeouts and limits
   - Monitor resource usage during metadata encoding and decoding

2. **Data Exposure**
   - Be careful when logging or outputting context metadata
   - Sanitize sensitive information in error messages
   - Implement proper data masking for sensitive fields

## Dependency Security

### 📦 **Production Dependencies**

The direct dependency scopes are defined in `pom.xml`:
- Vert.x Core (provided scope)
- Google Guava (provided scope)
- Apache Log4j Core (provided scope; applications supply their logging runtime)
- Apache Log4j API and Apache Commons IO (required)
- Vert.x Codegen (optional build/code-generation dependency)
- Vert.x WebClient and Kafka client (optional, provided scope; applications supply clients for the adapters they use)

The Kafka dependency graph is managed to Snappy 1.1.10.9 and LZ4 Java 1.11.4 for the published upstream [Snappy](https://github.com/xerial/snappy-java/security/advisories/GHSA-gwq9-9ffj-mvrv) and [LZ4](https://github.com/yawkat/lz4-java/security/advisories/GHSA-mcr4-qmvw-px4g) fixes. This does not add either library as a required dependency. Applications supplying their own Kafka client must manage its compression dependencies themselves; this library's Maven dependency management is not automatically inherited by consumers. Brokerless tests exercise actual Kafka-compressed batches with payloads, headers, FlowId codec profiles, removals, and tombstones; they do not establish compatibility with deployed brokers.

### 🧪 **Test Dependencies**

Test dependencies are also monitored but have lower priority for security patches. The blocking scan excludes Maven test scope while retaining compile, runtime, and provided dependencies. A separate nonblocking all-scope scan includes test dependencies and retains their findings, including Hazelcast's bundled Jackson. Both scans retain reports; an advisory scan failure produces a workflow warning and must not be read as a clean scan. Test scope avoids consumer runtime exposure, not all possible risk to CI executing those dependencies.

The cluster test runtime explicitly selects Hazelcast 5.7.0 instead of the cluster manager's transitive 5.6.0. This addresses the published upstream advisories [GHSA-6v25-8wq6-xq4j](https://github.com/hazelcast/hazelcast/security/advisories/GHSA-6v25-8wq6-xq4j) and [GHSA-w294-6q5q-53p8](https://github.com/hazelcast/hazelcast/security/advisories/GHSA-w294-6q5q-53p8). Hazelcast remains test-scoped and is not added to the library's consumer runtime dependencies. This targeted update does not claim to resolve other dependency alerts.

The Elasticsearch test client's OpenTelemetry modules are aligned with the 1.67.0 BOM for the published [tracestate parsing](https://github.com/open-telemetry/opentelemetry-java/security/advisories/GHSA-ccrj-gw8r-854m) and [baggage propagation](https://github.com/open-telemetry/opentelemetry-java/security/advisories/GHSA-rcgg-9c38-7xpx) fixes. OpenTelemetry remains test-scoped. Focused tests exercise trace round-trips and baggage bounds without installing global telemetry or adding a FlowId-to-OpenTelemetry bridge.

## Security Contact

For security-related questions or concerns:

- **Email**: security@inqwise.com
- **Response Time**: Within 48 hours
- **PGP Key**: Available upon request

## Security Updates

Security updates are distributed through:

1. **GitHub Releases**: Security patches in new versions
2. **Security Advisories**: GitHub Security Advisory database
3. **Documentation**: Updates to this security policy
4. **Notifications**: GitHub watch notifications for security updates

---

*This security policy was last updated: November 25, 2026*
