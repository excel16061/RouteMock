# Force-stop and test providers

## Result in brief

Force-stop is two separate findings, and only one of them is a pass:

| Finding | Result |
| --- | --- |
| **Simulation does not restart by itself** | PASS on 0.4.0 and 0.4.5 |
| **Test providers are removed without reopening the app** | **FAIL, a known limitation.** They stay registered until RouteMock is reopened |
| Reopening RouteMock removes them | PASS on 0.4.5 |

Android gives the app no chance to clean up at that moment, so RouteMock cannot fix this. These
results are historical. Force-stop was not repeated on 0.4.6, which differs from 0.4.5 only by
backup rules and its version number.

## What was observed

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

## What that means

- **The providers stay registered.** Android keeps them after the process is gone.
- **No new mock locations are produced.** Fixes came from RouteMock, and the fix age rose second
  for second. No new deliveries were recorded in 90 seconds.
- **The old position stays available as a frozen answer.** Last known stayed the mock fix, flagged
  as mock, so an app asking for last known location can receive a stale simulated position.
- **Real location was not seen in that time.** While the override stands, those providers report
  nothing new. Inferred from the table: no separate app was installed to confirm it.
- **Reopening RouteMock fixes it.** Providers were removed, and deliveries resumed with the mock
  flag gone from last known.

The delivery figure comes from a rolling log, and during this test RouteMock was the known
listener. It shows nothing new was recorded, not that no other app ever read last known.

## Who controls what

| | Who | Detail |
| --- | --- | --- |
| Cleanup on Stop, errors, or withdrawal | RouteMock | Removes providers, service, and wake lock |
| Cleanup when the system destroys the service | RouteMock | `onDestroy` runs in that case |
| Recording that cleanup may be needed | RouteMock | Saved before providers are added |
| Cleanup on next launch, with a message | RouteMock | Runs whenever that record exists |
| Not restarting by itself | RouteMock | The service is not sticky |
| **Cleanup at the moment of force-stop** | **Prevented by Android** | The process ends with no callback, so no code runs |
| **Cleanup before the user reopens the app** | **Prevented by Android** | A force-stopped app's services, receivers, and alarms do not run until the user opens it |
| Removing a provider whose app is gone | **Android does not do this** | Observed for 90 seconds; longer periods not tested |

## Recovery

1. **Open RouteMock.** No action inside it is needed. It removes the providers and reports the
   interruption. This was tested.
2. Wait for a new real fix, which can take a while indoors.
3. If RouteMock cannot be opened, restart the phone. Test providers are not expected to survive a
   restart, but **this was not tested**.

Uninstalling also ends the app, but that was not tested either, and it deletes saved routes.
