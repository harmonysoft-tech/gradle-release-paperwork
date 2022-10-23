# gradle-release-paperwork

## Overview

Takes care of automatic version update in gradle projects and populating change log.

Example:
  * current project's *build.gradle.kts* has `version = "3.5.1"`
  * last released version in *RELEASE_NOTES.md* is the same (`3.5.1`)
  * the plugin automatically applies version `3.6.1` to the *build.gradle.kts*
  * there were two non-merge git commits after `3.5.1`, they had commit messages `message1` and `message2`
  * the plugin automatically populates *RELEASE_NOTES.md* by the text below:
    ```
    ## v3.6.1 released on 19 Oct 2022 UTC
    * 8dadf86f930b189d5e16457a309e41c62da5e949 - message1
    * 6382b31bc781339d884708196fb87be7e31bb472 - message2
    ```
  * the plugin commits the changes in *build.gradle.kts* and *RELEASE_NOTES.md* into git

## Table of Contents

1. [Overview](#overview)
2. [Table of Contents](#table-of-contents)
3. [How to Use](#how-to-use)
4. [Configuration](#configuration)
    * [Version File Location](#version-file-location)
    * [Version Regex](#version-regex)
    * [Release Notes File Location](#release-notes-file-location)
    * [Additional Release Info](#additional-release-info)
    * [Change Description](#change-description)
    * [Changes Limit](#changes-limit)

## How to Use

The plugin adds `release-paperwork` task to the root project. It does the necessary actions, namely:
1. Collect unreleased changes (git commits)
2. Update current project version if necessary
3. Populate release notes by target commits info

By default, the plugin assumes that [semver](https://semver.org/) is used by the host project and it increments 'minor' version on release. However, sometimes we want to explicitly define a version to use, for example increment a 'patch' version on bugfix release. The plugin uses currently defined version if it's not the same as the last released version. Example:

1. Version `1.0.0` was released
2. New bug fix commit is made
3. Project version is explicitly set as `1.0.1`
4. `release-paperwork` task is called
5. Release notes are populated by the bug fix commit info for version `1.0.1`. Project version is not changed

## Configuration

The plugin uses default settings if no explicit configuration is defined, they should suit the majority of projects. However, it also offers fine-grained configuration for all processing aspects

### Version File Location

Normally project version is defined in root `build.gradle`/`build.gradle.kts`, but it might be set in other place instead, like `buildSrc`, `gradle.properties`, etc. We can instruct the plugin to use any project file as a version info holder. Usually this property goes with [Version Regex](#version-regex) setup:

```
releasePaperwork {
    projectVersionFile.set("gradle.properties")
    projectVersionRegex.set("project.version\\s*=\\s*(.+)")
}
```

*Note: file location is relative to root project's directory*

### Version Regex

Target use-case is described in [Version File Location](#version-file-location), this property allows to define custom regex for current project version extraction from the version file. Default value is `version\s*=\s*['"]([^'"]+)` to cover cases like `version = "1.0.0"`

### Release Notes File Location

By default, the plugin writes release notes to file `RELEASE_NOTES.md` located at the project. However, it's possible to define custom file to use for that via `releaseNotesFile` property (its location is relative to root project's directory):

```
releasePaperwork {
    releaseNotesFile.set("releases.txt")
}
```

### Additional Release Info

Every new version is stated in release notes file as below:

```
## vA.B.C released on 19 Oct 2022 UTC
  * <commit-hash1> feature1
  * ...
  * <commit-hashN> featureN
```

It's possible to add additional information to the release info via `additionalReleaseDescription` property. It should be a no-args lambda which produces text. The produced non-empty text is added after base release info:

```
releasePaperwork {
    additionalReleaseDescription.set {
        ", build id ${System.getenv("CI_BUILD_ID")}"
    }
}
```

Such setup would make release notes look as below:

```
## vA.B.C released on 19 Oct 2022 UTC, build id XYZ
  * <commit-hash1> feature1
  * ...
  * <commit-hashN> featureN
```

### Change Description

Every new version is stated in release notes file as below:

```
## vA.B.C released on 19 Oct 2022 UTC
  * <commit-hash1> feature1
  * ...
  * <commit-hashN> featureN
```

`featureN` here is just commit message. It's possible to customize its representation in the release notes file via `changeDescription` property - it's a lambda which receives default description (commit message) and returns description to use. If returned description is null or an empty string, the whole commit is excluded from release notes. This feature can be used to exclude merge commits. Consider, for example, the following git log (spring boot project):

```
commit 8dadf86f930b189d5e16457a309e41c62da5e949
Merge: 07dd388b58 fcaac2b343
Author: Phillip Webb <pwebb@vmware.com>
Date:   Tue Oct 18 17:15:03 2022 -0700

    Merge branch '2.7.x'

    Closes gh-32778
```

Complete commit message is used as a feature description by default, so, it would result in the following release notes:

```
vA.B.C released on 19 Oct 2022 UTC
  * 8dadf86f930b189d5e16457a309e41c62da5e949 Merge branch '2.7.x' Closes gh-32778
```

We can turn this into something more convenient then:

```
releasePaperwork {
    val mergeRegex = "[Mm]erge branch \\S+".toRegex()
    val ticketRegex = "gh-(\\d+)".toRegex()
    changeDescription.set { commitMessage ->
        val withDroppedMergeInfo = mergeRegex.replace(commitMessage, "").trim()
        ticketRegex.replace(withDroppedMergeInfo) { match ->
            buildString {
                append("[")
                append(match.groupValues[1])
                append("](https://github.com/spring-projects/spring-boot/issues/")
                append(match.groupValues[1])
                append(")")
            }
        }
    }
}
```

It results in release notes content as below:

```
## vA.B.C released on 19 Oct 2022 UTC
  * 8dadf86f930b189d5e16457a309e41c62da5e949 Closes [32778](https://github.com/spring-projects/spring-boot/issues/32778)
```

### Changes Limit

There might be quite a few commits since the last released version (for example, if we apply the plugin to a project with rich changes history). It might not be worth to list all of them into release notes for particular version. By default, the plugin includes only the last 20 commits per version. That can be adjusted via `maxChangesPerRelease` property. Non-positive value means that there is no limit:

```
releasePaperwork {
    maxChangesPerRelease.set(50)
}
```