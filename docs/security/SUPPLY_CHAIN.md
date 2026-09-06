# Supply-chain hardening

This document explains what's now enforced automatically, and the **one-time
setup step the maintainer must run locally** (requires network access to
Google/Maven Central, which this change could not perform in a sandboxed
environment without network access).

## What's now enforced

1. **Exact version pinning** — every dependency (AGP, Kotlin plugin,
   BouncyCastle, ZXing, AndroidX, JUnit, etc.) is already pinned to an exact
   version string, never a `+` or range, in `build.gradle` / `app/build.gradle`.
2. **Repository content filtering** (`settings.gradle`) — `google()` may only
   serve `com.android.*` / `androidx.*` / `com.google.android.*` groups, and
   `mavenCentral()` is restricted to the exact groups this project actually
   uses. `repositoriesMode.set(FAIL_ON_PROJECT_REPOS)` also blocks any module
   from quietly adding an untrusted third-party repository. This closes
   **dependency confusion** attacks (a same-named malicious package published
   to a different, unvetted host).
3. **Dependency locking** (`dependencyLocking { lockAllConfigurations() }` in
   `app/build.gradle`, `activateDependencyLocking()` on the buildscript
   classpath in the root `build.gradle`) — once lockfiles are generated (step
   below), Gradle refuses to resolve any dependency, direct or transitive,
   whose version isn't exactly what's recorded. An automatic transitive bump
   (intentional or attacker-triggered) fails the build loudly instead of
   silently changing what ships.
4. **Dependency verification** (`org.gradle.dependency.verification=strict`
   in `gradle.properties`) — goes one level deeper than locking: this checks
   the actual artifact **checksum/signature**, not just the version string,
   so even a same-version artifact swap (compromised mirror, MITM on a build
   agent) is caught.

## One-time setup required (maintainer, with network access)

**Status: not yet done in this repository.** `app/gradle.lockfile`,
the buildscript-classpath lockfile, and `gradle/verification-metadata.xml`
do not exist yet, even though locking and `strict` verification are already
turned on above. Until this step is completed, `android.yml` and
`codeql.yml` will deliberately fail fast at their "Verify supply-chain
bootstrap has run" step (rather than a confusing raw Gradle error) - this is
expected, not a regression.

Two ways to do this:

**Option A — recommended: run the bootstrap workflow.**
`.github/workflows/bootstrap-supply-chain.yml` does this on a
network-enabled GitHub Actions runner (this environment building the
project has none). From the repo's Actions tab, run it via
`workflow_dispatch`. It opens a pull request containing the generated
files - **read `gradle/verification-metadata.xml` line by line before
merging** (confirm every entry is a dependency/group you recognize and
expect); this review step is what makes verification meaningful, so the
workflow intentionally does not auto-commit.

**Option B — manually, from a trusted machine with network access**
(equivalent to what the workflow automates):

```bash
# 1. Generate the dependency lockfiles (creates app/gradle.lockfile and a
#    root-level lockfile for the buildscript classpath).
./gradlew :app:dependencies --write-locks
./gradlew buildEnvironment --write-locks

# 2. Generate the verification metadata (checksums + optionally PGP
#    signatures for every resolved artifact).
./gradlew --write-verification-metadata sha256 help

# Review gradle/verification-metadata.xml by hand before committing -
# this is the step that actually matters. Confirm every new entry is a
# dependency you recognize and expect.
```

After either option, commit/merge:
- `app/gradle.lockfile`
- the root lockfile Gradle writes for the buildscript classpath
- `gradle/verification-metadata.xml`

## CI enforcement

`.github/workflows/android.yml` now runs Gradle with `--write-locks`
disabled and `--locked` implied by the committed lockfiles + `strict`
verification mode, so CI itself will fail (not silently pass) if:
- the lockfiles are missing entirely, or
- a resolved dependency doesn't match the committed lock/verification data.

If CI starts failing after an intentional dependency version bump, re-run the
two commands above locally, review the diff in
`gradle/verification-metadata.xml` line by line, and commit the update
alongside the version bump in the same PR — never as a separate "fix CI" commit,
so the checksum change is always reviewed together with the reason for it.

## What this does NOT cover

- Compromise of the Gradle/AGP/Kotlin toolchain itself before it reaches
  Maven Central (i.e. an upstream project being compromised at its source).
  Locking/verification only protects the link between "what Maven Central
  serves" and "what this build consumes."
- Compromise of the GitHub Actions runner image or `actions/*` marketplace
  actions used in the workflow itself. Pinning those to commit SHAs (rather
  than tags like `@v4`) is a further hardening step not yet done here and is
  worth doing in a follow-up.
