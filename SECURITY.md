# Security Policy

## Supported Versions

We actively support the following versions of inqwise-walker with security updates:

| Version | Supported          |
| ------- | ------------------ |
| 1.1.x   | :white_check_mark: |
| 1.0.x   | :white_check_mark: |
| < 1.0   | :x:                |

## Security Scanning

This project uses multiple security scanning tools to identify and address vulnerabilities:

### 🔍 **Automated Security Scans (All Free Tools)**

- **Snyk (Free Tier)**: Scans dependencies for known vulnerabilities - unlimited for public repos
- **OWASP Dependency Check (Open Source)**: Identifies vulnerable components using CVE database
- **GitHub Dependabot (Free)**: Automated dependency updates with security patches
- **GitHub CodeQL (Free for Public)**: Static analysis for code security issues

### 📊 **Security Badges**

- [![Known Vulnerabilities](https://snyk.io/test/github/inqwise/inqwise-walker/badge.svg)](https://snyk.io/test/github/inqwise/inqwise-walker)
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

When using inqwise-walker in your projects:

### ✅ **Recommended Practices**

1. **Keep Dependencies Updated**
   - Regularly update to the latest version
   - Monitor security advisories
   - Use dependency scanning tools

2. **Input Validation**
   - Validate all input data before walking
   - Sanitize data from untrusted sources
   - Implement proper error handling

3. **Access Control**
   - Limit access to sensitive object structures
   - Use appropriate authentication and authorization
   - Follow principle of least privilege

### ⚠️ **Security Considerations**

1. **Performance Impact**
   - Be aware of potential DoS through deeply nested objects
   - Implement appropriate timeouts and limits
   - Monitor resource usage during walking operations

2. **Data Exposure**
   - Be careful when logging or outputting walked data
   - Sanitize sensitive information in error messages
   - Implement proper data masking for sensitive fields

## Dependency Security

### 📦 **Production Dependencies**

All production dependencies are scanned for vulnerabilities:
- Vert.x Core (provided scope)
- Google Guava (provided scope)
- Apache Log4j API (required)
- Inqwise Difference (provided scope)

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

*This security policy was last updated: October 2024*
