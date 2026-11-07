# Final report

Milestone state: RouteMock 0.4.7 and dashboard 0.1.1. The dashboard is not deployed, and no license
is selected. The last build tested on the phone is 0.4.6. The check-by-check record is the
[verification report](samples/verification-report.md), and current status is in the
[handoff](samples/final-handoff.md).

## Results by kind of evidence

| Kind | What it shows | PASS | FAIL | NOT TESTED | Skipped |
| --- | --- | --- | --- | --- | --- |
| Physical-device checks | Behavior on one real phone | 34 | 1 | 7 | |
| Automated tests | Logic on a computer, with no phone | 388 | 0 | | 1 |
| Static APK inspection | What is inside the built file | 6 | 0 | | |
| Live-provider checks | Responses from the real routing service | 4 | 0 | | |

- **Device checks:** 7 PASS are the latest, on 0.4.6. The other 27 PASS and the 1 FAIL are historical,
  from 0.4.0 to 0.4.5.
- **The 1 FAIL** is a known limitation: after a force-stop, test providers stay registered until
  RouteMock is reopened.
- **Live-provider checks:** 1 PASS on 0.4.6, and 3 historical.
- These kinds of evidence are not added together.

## Build, test, and lint

Run from clean on 0.4.7, with every task forced to execute and no build cache.

| Check | Total | Passed | Failed | Errors | Skipped |
| --- | --- | --- | --- | --- | --- |
| Android unit tests | 298 | 297 | 0 | 0 | 1 |
| Dashboard tests | 91 | 91 | 0 | 0 | 0 |

- Debug and release builds: successful.
- Android lint: 0 errors, 8 warnings: 5 `GradleDependency`, 2 `AndroidGradlePluginVersion`,
  1 `OldTargetApi`. See the [lint review](LINT_REVIEW.md).
- Django system check: no issues. Migration check: no changes needed.

## Latest road playback (0.4.6)

189 Android-delivered fixes on 0.4.6, all mock-flagged. Worst distance from the road was 0.059 m
through 5 sharp turns. Speed was within 0.008% of the selection at 120, 60, and 90 km/h. Pause held
one position at speed 0.0, resume continued from it, and Stop left nothing behind. The run covered the
first 4.93 km of the 31.21 km route.

## Force-stop

| Finding | Result |
| --- | --- |
| Simulation does not restart by itself | PASS |
| Test providers removed without reopening the app | **FAIL, a known limitation** |
| Reopening RouteMock removes them | PASS |

Stale mock data stays available as last known location until then. See
[FORCE_STOP_RECOVERY.md](FORCE_STOP_RECOVERY.md).

## Bugs found by device testing and fixed

| Bug | Fix |
| --- | --- |
| Re-pairing never received remote requests | Polling restarts after pairing |
| Withdrawal never reached the server | Revocation is queued durably and delivered |
| Consent labels could be false | `UNKNOWN` origin and corrections |
| `executed` could be reported early | Reported only once a session runs |
| Required consents could not be withdrawn once gated | Withdraw all on the consent screen |

## Limitations

- One phone, one played route, one routing provider, and one map tile provider.
- No road run reached the destination. The longest covered about 7.5 km of 31.21 km.
- Most device results are historical and were not repeated on 0.4.6.
- Version 0.4.7 has not been installed on a phone.
- Repeated phases are not independent routes or cases.
- The session-level consent check is proven by unit tests, as no phone can reach it directly.
- The test-mode label records whatever happens while the marker exists, including a person's actions.
- Live traffic is not configured.
- The dashboard is local only. See [DASHBOARD_LIMITATIONS.md](DASHBOARD_LIMITATIONS.md),
  [DEPLOYMENT.md](DEPLOYMENT.md), and [THREAT_MODEL.md](THREAT_MODEL.md).

## Pending your decision

- Software, documentation, and data licenses: [LICENSING.md](LICENSING.md).
- Legal review of the consent documents: [legal/README.md](legal/README.md).
- Your own consent acceptance on the phone.
- Installing 0.4.7 on the phone.
