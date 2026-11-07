# Protocol road-arrival-1

Status: run once, on RouteMock 0.4.7 (run `df94be8f-ea33-462e-8872-f434ef499bce`). Results are in the [verification report](../samples/verification-report.md). The criteria below are unchanged.

## Purpose

Close device check D41: reaching the destination of a road route. Follow road-playback-2.md for observation and privacy practices. This is a separate full-route check, not a continuation of historical partial runs.

## Preconditions and inputs

- Record source revision, working-tree status, app version, installed APK SHA-256, Android version, and a pseudonymous device label.
- Use synthetic map-selected endpoints and a valid returned road route approximately 0.5–1.5 km long, including at least one turn. Record selected and snapped endpoints, geometry, distance, provider, and response provenance. Retain provider attribution and applicable data terms.
- Set repeat OFF and speed to 30 km/h. Do not change speed or pause in this run. Expected moving duration is road distance / (30 / 3.6) seconds.
- Determine the implementation's documented arrival behavior before running: automatic cleanup or an explicit stationary endpoint hold. Do not change the expected behavior after seeing results.
- Use explicit test consent and record it as automated test acceptance only. Do not access personal content, collect touch events, or log real location.

## Procedure

1. Start an independent host observer of Android-delivered GPS mock fixes before starting RouteMock. Record elapsed-realtime timestamps, coordinates, speed, bearing where present, and mock flags. Keep raw Android diagnostic output local and ignored by Git.
2. Start the selected road route explicitly. Let it run from start to arrival without manual Stop, repeat, route edits, or speed changes.
3. Observe the endpoint and app arrival state. Continue observation for 15 seconds after reported arrival. Record observations rather than inferring delivery from the map marker.
4. If the app holds the endpoint, verify stationary fixes with zero speed. If it automatically cleans up, verify provider/service/wake-lock/notification removal. An observer may miss the last fix: report endpoint delivery INCONCLUSIVE if there is no independent observation, even when the UI reports arrival.
5. Press Stop if any session remains, then verify cleanup independently. Withdraw test consent and remove temporary observers and USB tunnels. Preserve saved user data and local setup.

## Acceptance criteria, defined before execution

| Check | Criterion |
| --- | --- |
| Delivery | Every observed injected fix has the mock flag |
| Road geometry | Maximum observed deviation at most 1 m, using the same coordinate precision and projection method as road-playback-2 |
| Arrival delivery | Independently observed terminal fix within 1 m of the selected geometry's final point, not the unsnapped map pin |
| Completion time | Absolute difference from distance / selected speed at most max(2 seconds, 2% of expected duration), measured from simulation start to arrival with a monotonic clock; report observer sampling uncertainty separately |
| Completion state | One arrival transition, no repeat or restart, distance remaining zero within recorded display precision |
| Endpoint behavior | Matches the predeclared behavior; stationary endpoint delivery has speed zero, or automatic cleanup leaves no active resources |
| Explicit Stop | No mock providers, foreground service, RouteMock wake lock, or notification afterward |

Evaluate each criterion separately as PASS, FAIL, NOT TESTED, or INCONCLUSIVE. Never infer resource cleanup only from missing fixes. Report the actual time window checked. If timing or terminal delivery cannot be observed reliably, retain the gap.

## Evidence and reporting

Create a new run ID. Save sanitized observations, route metadata where permitted, measurements, sampling gaps, expected/observed arrival behavior, and screenshots without real locations or credentials. Record provenance and units. Do not overwrite prior finalized evidence.

Update D41 only after a physical-device run has supporting evidence. Keep current versus historical results separate, and do not count repeated runs as distinct routes. Regenerate affected evidence manifests after final edits; exclude the manifest itself. Scan staged content for secrets before publication.

## Execution boundary

This document is a protocol, not evidence that the phone test passed. Run it through the authorized local ADB session. Do not reboot, uninstall, clear app data, expose network ADB, or modify unrelated projects.
