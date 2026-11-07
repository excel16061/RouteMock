# Final report

Milestone state: RouteMock 0.4.7 and dashboard 0.1.1. The dashboard is not deployed, and no license
is selected. 0.4.7 is installed on the phone. The check-by-check record is the
[verification report](samples/verification-report.md), and current status is in the
[handoff](samples/final-handoff.md).

## Results by kind of evidence

| Kind | What it shows | PASS | FAIL | NOT TESTED | Skipped |
| --- | --- | --- | --- | --- | --- |
| Physical-device checks | Behavior on one real phone | 37 | 1 | 4 | |
| Automated tests | Logic on a computer, with no phone | 388 | 0 | | 1 |
| Static APK inspection | What is inside the built file | 6 | 0 | | |
| Live-provider checks | Responses from the real routing service | 6 | 0 | | |
| Emulator checks | Android 14 emulator, not a phone | 9 | 0 | | |

- **Device checks:** 10 PASS are current, on 0.4.7. The other 27 PASS and the 1 FAIL are historical,
  from 0.4.0 to 0.4.5.
- **The 1 FAIL** is a known limitation: after a force-stop, test providers stay registered until
  RouteMock is reopened.
- **Live-provider checks:** 2 PASS on 0.4.7, and 4 historical.
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

## Road playback on the current build

127 Android-delivered fixes on 0.4.7, all mock-flagged. Worst distance from the road was 0.057 m
through 5 sharp turns. Speed was within 0.013% of the selection at 120 and 60 km/h. Pause held one
position at speed 0.0, resume continued from it, and Stop left nothing behind. The run covered the
first 3.54 km of the 31.21 km route. Meanwhile, a fake dashboard served 9 malformed command replies,
after 6 malformed pairing replies, with no crash and no effect on the session.

## Road arrival

Protocol road-arrival-1 passed all seven criteria on 0.4.7, on a separate 1.32 km route at 30 km/h.
Android delivered the exact final point, arrival came 0.35 s after the expected 158.31 s, with about
1 s of sampling uncertainty, and the app held the destination at zero speed until Stop.

## Delivery to a separate app

Protocol observer-delivery-1 passed all eight criteria. A separately installed test app received 198
fresh mock callbacks through `LocationManager`, matching Android's own record one for one, with 53
during the endpoint hold and none in the 92 seconds after Stop. Cached reads were kept apart and not
counted. This covers one test app only.

## Android 14 emulator

On an Android 14 (API 34) emulator, RouteMock 0.4.7 passed all nine checks: first launch, consent,
refusal before mock-app selection, selection in Developer options, foreground-service startup with the
`specialUse` type, playback through arrival, zero-speed hold, Stop cleanup, and consent withdrawal.
This is emulator evidence. **D42 stays NOT TESTED** for a physical Android 14 or later phone.

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

- One phone, three played routes, one routing provider, and one map tile provider.
- Arrival was observed on two short routes at one speed. The 31.21 km route has not been driven to its
  end.
- Delivery was shown to one test app, through `LocationManager`. Apps using Google Play services are
  untested.
- Most device results are historical and were not repeated on 0.4.7.
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
