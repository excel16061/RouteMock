# RouteMock final handoff

Current state, on 2026-11-07, after protocol road-arrival-1. The check-by-check record is the
[verification report](verification-report.md).

**Status:** RouteMock 0.4.7 is published and installed on the phone. No consent is in force, and test resources are cleaned up. The dashboard is local only and not deployed.
No license is applied.

## Repository

| Item | State |
| --- | --- |
| Repository | `https://github.com/excel16061/RouteMock`, branch `main` |
| Commit identity | Repository-local Git settings use the GitHub noreply address |
| Parent repository | History, index, remotes, and configuration unchanged |
| Backup | Made before the repository was created, outside the project |

## Version

| Item | Value |
| --- | --- |
| App source | **0.4.7**, versionCode 11 |
| SDK levels | Compile 36, target 36, minimum 29 |
| Debug APK SHA-256 | `e889a053efac0efd41afdd61d7cea99a641770a1b33d486a52f861f2b31adcef` |
| Release APK SHA-256 | `5174eb405d065bf297a4fc4a719ba4308f4c1bd93f0dcf4f79d83877b5b57c66`, unsigned, not installed. This hash differs between clean builds of the same source, so release builds are not byte-reproducible here |
| Installed on the phone | 0.4.7, same hash as the debug APK, installed with `adb install -r` |
| Dashboard | 0.1.1, Django 6.1.3 |

Version 0.4.7 adds safer handling of malformed dashboard replies, with no change to playback,
consent, or location code.

## Build, test, and lint

Run from clean on 0.4.7, with every task forced and no build cache.

| Check | Result |
| --- | --- |
| Gradle | BUILD SUCCESSFUL, 103 of 103 tasks executed |
| Debug and release builds | Both successful |
| Android unit tests | 298 total, 297 passed, 0 failed, 0 errors, 1 skipped |
| Dashboard backend tests | 91 total, 91 passed, 0 failed, 0 skipped |
| Android lint | 0 errors, 8 warnings |
| Django system check | No issues |
| Django migrations | No changes needed |
| Django `check --deploy` | 1 warning: HSTS preload, left off deliberately |

The lint warnings are 5 `GradleDependency`, 2 `AndroidGradlePluginVersion`, and 1 `OldTargetApi`. The
[lint review](../LINT_REVIEW.md) explains each. The skipped test is `LiveValhallaCheck`, which needs a
network and was run separately on 0.4.6.

## Results by kind of evidence

| Kind | PASS | FAIL | NOT TESTED | Other |
| --- | --- | --- | --- | --- |
| Physical-device checks | 36 | 1 | 5 | 9 PASS on 0.4.7; 27 PASS and 1 FAIL historical |
| Automated unit and backend tests | 388 | 0 | 0 | 1 skipped |
| Static APK inspection | 6 | 0 | 0 | |
| Live-provider checks | 5 | 0 | 0 | 1 on 0.4.7, 4 historical |

These are not added together. The 1 FAIL is the force-stop cleanup limitation below.

## Current-build device run (0.4.7)

| Measurement | Result | Tolerance |
| --- | --- | --- |
| Fixes | 127 over 128.10 s, all mock-flagged | All flagged |
| Worst distance from the road | 0.057 m (mean 0.023 m) | 1 m |
| Turns | 5 bearing changes over 30 degrees, the largest 101.9 | 1 m |
| 120 km/h | -0.002% over 60.76 s, then +0.001% over 37.90 s after resuming | 2% |
| 60 km/h | 16.669 m/s, +0.013% over 11.51 s | 2% |
| Distance against speed and moving time | 3543.0 m against 3545.5 m, over 112.12 s moving | 2% |
| Pause | 17 fixes, one position, speed 0.0, no bearing | Exact |
| Largest step against speed x gap | 1.004 | 1.05 |
| Stop cleanup | 0 providers, service, wake lock, and notification | None left |
| Malformed pairing replies | 6 refused, then a valid reply paired | No crash |
| Malformed command replies | 9 served by Stop, while the session ran | No crash, session continues |

The app kept one process ID throughout, and the crash log gained no lines. Replies came from a fake
dashboard over loopback HTTP. The earlier 0.4.6 measurements are kept as history in the
[verification report](verification-report.md).

**Live provider:** one route was calculated on the phone on 0.4.7. `LiveValhallaCheck` last ran on
0.4.6.

## Road arrival (road-arrival-1)

