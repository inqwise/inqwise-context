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
- **OWASP Dependency Check**: [The CI workflow](.github/workflows/ci.yml) runs a dependency check with a configured CVSS failure threshold of 7.
- **GitHub Dependabot**: [The repository configuration](.github/dependabot.yml) schedules weekly Maven and GitHub Actions update checks.
- **GitHub CodeQL**: [The CodeQL workflow](.github/workflows/codeql.yml) configures source-code analysis.

A passing build or test suite is not evidence that every security scan ran or that no vulnerabilities exist. Review scan reports and skipped jobs separately.

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

### 🧪 **Test Dependencies**

Test dependencies are also monitored but have lower priority for security patches.

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

*This security policy was last updated: November 24, 2026*
