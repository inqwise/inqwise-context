# Repository Guidelines

## Project Structure & Module Organization
- `pom.xml`: Maven entry point defining Java 21 toolchain, dependencies, and plugins.
- `src/main/java/com/inqwise/context`: Core context storage, transport, and SPI contracts (`spi`, `data` sub-packages).
- `src/main/resources` and `src/main/generated`: Reserved for runtime assets or generated sources; keep generated output out of version control.
- `src/test/java` and `src/test/resources`: JUnit 5 test scaffolding mirroring the main package layout; `src/test/generated` captures code-gen artifacts during integration tests.
- `target/`: Maven build output. Never modify files here manually; clean with `mvn clean` if artifacts drift.

## Build, Test, and Development Commands
- `mvn clean install`: Full build, runs unit tests, aggregates JaCoCo coverage, and produces the deployable JAR.
- `mvn test`: Fast feedback loop for unit and Vert.x extension tests; skips packaging.
- `mvn verify`: Executes the CI-equivalent lifecycle, including enforcer, javadoc, and coverage reports under `target/site/jacoco`.
- `mvn -DskipTests package`: Package-only build when tests are proven externally (do not use for PR validation).

## Coding Style & Naming Conventions
- Follow standard Java conventions: 4-space indentation, UTF-8 encoding, `PascalCase` for types, `camelCase` for members, and package names under `com.inqwise.context`.
- Prefer immutable data objects where practical; favor `var` sparingly to keep types explicit in public APIs.
- Sort imports by IDE default (static after non-static) and avoid wildcard imports. Use `LogManager.getLogger` for new logging surfaces to align with Log4j 2 usage.
- Keep public APIs annotated with clear Javadoc; Maven CI fails if javadocs are malformed.

## Testing Guidelines
- Write tests in `src/test/java` using JUnit Jupiter and, when interacting with Vert.x, the `vertx-junit5` extension.
- Name test classes `<ClassUnderTest>Test` and methods in a `methodUnderTest_condition_expectedOutcome` pattern to match existing history.
- Run `mvn test` locally before pushing; inspect `target/site/jacoco/index.html` to confirm coverage stays on par with main.
- Mock external transports rather than relying on live EventBus endpoints to keep tests deterministic.

## Commit & Pull Request Guidelines
- Use imperative commit subjects (e.g., `Align project metadata and workflows`) and keep them under ~72 characters.
- Reference GitHub issues or Snyk advisories in the body when applicable, and summarize functional impacts plus test evidence.
- Pull requests must describe the change, note any schema or API adjustments, and attach screenshots or logs when affecting operational behavior.
- Ensure CI workflows (`ci.yml`, `release.yml`, `codeql.yml`) stay green; rerun failed jobs locally or via GitHub before requesting review.

## Security & Configuration Tips
- Review `SECURITY.md` before shipping user-facing changes; report suspected vulnerabilities via the documented contact.
- Never commit secrets or proprietary endpoints; prefer environment variables and document defaults in `README.adoc`.
- For new gates or transports, validate that metadata propagation respects policy boundaries and avoid logging sensitive payloads.