Run `df94be8f-ea33-462e-8872-f434ef499bce` on 0.4.7, on a separate 1.32 km route at 30 km/h. **All seven criteria passed.**

| Check | Observed | Limit |
| --- | --- | --- |
| Delivery | 181 of 181 mock-flagged | All |
| Road geometry | Worst 0.055 m | 1 m |
| Arrival delivery | 0.000 m from the final geometry point | 1 m |
| Completion time | 158.67 s against 158.31 s, +0.35 s; sampling uncertainty about 1 s | 3.17 s |
| Completion state | One arrival, 0 m remaining, no repeat | |
| Endpoint behavior | Stationary hold as declared: 27 fixes over 26.75 s at speed 0.0 | |
| Explicit Stop | No providers, service, wake lock, or notification | |

## Remaining limitations

**NOT TESTED on a device:**

1. Delivery to another app
2. Low-memory process death
3. Release APK at runtime
4. A phone restart clearing abandoned test providers
5. Android 14 or later

**Force-stop, as separate findings:**

- Simulation does not restart by itself: PASS.
- Test providers removed without reopening the app: **FAIL, a known limitation.** They stay until
  RouteMock is reopened, and a stale mock position stays available as last known location.
- Reopening RouteMock removes them: PASS.

**Also:**

- Evaluation covers one physical Android 13 phone, two played road routes, one routing provider, and
  one map tile provider.
- Arrival was observed once, on a 1.32 km route at 30 km/h. The 31.21 km route has not been driven to
  its end.
- Most device results are historical and were not repeated on 0.4.7.
- Malformed replies were tested in the listed shapes, over loopback HTTP, not over HTTPS.
- Device-to-device transfer was not exercised.
- Live traffic is **not configured**.
- The dashboard is local only. The [deployment requirements](../DEPLOYMENT.md) are unresolved, and
  its [limitations](../DASHBOARD_LIMITATIONS.md) stand.

## Consent status

**No consent is in force.** Acceptance is left to the phone's owner.

| Scope | Last event |
| --- | --- |
| Terms | `WITHDRAWN` |
| Privacy | `WITHDRAWN` |
| Mock location | `WITHDRAWN` |
| Dashboard upload | `WITHDRAWN` |

- The receipt holds 35 events and 1 correction. The last event is at 02:35:26 UTC on 2026-11-07.
- The first 29 events and the correction are unchanged, and all 29 stay origin `UNKNOWN`.
- The later test runs each added an acceptance and its withdrawal, all `AUTOMATED_TEST`. None is
  anyone's personal acceptance.
- The consent screen shows four unticked boxes.
- The consent documents are unchanged and remain drafts pending legal review.

## Cleanup status

**Phone:** no mock providers, foreground service, RouteMock wake lock, notification, pairing token,
queued revocation, or `adb reverse` entry. The consent test marker is removed. Auto-rotate, Wi-Fi, and
automatic time zone are on, and the time zone and clock are correct.

**Left in place:** RouteMock's selection as the mock location app, which was not changed, and the
synthetic `RM-test-berlin` route, the only saved route.

**Computer:** no dashboard server, observer, or Gradle daemon is running, and `dashboard/` has no
database.

The local dashboard secret was replaced during this work, after it appeared in a tool session. It was
never in Git, and no account or session depended on it.

## Publication

See the [publication audit](../PUBLICATION_AUDIT.md) and the
[security review](../SECURITY_REVIEW.md). Hashes for this file and the other reports are in
`docs/EVIDENCE_MANIFEST.sha256`.

## Files

| File | Path |
| --- | --- |
| Verification report | `docs/samples/verification-report.md` |
| Final report | `docs/FINAL_REPORT.md` |
| Publication audit | `docs/PUBLICATION_AUDIT.md` |
| Lint review | `docs/LINT_REVIEW.md` |
| Licensing decisions | `docs/LICENSING.md` |
| Dashboard guide | `docs/DASHBOARD_GUIDE.md` |
| Deployment blockers | `docs/DEPLOYMENT.md` |
| Security review | `docs/SECURITY_REVIEW.md` |
| Evidence manifest | `docs/EVIDENCE_MANIFEST.sha256` |
| Debug APK (not published) | `app/build/outputs/apk/debug/app-debug.apk` |

## Your decisions

- Your own consent acceptance, on the phone.
- Software, documentation, and sample data licenses: see [LICENSING.md](../LICENSING.md).
- Legal review of the consent documents.
