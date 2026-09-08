# SecureKeyboard V40 — GitHub Build & Pre-Audit

## Required repository contents

The repository must contain the complete Android project. The V40 project includes a `gradlew` launcher, but the wrapper JAR is intentionally absent in the supplied archive. GitHub Actions therefore installs Gradle 8.4 explicitly instead of trusting a missing wrapper JAR.

## Pre-audit workflow

`.github/workflows/v40-preaudit.yml` performs:

1. Java 17 setup.
2. Android SDK setup.
3. Gradle 8.4 setup.
4. Static security audits.
5. JVM unit tests.
6. Debug APK build.
7. Release APK build (unsigned unless private CI signing secrets are configured).
8. SHA-256 hashes and audit evidence upload.

### Important supply-chain note

The current project enables strict dependency verification in `gradle.properties`, but the verification metadata file is not yet present. The pre-audit workflow temporarily sets dependency verification to `off` **only for the build/test execution**, so that buildability can be established without pretending that the supply chain is verified.

This is intentionally NOT a release-security approval.

## Supply-chain bootstrap

Run `.github/workflows/dependency-verification-bootstrap.yml` manually. Review the generated `gradle/verification-metadata.xml`, commit it to the repository, and only then change the pre-audit build commands to strict verification.

After metadata is reviewed and committed, use:

`-Dorg.gradle.dependency.verification=strict`

and fail the build if verification metadata is missing or mismatched.

## External security audit package

The external auditor should receive:

- Reviewed source repository commit SHA.
- Release APK hash.
- Debug APK hash if needed for analysis.
- `gradle/verification-metadata.xml` after review.
- SBOM generated in the controlled CI environment.
- Threat model.
- Cryptographic architecture.
- Security test results.
- Known limitations and residual risks.

No document in this repository should claim immunity from Pegasus, OS-level compromise, or a 10/10 security guarantee. Those are outside what an ordinary Android application can prove without independent testing and appropriate platform-level controls.
