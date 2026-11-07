# Protocol observer-delivery-1

Status: run once, on a physical phone (run `4541ad7c-3c1e-4dc6-b55d-a935156d9e1d`). Results are in the
[verification report](../samples/verification-report.md). The criteria below are unchanged.

## Purpose

Close device check D36: whether a separate app receives RouteMock's simulated fixes through Android's
standard GPS provider. It uses the [location observer](../../tools/location-observer/README.md).

An emulator result, if ever produced, must be labeled as emulator evidence and cannot close D36.

## Preconditions

- Installing the observer on the phone needs the owner's approval first. Use `adb install` for the
  observer, and leave RouteMock's data alone.
- Record the source revision, both app versions, both APK hashes, and the Android version.
- Use a synthetic road route of about 0.5 to 1.5 km with at least one turn, at 30 km/h, repeat off.
  Record its geometry and provider as in [road-arrival-1](road-arrival-1.md).
- Use labeled test consent, and withdraw it afterwards. Collect no touch events.
- Expected behavior, declared now: fixes follow the route, then a stationary hold at speed zero at the
  final geometry point until Stop, and no new mock callbacks after Stop.

## Procedure

RouteMock must be on screen to start, and the observer must be on screen to listen, so the observer
misses the first seconds of the session. This is expected. Do not count it as lost delivery.

1. Run a host observer of Android's records as in [road-playback-2](road-playback-2.md), as an
   independent comparison.
2. In the observer, grant precise location when asked. Refuse nothing else, as nothing else is asked.
3. Start the route in RouteMock, then bring the observer to the screen and tap **Start observation**.
4. Leave the observer on screen through arrival, and for at least 15 seconds of endpoint hold.
5. Tap **Read last known** during the hold.
6. Stop RouteMock from its notification, so the observer stays on screen. Tap **Mark RouteMock stopped**
   at once.
7. Keep observing for at least 60 seconds. Then tap **Read last known** again.
8. Tap **Stop observation**, then **Export CSV**, and read the export with `adb`.
9. Check RouteMock's cleanup independently, and withdraw test consent. Remove the host observer and any
   tunnels.

## Acceptance criteria, defined before execution

Compare `callback` rows only, unless a row says otherwise.

| Check | Criterion |
| --- | --- |
| Delivery to another app | At least 30 callbacks reach the observer during the route |
| Mock flag | Every recorded row has the mock flag. Any non-mock fix appears only as a count |
| Road geometry | Worst distance from the selected geometry at most 1 m, measured as in road-playback-2 |
| Speed | Measured speed within 2% of 30 km/h across the moving period, and reported speed matches |
| Endpoint hold | At least 10 callbacks within 1 m of the final geometry point, at speed zero |
| Freshness | Callback age (`age_ms`) reported. Median at most 2,000 ms during the route |
| Stop | No mock callback with a fix time more than 2 seconds after the mark, across at least 60 seconds |
| Cached data | `last_known` rows reported separately, and never counted toward any criterion above |

Report the count of ignored non-mock callbacks after Stop. It shows whether real fixes resumed. It says
nothing about where the phone is, and no coordinates are kept for them. If none arrive, that may simply
mean no real fix was available, for example indoors.

Evaluate each criterion separately as PASS, FAIL, NOT TESTED, or INCONCLUSIVE. Report the actual time
windows, and compare the observer's count with the host observer's.

## Scope of a pass

A pass shows delivery to **one** test app, through `LocationManager` and the GPS provider, on one phone.
It says nothing about apps using Google Play services, or about any app that rejects mock locations.

## Evidence

Create a new run ID. Publish only a reviewed export and sanitized measurements. Keep raw Android output
local and ignored by Git. Do not overwrite earlier evidence. Update D36 only after a physical run, keep
the current and historical results separate, regenerate affected manifests, and scan staged content.
