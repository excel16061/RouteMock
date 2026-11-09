# Force-stop and test providers

## Result in brief

Force-stop is several separate findings, and one of them is a FAIL:

| Finding | Phone, Android 13 | Emulator, Android 14 |
| --- | --- | --- |
| **Simulation does not restart by itself** | PASS on 0.4.0, 0.4.5, and 0.4.8 (D16) | PASS on 0.4.7 and 0.4.8 (E10) |
| **Test providers are removed without reopening the app** | **FAIL, known limitation**, on 0.4.0, 0.4.5, and 0.4.8 (D17) | **FAIL, same limitation** (E11) |
| No fresh mock fixes after force-stop | Seen, not a separate check | PASS (E12) |
| Reopening RouteMock removes them | PASS on 0.4.5 and 0.4.8 (D18) | PASS on 0.4.7 and 0.4.8 (E13) |
| Reopening shows the recovery notice | PASS on 0.4.8 (D43) | PASS on 0.4.8 (E16) |
| Playback stays stopped after reopening | Seen on 0.4.8 | PASS (E14) |

Android gives the app no chance to clean up at force-stop, so RouteMock cannot prevent this. What it
can do is clean up the moment it is opened again, and say so clearly. Since 0.4.8 it opens straight
on the recovery notice.

The phone results from 0.4.0 and 0.4.5 are historical. The phone check was repeated on 0.4.8 on
2026-11-08. The emulator results are **emulator evidence only**, not a physical-device result.

## On the phone (historical)

On RouteMock 0.4.5, a REVVL 6 Pro on Android 13, with a stationary session running, then
`am force-stop`, reading Android's location service with no recipient names recorded:

| Time after force-stop | Test providers | Age of newest mock fix | Last known is mock | Recorded `gps` deliveries |
| --- | --- | --- | --- | --- |
| Running | 3 | 0.7 s | Yes | 6 |
| 3 s | 3 | 4.0 s | Yes | 6 |
| 15 s | 3 | 16.0 s | Yes | 6 |
| 30 s | 3 | 31.0 s | Yes | 6 |
| 60 s | 3 | 61.0 s | Yes | 6 |
| 90 s | 3 | 91.0 s | Yes | 6 |
| Relaunched | 0 | None | No | 9 |
| 20 s later | 0 | None | No | 11 |

The delivery figure comes from a rolling log, and during this test RouteMock was the known
listener. No separate app was installed then.

## On the phone, 0.4.8

The same phone on Android 13, with the saved synthetic route `RM-test-berlin` moving when RouteMock was
force-stopped:

| Moment | Test providers | Last known is mock | Service and notification |
| --- | --- | --- | --- |
| Running | 3 | Yes | Yes |
| 3, 30, 60, and 90 s after force-stop | 3 | Yes, aging second for second | None |
| Reopened, 6 s | 0 | No, a real fix | None |
| 30 and 60 s after reopening | 0 | No | None |

Reopening removed the providers about 0.7 s after the launch command and opened on the recovery
notice. Playback did not resume. See the
[verification report](samples/verification-report.md#force-stop-recovery-on-the-phone-048) and
[samples](samples/forcestop-observations-phone-0.4.8.csv).

## On the Android 14 emulator

Android 14 (API 34) emulator, image revision 14, on 2026-11-07. The saved `RM-emulator-test` road
route ran at 30 km/h, and was moving when it was force-stopped. Android's location service was read
from outside the app. A separate observer app, listening to GPS and reading last known, was in the
foreground the whole time.

| Seconds after force-stop (0.4.7) | Test providers | Age of newest mock fix | Last known is mock | Observer mock callbacks |
| --- | --- | --- | --- | --- |
| Running, -4 s | 3 | 0.9 s | Yes | 34 |
| 10 | 3 | 11.0 s | Yes | 39 |
| 22 | 3 | 23.0 s | Yes | 39 |
| 36 | 3 | 37.2 s | Yes | 39 |
| 67 | 3 | 67.3 s | Yes | 39 |
| 97 | 3 | 97.6 s | Yes | 39 |
| 127 | 3 | 127.8 s | Yes | 39 |
| Reopened, +6 s | 0 | None | None cached | |
| New observer session, +2 min | 0 | None | No, a real fix | 0, with 40 real fixes |

The repeat on 0.4.8 matched: 3 providers for the 67 s watched, callbacks fixed at 34, then removal
about 1.1 s after the launch command.

**Normal Stop, for comparison**, on 0.4.7: by the first sample, 7 s after Stop in the notification,
there were 0 providers, last known was a real fix, and the observer then received 57 real fixes and no
mock fix in 60 s.

All samples are in [forcestop-observations-emulator-api34.csv](samples/forcestop-observations-emulator-api34.csv),
with no coordinates.

## What that means

- **The providers stay registered.** Android keeps them after the process is gone, on Android 13
  and 14 alike.
- **No new mock locations are produced.** The newest fix stays the one sent just before the
  force-stop, and its age rises second for second.
- **A stale fix stays available.** Last known stays the final mock fix, flagged as mock. On the
  emulator it still carried the moving speed, 8.333 m/s, and another app read it 6 times. An app
  that ignores the mock flag would see a position that looks live but is frozen.
- **Real location does not come back** while the providers stand.
- **Nothing shows the problem.** Android removes RouteMock's notification at force-stop, so the
  user sees no sign that the providers remain.
- **Reopening RouteMock fixes it.** It removes the providers on launch, Android clears the cached
  last known, and real fixes resume. Playback is not restarted, and the draft route is not
  restored, so Start needs a route loaded again.

## Who controls what

| | Who | Detail |
| --- | --- | --- |
| Cleanup on Stop, errors, or withdrawal | RouteMock | Removes providers, service, and wake lock |
| Cleanup when the system destroys the service | RouteMock | `onDestroy` runs in that case |
| Recording that cleanup may be needed | RouteMock | Saved before providers are added |
| Cleanup on next launch, with a message | RouteMock | Runs at app start whenever that record exists, before any screen |
| Opening on that message | RouteMock | Since 0.4.8, the app opens on Simulation, where the notice is |
| Not restarting by itself | RouteMock | The service is not sticky |
| **Cleanup at the moment of force-stop** | **Prevented by Android** | The process ends with no callback, so no code runs |
| **Cleanup before the user reopens the app** | **Prevented by Android** | A force-stopped app's services, receivers, and alarms do not run until the user opens it |
| Removing a provider whose app is gone | **Android does not do this** | Watched for 90 s on the phone and 127 s on the emulator; longer periods not tested |
| Keeping the notification as a reminder | **Android removes it** | Force-stop clears the app's notifications |

## Recovery

**If RouteMock's notification disappears without you pressing Stop**, assume the test providers
may still be registered.

1. **Open RouteMock.** No action inside it is needed. It removes the providers as it starts. From
   0.4.8 it opens on Simulation with the notice "The previous simulation ended without Stop and was
   not restarted". Tested on the phone (0.4.5 and 0.4.8) and the emulator (0.4.7 and 0.4.8).
2. **Wait for a new real fix**, which can take a while indoors. Until then, apps may show the last
   simulated position.
3. **If the notice says Android refused to remove a provider**, select RouteMock as the mock location
   app again and reopen it, or restart the phone.
4. **If RouteMock cannot be opened**, restart the phone. Test providers are not expected to survive a
   restart, but **this was not tested**.

Uninstalling also ends the app, but that was not tested either, and it deletes saved routes.
