# Release Guide

[中文](README.md) | [English](README.en.md)

## Scope

This repository publishes these artifacts:

- `mybatis-like-sharephere-support-common`
- `mybatis-like-sharephere-support-migration`
- `mybatis-like-sharephere-support-spring2-starter`
- `mybatis-like-sharephere-support-spring3-starter`
- `mybatis-like-sharephere-support-bom`

Local development builds do not sign or publish by default. Real publishing is enabled only when `release.publish=true`.

## Prerequisites

Before a real release, make sure these are ready:

1. Maven can access a writable local repo, for example `.m2repo`
2. A GPG key is available for artifact signing
3. Maven `settings.xml` contains a `server` with id `central`
4. The Central credentials/token are valid
5. Project version and changelog/tag are aligned

## Local Commands

Normal local build:

```bash
mvn -Dmaven.repo.local=.m2repo install
```

Release build with signing and deploy:

```bash
mvn -Dmaven.repo.local=.m2repo -Drelease.publish=true deploy
```

Notes:

- Without `release.publish=true`, GPG signing and Central Publishing are skipped
- With `release.publish=true`, the `release-publish` profile in the root `pom.xml` activates both plugins

## settings.xml Example

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>${env.MAVEN_USERNAME}</username>
      <password>${env.MAVEN_PASSWORD}</password>
    </server>
  </servers>
</settings>
```

If you publish locally with GPG, Maven also needs the standard GPG configuration available to the current user.

## GitHub Actions Release

The repository already contains [publish-maven-central.yml](.github/workflows/publish-maven-central.yml).

Current behavior:

1. Push a tag matching `v*`
2. GitHub Actions sets up JDK 17
3. The workflow injects the `central` Maven server and GPG key
4. Maven runs `clean deploy` for starter and migration modules with `-am`

Required GitHub secrets:

- `OSSRH_USERNAME`
- `OSSRH_TOKEN`
- `GPG_PRIVATE_KEY`
- `GPG_PASSPHRASE`

## Suggested Release Checklist

1. Run `mvn -Dmaven.repo.local=.m2repo test`
2. Run `mvn -Dmaven.repo.local=.m2repo -DskipTests package`
3. Verify generated `-sources.jar` and `-javadoc.jar`
4. Confirm the target version is correct in the root `pom.xml`
5. Create and push the release tag if using GitHub Actions
6. Verify the artifact appears in Central after publication

## Troubleshooting

`maven-install-plugin` / `maven-gpg-plugin` download failure:

- This is an environment or repository access problem, not a project build logic problem
- Retry after fixing network or mirror access

GPG signing unexpectedly triggered in local builds:

- Check whether `-Drelease.publish=true` was passed

Central publishing not triggered:

- Confirm the `central` server id exists in `settings.xml`
- Confirm credentials are valid
- Confirm the `release-publish` profile is active
