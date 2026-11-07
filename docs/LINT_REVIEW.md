# Lint review

Android lint on 0.4.7: **0 errors, 8 warnings.** There were 9 before this review. Nothing was
suppressed: the one warning that went away was resolved, and the rest stay visible.

| Warning | Count | Outcome |
| --- | --- | --- |
| `DataExtractionRules` | 1 | **Resolved** in 0.4.6 |
| `OldTargetApi` | 1 | Kept, explained below |
| `GradleDependency` | 5 | Kept, blocked by compileSdk 37 |
| `AndroidGradlePluginVersion` | 2 | Kept, deferred |

## DataExtractionRules

**What it said:** `dataExtractionRules` applies only to Android 12 and later, and the app supports
Android 10, so `fullBackupContent` should also be set.

**What is at stake:** three kinds of data must not leave the phone.

| Data | Stored as | Risk if copied |
| --- | --- | --- |
| Device credential | Encrypted with a Keystore key that does not leave the device | A copy could not be decrypted elsewhere. The app would clear it |
| Consent receipt | Plain JSON | **A restored copy could present consent nobody gave on that device** |
| Saved routes | Plain JSON | Coordinates the user may consider private |

**What was found:** no leak was demonstrated.

| Android version | What applies | Before this review |
| --- | --- | --- |
| 10 and 11 | `allowBackup="false"` | Blocked, but by that one attribute alone |
| 12 and later, cloud backup | `allowBackup="false"` and the rules | Blocked |
| 12 and later, device transfer | The rules. `allowBackup` is ignored | Blocked: every domain excluded |

The built APK kept `allowBackup=false`, Android's package record showed no `ALLOW_BACKUP` flag, and
the app has no external files, and its device-protected storage holds only cache folders.

**What changed anyway:** protection on older versions rested on one attribute with nothing behind
it. Version 0.4.6 adds `res/xml/backup_rules.xml`, which excludes every domain, and references it
from the manifest.

**Regression check:** `BackupRulesTest` requires backup off, both rule files referenced, every domain
excluded, and nothing included. It was confirmed to fail for each of three deliberate breakages:
setting `allowBackup` to true, removing a device-transfer exclusion, and removing an older-Android
exclusion. Gradle did not rerun the test when only these XML files changed, so they are now declared
as test inputs.

**On the phone:** `bmgr backupnow` on 0.4.6 and 0.4.7 returned "Backup is not allowed". Device-to-device
transfer needs a second phone and was **not tested**.

## OldTargetApi

The app has `targetSdk 36` and `compileSdk 36`. Lint reports this because it knows of API 37, so the
app is one level behind, not badly out of date.

Raising it is not a one-line change. It would need the API 37 platform installed, which is a change
to the shared SDK, and testing of each behavior change. The only test phone runs Android 13, so
behavior specific to newer versions could not be exercised there. That is already true of target 36,
which is why Android 14 and later is listed as NOT TESTED.

## GradleDependency

| Lint suggests | Current | Requires | Decision |
| --- | --- | --- | --- |
| compileSdk 37 | 36 | The API 37 platform, not installed | Keep |
| `androidx.core` 1.19.1 | 1.18.0 | compileSdk 37 (read from its AAR metadata) | Blocked |
| `lifecycle-viewmodel-compose` 2.11.0 | 2.10.0 | compileSdk 37 (read from its AAR metadata) | Blocked |
| `lifecycle-runtime-compose` 2.11.0 | 2.10.0 | compileSdk 34, but it shares one version with the line above | Blocked with it |
| Compose BOM 2026.09.00 | 2026.06.01 | compileSdk 37 (2026.08 and later bring Compose UI 1.12) | Blocked |

All five move together, and all depend on one decision: installing API 37.

## AndroidGradlePluginVersion

| Lint suggests | Current | Decision |
| --- | --- | --- |
| Android Gradle Plugin 9.5.1 | 9.5.0 | Deferred. A patch release with no reported need |
| Gradle 9.9.0 | 9.6.1 | Deferred. Toolchain changes would need the evidence rechecked |

## Known vulnerabilities

On 2026-11-06, all 124 packages in the Android release runtime were looked up in
[OSV](https://osv.dev), and all 10 installed dashboard packages were checked with `pip-audit`. **No
known vulnerabilities** were listed. An empty result is not proof that none exist, so repeat this
before any release.

**Recommendation:** make these upgrades together as a separate piece of work, with the checks
repeated afterwards. None is needed for the current build.
