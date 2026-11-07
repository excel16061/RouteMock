# Reproducing the results

## Environment used

| Item | Version |
| --- | --- |
| Host | macOS on Apple silicon |
| JDK | 17 |
| Android SDK | Platform 36, build-tools 36.0.0 |
| Gradle, Android Gradle Plugin | 9.6.1, 9.5.0 |
| Kotlin | 2.4.21 |
| Python | 3.15.1 |
| Django | 6.1.3 |
| Phone | One REVVL 6 Pro, Android 13 |

Device results were produced from uncommitted source, so they carry no revision identifier. They
apply to the working tree as it stood. The first commit contains 0.4.7, and dependency versions are pinned in `gradle/libs.versions.toml` and
`dashboard/requirements.txt`.

## Automated checks

```sh
cp env.sh.example env.sh        # Then edit the two paths.
source ./env.sh
./gradlew clean :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug --rerun-tasks --no-build-cache

cd dashboard
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python manage.py test core
```

Expected: Android 298 tests, 297 passed, 0 failed, 1 skipped; lint 0 errors, 8 warnings; dashboard
91 tests, all passing. Times and build hashes will differ. The warnings are explained in
[LINT_REVIEW.md](LINT_REVIEW.md).

The skipped test contacts a volunteer service, so run it sparingly:
`ROUTEMOCK_LIVE_ROUTING=1 ./gradlew :app:testDebugUnitTest --tests '*LiveValhallaCheck*' --rerun`.

## Release APK inspection

```sh
APK=app/build/outputs/apk/release/app-release-unsigned.apk
AAPT=$ANDROID_HOME/build-tools/36.0.0/aapt2
$AAPT dump xmltree $APK --file AndroidManifest.xml | grep -E 'debuggable|usesCleartextTraffic'
$AAPT dump resources $APK | grep -A1 network_security_config
```

Then dump the file named by the second command with `dump xmltree`. It should have one
`base-config`, with cleartext off and `system` trust only. In the manifest, expect
`allowBackup=false` with `fullBackupContent` and `dataExtractionRules` present.

## Road playback

Follow [road-playback-2](protocols/road-playback-2.md), using the points in
[synthetic-route.json](samples/synthetic-route.json). The 0.4.6 run reused the saved route and added
a 90 km/h phase. Road geometry is a live response, so a new
run may get a different route as OpenStreetMap changes. Use
[selected-road-geometry.geojson](samples/selected-road-geometry.geojson) to compare with this one.

## Device tests

Install with `adb install -r`, and select RouteMock as the mock location app. Do not touch the
phone during a run. Device checks here used scripted UI automation and scripts outside the
project, so they are described but not shipped, and repeating them means rebuilding that tooling.

Debug builds label consent events as test events while `files/consent-test-mode` exists:
`adb shell run-as com.routemock.app touch files/consent-test-mode`. Remove it afterwards. Anything
done on the phone while it exists gets that label, even by a person.

## Checking evidence

```sh
shasum -a 256 -c docs/EVIDENCE_MANIFEST.sha256
for d in report-export report-export-correction report-export-0.4.6; do
  (cd docs/samples/$d && grep -v '^#' MANIFEST.sha256 | shasum -a 256 -c)
done
```

A match shows files are unchanged from the manifest. It does not show the observations are true,
and it means little unless you hold a copy of the manifest from a trusted source.
