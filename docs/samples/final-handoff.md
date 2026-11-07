# RouteMock final handoff

Current state at publication, on 2026-11-06. The check-by-check record is the
[verification report](verification-report.md).

**Status:** the source is RouteMock 0.4.7. The phone still runs 0.4.6, the last build tested on it. No
consent is in force, and test resources are cleaned up. The dashboard is local only and not deployed.
No license is applied.

## Repository

| Item | State |
| --- | --- |
| Repository | `https://github.com/excel16061/RouteMock`, branch `main` |
| Parent repository | History, index, remotes, and configuration unchanged |
| Backup | Made before the repository was created, outside the project |

## Version

| Item | Value |
| --- | --- |
| App source | **0.4.7**, versionCode 11 |
| SDK levels | Compile 36, target 36, minimum 29 |
| Debug APK SHA-256 | `e889a053efac0efd41afdd61d7cea99a641770a1b33d486a52f861f2b31adcef` |
| Release APK SHA-256 | `df5c5c9c6eeb17e62c7de680664b15f872c0f85711dc1dbaa1d0cd3129fe9e99`, unsigned, not installed |
| Installed on the phone | 0.4.6, versionCode 10, SHA-256 `8322e873620ac7f2234aade524c36394686e09b33a2d21705f091b79efd54ab4` |
| Dashboard | 0.1.1, Django 6.1.3 |

Version 0.4.7 adds safer handling of malformed dashboard replies, with no change to playback,
consent, or location code. **It has not been installed on a phone.**

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
| Physical-device checks | 34 | 1 | 7 | 7 PASS on 0.4.6; 27 PASS and 1 FAIL historical |
| Automated unit and backend tests | 388 | 0 | 0 | 1 skipped |
| Static APK inspection | 6 | 0 | 0 | |
| Live-provider checks | 4 | 0 | 0 | 1 on 0.4.6, 3 historical |

These are not added together. The 1 FAIL is the force-stop cleanup limitation below.

## Latest road playback (0.4.6)

| Measurement | Result | Tolerance |
| --- | --- | --- |
| Fixes | 189 over 193.21 s, all mock-flagged | All flagged |
| Worst distance from the road | 0.059 m (mean 0.022 m) | 1 m |
| Turns | 5 bearing changes over 30 degrees, the largest 101.9 | 1 m |
| 120 km/h | +0.001% over 62.72 s, then -0.001% over 35.87 s after resuming | 2% |
| 60 km/h | 16.665 m/s, -0.008% over 43.01 s | 2% |
| 90 km/h | 24.999 m/s, -0.002% over 33.17 s | 2% |
| Distance against speed and moving time | 4926.6 m against 4926.7 m, over 178.20 s moving | 2% |
| Pause | 15 fixes, one position, speed 0.0, no bearing | Exact |
| Largest step against speed x gap | 1.005 | 1.05 |
| Stop cleanup | 0 providers, service, wake lock, and notification | None left |

**Live provider:** `LiveValhallaCheck` passed once on 0.4.6. It returned 4 distinct routes, the
shortest 31.214 km, and a correct failure for points at sea.

## Remaining limitations

**NOT TESTED on a device:**

1. Delivery to another app
2. Low-memory process death
3. Invalid server responses
4. Release APK at runtime
5. A phone restart clearing abandoned test providers
6. Reaching the end of a road route (the longest run covered about 7.5 km of 31.21 km)
7. Android 14 or later

**Force-stop, as separate findings:**

- Simulation does not restart by itself: PASS.
- Test providers removed without reopening the app: **FAIL, a known limitation.** They stay until
  RouteMock is reopened, and a stale mock position stays available as last known location.
- Reopening RouteMock removes them: PASS.

**Also:**

- Evaluation covers one physical Android 13 phone, one played road route, one routing provider, and
  one map tile provider.
- Most device results are historical and were not repeated on 0.4.6.
- Version 0.4.7 has not been installed on a phone.
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

- The receipt holds 31 events and 1 correction. The last event is at 00:08:01 UTC on 2026-11-07.
- The first 29 events and the correction are unchanged, and all 29 stay origin `UNKNOWN`.
- The 0.4.6 run added 2 events, an acceptance of the three required scopes and its withdrawal, both
  `AUTOMATED_TEST`. Neither is anyone's personal acceptance.
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
- Installing 0.4.7 on the phone.
