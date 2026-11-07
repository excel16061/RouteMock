# Verification report

**Current build: RouteMock 0.4.7, installed on the phone.** This is the master record of what was
checked, how, and on which build. Evidence of different kinds is kept apart, and results from earlier
builds are marked historical.

**Hardware:** one physical phone, a REVVL 6 Pro on Android 13. Dashboard tests with "two devices"
used two **backend device records** in a test database, never two phones.

**Scope:** three distinct road routes were played: 31.21 km, partly, and 1.32 km and 1.28 km, to
arrival. Each came from one live response. One routing provider (the FOSSGIS Valhalla server) and one map tile provider
(OpenFreeMap) were used. Stationary sessions used a synthetic point. The source was uncommitted, so
no revision identifier exists.

## Totals by kind of evidence

| Kind | What it shows | PASS | FAIL | NOT TESTED | Other |
| --- | --- | --- | --- | --- | --- |
| [Physical-device checks](#physical-device-checks) | Behavior on the one real phone | 37 | 1 | 4 | |
| [Automated tests](#automated-tests) | Logic on a computer, with no phone | 388 | 0 | 0 | 1 skipped |
| [Static APK inspection](#static-apk-inspection) | What is inside the built file | 6 | 0 | 0 | |
| [Live-provider checks](#live-provider-checks) | Responses from the real routing service | 6 | 0 | 0 | |
| [Emulator checks](#emulator-evidence-android-14) | Behavior on an Android 14 emulator, not a phone | 9 | 0 | 0 | |

- **Device split:** 10 PASS on the current 0.4.7 build. The other 27 PASS and the 1 FAIL are historical,
  from 0.4.0 to 0.4.5, and were not repeated on 0.4.7.
- **Live-provider split:** 2 PASS on 0.4.7, and 4 historical.
- **The 1 FAIL** is the force-stop cleanup limitation in check D17. Android gives the app no chance
  to clean up at that moment, so the app cannot fix it, but the outcome is still not a pass.

The evidence kinds are not added together, because a unit test and a phone check do not prove the
same thing.

## Current build: 0.4.7 on the phone

Run on 2026-11-07 from published commit `16611618fedea1d1090a6b9f51d5ee3704e6a269`. The debug APK was
SHA-256 `e889a053efac0efd41afdd61d7cea99a641770a1b33d486a52f861f2b31adcef`, and the file installed on
the phone matched it. It was installed over 0.4.6 with `adb install -r`, and saved routes and the
consent receipt stayed byte-identical. The saved synthetic route was used, so no routing request was
made.

### Road playback

Protocol [road-playback-2](../protocols/road-playback-2.md). Fixes came from Android's location
service, read outside the app. See the [observations](delivery-observations-0.4.7.csv) and
[export](report-export-0.4.7/derived/report.html).

| Measurement | Observed | Tolerance | Result |
| --- | --- | --- | --- |
| Fixes observed | 127 over 128.10 s, all mock-flagged | All flagged | PASS |
| Worst distance from the road | 0.057 m (mean 0.023 m) | 1 m | PASS |
| Turns | 5 bearing changes over 30 degrees, the largest 101.9, worst 0.042 m from the road | 1 m | PASS |
| 120 km/h before pause | 33.333 m/s over 60.76 s, -0.002% | 2% | PASS |
| 120 km/h after resume | 33.334 m/s over 37.90 s, +0.001% | 2% | PASS |
| Change to 60 km/h | 16.669 m/s over 11.51 s, +0.013% | 2% | PASS |
| Distance against speed and moving time | 3543.0 m against 3545.5 m expected, over 112.12 s moving, -0.07% | 2% | PASS |
| Pause | 17 fixes over 15.58 s, one position, speed 0.0, no bearing | Exact | PASS |
| Resume | Continued from the paused position | No jump | PASS |
| Largest step against speed x gap | 1.004 | 1.05 | PASS |
| Stop cleanup | 0 mock providers, service, RouteMock wake lock, and notification | None left | PASS |

**Coverage:** this run covered the first 3.54 km of the route and did not reach the destination. The
60 km/h phase lasted only 11.51 s. The 0.07% distance gap comes from the moment of the speed change;
each constant-speed phase matches to within 0.1 m.

### Dashboard replies

A fake dashboard on the computer, reached over `adb reverse`, served these replies in order and logged
only which one it sent. It never stored request bodies. The app's process ID stayed the same from
consent to Disconnect, and the crash log gained no lines.

| Reply | Served | Outcome |
| --- | --- | --- |
| Pairing: no credential | Yes | Refused, not paired |
| Pairing: credential containing a line break and an injected header | Yes | Refused, not paired |
| Pairing: credential too short | Yes | Refused, not paired |
| Pairing: credential is a number | Yes | Refused, not paired |
| Pairing: not JSON | Yes | Refused, not paired |
| Pairing: HTTP 500 | Yes | Refused, not paired |
| Pairing: valid | Yes | Paired |
| Commands: `commands` is a number, an object, or a list of non-objects | Yes (3) | Ignored, session continued |
| Commands: field missing, array at the root, or not JSON | Yes (3) | Ignored, session continued |
| Commands: a command with bad fields | Yes | Ignored, no request shown |
| Commands: HTTP 500 | Yes | Ignored, session continued |
| Commands: 9 MiB body, over the 8 MiB limit | Yes | Abandoned, session continued |
| Session summary: reply not JSON | Yes | Ignored |

All 9 bad command replies arrived by the time of Stop, while the session ran. Reply times were not
logged, so the first may have come just before Start. **Limits:** these replies came over loopback
HTTP, not HTTPS, and they are the listed shapes only, not every possible bad reply.

### Road arrival (protocol road-arrival-1)

Run `df94be8f-ea33-462e-8872-f434ef499bce` under [road-arrival-1](../protocols/road-arrival-1.md) on 2026-11-07, at source revision
`c551ce7`, with a clean working tree. The installed APK was the same `e889a053...` build. Device
label: Test phone A.

This was a **separate full run on a new route**, not a continuation of earlier partial runs. See the
[observations](delivery-observations-road-arrival-1.csv),
[geometry](road-arrival-1-geometry.geojson), and
[export](report-export-road-arrival-1/derived/report.html).

| Input | Value |
| --- | --- |
| Requested start and destination | 52.480628, 13.318309 and 52.480058, 13.329752 |
| Route start and end (snapped) | 52.480622, 13.31891 and 52.480101, 13.329937, which are 40.7 m and 13.4 m from the pins |
| Route | 1319.29 m, 8 turns over 30 degrees, the only route returned |
| Provider | Valhalla on the FOSSGIS public server, one live request |
| Speed, repeat | 30 km/h, off. No speed change, pause, or manual Stop before arrival |
| Expected duration | 158.31 s |
| Expected arrival behavior | Explicit stationary endpoint hold, declared at 02:20:10 UTC, before the route was chosen |

| Check | Criterion | Observed | Result |
| --- | --- | --- | --- |
| Delivery | Every fix has the mock flag | 181 of 181 | PASS |
| Road geometry | At most 1 m | Worst 0.055 m, mean 0.020 m | PASS |
| Arrival delivery | Terminal fix within 1 m of the final geometry point | 0.000 m at six decimal places | PASS |
| Completion time | Within 3.17 s of 158.31 s | 158.67 s, +0.35 s | PASS |
| Completion state | One arrival, no repeat, zero remaining | Moving, then Arrived, once; 0 m and 0:00:00 remaining | PASS |
| Endpoint behavior | Stationary hold at speed zero | 27 fixes over 26.75 s, one position, speed 0.0, no bearing | PASS |
| Explicit Stop | Nothing left afterwards | 0 providers, service, wake lock, and notification, checked 5 s after Stop | PASS |

**Timing:** measured from Android's elapsed-realtime fix timestamps, from the first observed fix
(0.0 m along the road) to the first terminal fix. **Sampling uncertainty is about 1 s**, the interval
between fixes. Extrapolating from the last moving fix, 5.71 m short of the end, gives 158.31 s. The
longest gap between fixes was 1.09 s. The first fix came 2.73 s after the Start tap, and this delay is
not part of the measured time.

**Time window:** observation continued for 25.1 s after the app first showed arrival. During the hold,
the session stayed active: 3 providers, the service, and the wake lock, as declared. App status was
sampled every 1 to 2 s, so a very brief extra state could be missed. Along-road distance never fell,
which supports there being no restart.

**Deviations:** the start pin was placed with a map tap, and the destination pin by dragging, as taps
near a pin do not reach the map. The route was saved temporarily to read its geometry and then
deleted, leaving saved routes byte-identical.

### Delivery to a separate app (protocol observer-delivery-1)

Run `4541ad7c-3c1e-4dc6-b55d-a935156d9e1d` under [observer-delivery-1](../protocols/observer-delivery-1.md) on 2026-11-07. RouteMock
0.4.7 (`e889a053...`) ran with the separately installed
[location observer](../../tools/location-observer/README.md) 0.1.0, application ID
`com.routemock.observer`, SHA-256
`8417d01db18936af655a4bd0c1aae80695f50b192dc4cfa8d631edac63b9f4d3`. The installed file matched that
hash. Precise location was granted on the phone's own dialog, while in use only. The observer's source
was at that point uncommitted, on top of `dc41712`.

The route was new: 1277.78 m, 4 turns over 30 degrees, at 30 km/h with repeat off, from one live
Valhalla request. See the [export](delivery-observations-observer-delivery-1.csv),
[geometry](observer-delivery-1-geometry.geojson), and
[report](report-export-observer-delivery-1/derived/report.html).

| Check | Criterion | Observed | Result |
| --- | --- | --- | --- |
| Delivery to another app | At least 30 callbacks during the route | 145 moving callbacks, 198 in all | PASS |
| Mock flag | Every recorded row flagged | 199 of 199 rows | PASS |
| Road geometry | At most 1 m | Worst 0.055 m, mean 0.016 m | PASS |
| Speed | Within 2% of 8.333 m/s | 8.3333 m/s over 147.99 s, +0.000%; reported 8.333 | PASS |
| Endpoint hold | At least 10 callbacks at the final point, speed zero | 53 over 53.47 s, 0.000 m away, speed 0.000 | PASS |
| Freshness | Median age at most 2,000 ms | Median 4 ms, maximum 87 ms during the route | PASS |
| Stop | No mock callback over 2 s after the mark, across 60 s | 0 after the mark, across 88.69 s | PASS |
| Cached data | Separate, and never counted | See below | PASS |

**Fresh callbacks and cached fixes, reported separately:**

| Phase | Fresh mock callbacks | Non-mock callbacks (counted, unread) | Cached reads |
| --- | --- | --- | --- |
| Route | 145 | 0 | None |
| Endpoint hold | 53 over 53.47 s, identical position | 0 | 1 mock row, 0.89 s old |
| After Stop | **0** across 92.09 s from the Stop tap | 91 | 1 read, not mock-flagged, so not read |

- **Comparison:** Android's own record held 198 fixes in the same window, and the observer received all
  198.
- **Stop:** the last mock callback came 0.61 s before the Stop tap. The mark was tapped 3.40 s after it.
- **Real fixes after Stop:** the 91 non-mock callbacks show real fixes resumed. Their coordinates were
  never read. A separate 6 arrived before any session, during permission setup, and were discarded the
  same way.
- **Expected gap:** the observer started 4.39 s after RouteMock, so it missed the first 42.4 m.

**Scope of this pass:** one test app, through `LocationManager` and the GPS provider, on one phone. It
says nothing about apps using Google Play services, or about apps that reject mock locations.

## Emulator evidence (Android 14)

**This is emulator evidence, not a physical-device result.** It is counted separately and does not
close D42, which stays NOT TESTED until a physical Android 14 or later phone is tested.

Run `14391547-6f88-4986-a0ee-648a21100fa9` on 2026-11-07, with RouteMock 0.4.7 (`e889a053...`, installed fresh) at commit `d99067c`.
See the [observations](delivery-observations-emulator-api34.csv) and
[report](report-export-emulator-api34/derived/report.html).

| Target | Value |
| --- | --- |
| Type | Android Emulator 37.2.13, AVD in a separate SDK folder |
| Image | `system-images;android-34;google_apis;arm64-v8a`, revision 14 |
| Android | 14, API 34, build `UE1A.230829.050`, userdebug, security patch 2023-09-05 |
| Model | `sdk_gphone64_arm64`, arm64-v8a, software rendering |

| # | Check | Result | Evidence |
| --- | --- | --- | --- |
| E1 | First launch | PASS | A fresh install opened to the consent gate, with no receipt and no crash |
| E2 | Consent | PASS | Three required boxes enabled Continue; one `AUTOMATED_TEST` acceptance recorded |
| E3 | Start before mock-app selection | PASS | Refused: "RouteMock is not the selected mock location app", with setup steps; nothing started |
| E4 | Mock-app selection | PASS | RouteMock listed under **Select mock location app** in Developer options and selected there; app-op `allow` |
| E5 | Foreground-service startup | PASS | Android 14 asked for notification permission, answered Allow; service foreground with type `specialUse` (`0x40000000`); 3 providers; no crash |
| E6 | Playback through arrival | PASS | 190 fixes, all mock-flagged; worst 0.057 m from the road; speed -0.001%; arrival 0.17 s after the expected 158.31 s |
| E7 | Endpoint hold | PASS | 33 fixes over 32.29 s, 0.000 m from the final point, speed 0.0; the service stayed foreground |
| E8 | Stop cleanup | PASS | 0 providers, service, wake lock, and notification; no last mock record |
| E9 | Consent withdrawal | PASS | Withdraw all left every scope `WITHDRAWN` |

**Route:** typed synthetic points returned the same 1,319.29 m route as road-arrival-1, and it was saved
on the emulator as `RM-emulator-test`. It is not a new distinct route. Fixes came from the emulator's
location records, read outside the app.

**Limits:** one emulator image, one run, with software rendering. The test automation answered the
notification prompt. Swiping away the notification, which Android 14 allows, was not tried.

## Physical-device checks

"Current" means run on 0.4.7. "Historical" means the result is from the build shown and was not
repeated on 0.4.7. Since 0.4.5, the app has changed only in its backup rules and its handling of
malformed dashboard replies.

### Current build, 0.4.7 (10 PASS)

| # | Check | Build | Result | Evidence |
| --- | --- | --- | --- | --- |
| D1 | Fixes follow the road, including turns | 0.4.7 | PASS | Table above. Also passed on 0.4.0, 0.4.1, 0.4.4, and 0.4.6 |
| D2 | Distance against speed and moving time | 0.4.7 | PASS | Table above |
| D3 | Pause, resume, and speed change | 0.4.7 | PASS | Table above |
| D4 | Stop cleanup | 0.4.7 | PASS | Table above |
| D5 | Android refuses to back up the app | 0.4.7 | PASS | `bmgr backupnow`: "Backup is not allowed". Device-to-device transfer itself was not exercised |
| D6 | Upgrade keeps saved data | 0.4.7 | PASS | Saved routes and consent receipt byte-identical before and after install |
| D7 | Automated consent labeled | 0.4.7 | PASS | Events recorded `AUTOMATED_TEST`, subject to the label's limit below |
| D38 | Invalid server responses | 0.4.7 | PASS | The listed replies, over loopback HTTP. See above |
| D36 | Delivery to another app | 0.4.7 | PASS | Protocol observer-delivery-1: one separate test app, `LocationManager`, GPS provider. See above |
| D41 | Reaching the end of a road route | 0.4.7 | PASS | Protocol road-arrival-1, on the 1.32 km route at 30 km/h. See above. The 31.21 km route has not been driven to its end |

### Historical (27 PASS, 1 FAIL)

| # | Check | Build | Result | Evidence |
| --- | --- | --- | --- | --- |
| D8 | Notification Pause, Resume, Stop | 0.4.0 | PASS | State changed each time; Stop cleaned up |
| D9 | Rotation | 0.4.0 | PASS | Longest gap between fixes 1.12 s |
| D10 | Section switching | 0.4.0 | PASS | Longest gap 1.07 s |
| D11 | Background | 0.4.0 | PASS | 25 s, longest gap 1.05 s |
| D12 | Brief screen off | 0.4.0 | PASS | 30 s, longest gap 1.03 s |
| D13 | Draft changes leave the session alone | 0.4.0 | PASS | 290 fixes within 0.063 m of the original road |
| D14 | Offline handling | 0.4.0 | PASS | Real Wi-Fi outage; banner and specific error |
| D15 | Invalid road route cannot start | 0.4.0 | PASS | Start and Preview refused |
| D16 | Force-stop: simulation does not restart | 0.4.0, 0.4.5 | PASS | No process or service returned within 90 s |
| D17 | **Force-stop: test providers removed without reopening the app** | 0.4.0, 0.4.5 | **FAIL (known limitation)** | 3 providers stayed for the 90 s observed. No new fixes, but last known stayed a stale mock fix. See [recovery notes](../FORCE_STOP_RECOVERY.md) |
| D18 | Force-stop: reopening RouteMock removes the providers | 0.4.5 | PASS | 0 providers after relaunch; last known no longer mock |
| D19 | Abnormal end via `am crash` | 0.4.0 | PASS | No restart. A crash, not a low-memory kill |
| D20 | Real marker never takes mock fixes | 0.4.0 | PASS | "Last real location", frozen at the earlier fix |
| D21 | Service unreachable from outside | 0.4.1 | PASS | `adb` refused: "Requires permission not exported" |
| D22 | Consent gate blocks the app | 0.4.1 | PASS | Onboarding only; nothing running; Continue disabled |
| D23 | Changed document needs new acceptance | 0.4.2 | PASS | Returned to onboarding |
| D24 | No pairing offered without consent | 0.4.1 | PASS | The phone showed no pairing form without the scope |
| D25 | Approved remote start | 0.4.1, 0.4.4 | PASS | Nothing ran before approval; `executed` only once a session ran |
| D26 | Rejected remote start | 0.4.0, 0.4.4 | PASS | Nothing ran; status `rejected` |
| D27 | Dashboard lost during a session | 0.4.0 | PASS | Server stopped; local Stop still worked |
| D28 | Re-pairing receives requests | 0.4.4 | PASS | Failed on 0.4.3 because polling did not restart. Fixed in 0.4.4 and repeated |
| D29 | Withdraw all, reachable when gated | 0.4.4 | PASS | Offered on the consent screen; used, it left nothing granted |
| D30 | Withdraw all during a session | 0.4.1 | PASS | Session stopped; onboarding shown |
| D31 | Required consent withdrawn online | 0.4.4 | PASS | Service, providers, and wake lock 0; remote ended; uploads kept; device not revoked |
| D32 | Upload consent withdrawn, local still works | 0.4.4 | PASS | Revoked; new local session ran; no upload |
| D33 | Offline upload withdrawal | 0.4.4 | PASS | Enforced at once; survived force-stop and relaunch; server revoked 5.0 s after reconnecting |
| D34 | Offline required withdrawal | 0.4.5 | PASS | Session stopped at once; server updated 5.0 s after reconnecting |
| D35 | Offline Withdraw all, delivered on relaunch | 0.4.5 | PASS | Server unchanged while the app stayed closed; revoked 4.3 s after launch |

### NOT TESTED (4)

| # | Check | Why |
| --- | --- | --- |
| D37 | Low-memory process death | `adb` could not cause it: `kill` was denied, and `am kill` leaves foreground services alone |
| D39 | Release APK at runtime | Its signature differs from the debug build, so installing it needs an uninstall, which was ruled out |
| D40 | Phone restart clearing abandoned test providers | No restart was performed |
| D42 | Android 14 or later | Only Android 13 is available on a physical phone. An Android 14 emulator passed separately, which does not count here |

## Automated tests

These ran on a computer, with no phone. Final run on 0.4.7:

| Suite | Total | Passed | Failed | Errors | Skipped |
| --- | --- | --- | --- | --- | --- |
| Android unit tests | 298 | 297 | 0 | 0 | 1 |
| Dashboard backend tests | 91 | 91 | 0 | 0 | 0 |
| **Together** | **389** | **388** | **0** | **0** | **1** |

The location observer tool has its own 12 unit tests, all passing, counted separately from the totals
above. They cover the tool, not delivery. No emulator was available, so there is no emulator evidence.

The skipped test is `LiveValhallaCheck`, which needs a network and is counted under
[live-provider checks](#live-provider-checks) when run on request.

Some behavior is proven **only** here, not on a phone:

| Behavior | Why not on a phone |
| --- | --- |
| Consent required at the session boundary | The service is not exported and the UI is gated, so a phone cannot reach it |
| Dashboard refuses pairing without consent (HTTP 403) | Backend behavior. The phone's side is D24 |
| Backup rules cover every storage domain | `BackupRulesTest` reads the rule files. D5 is the phone's side |

## Static APK inspection

Read from `app-release-unsigned.apk` for 0.4.7 without running it. Nothing here shows runtime
behavior: rejecting cleartext has not been observed on a device.

| # | Check | Result | Evidence |
| --- | --- | --- | --- |
| S1 | Not debuggable | PASS | No `debuggable` attribute |
| S2 | Cleartext off in the manifest | PASS | `usesCleartextTraffic=false` |
| S3 | System trust only | PASS | One `base-config`, cleartext off, `system` anchors, no user trust |
| S4 | No debug network exceptions | PASS | No `domain-config`, `debug-overrides`, or `127.0.0.1` |
| S5 | Release build flag | PASS | `BuildConfig.DEBUG=false` in the generated release class |
| S6 | Backup off in the built manifest | PASS | `allowBackup=false`, with `fullBackupContent` and `dataExtractionRules` present |

The loopback pattern text is present in release code but cannot be reached, because it is checked
only when `BuildConfig.DEBUG` is true.

## Live-provider checks

Checks against the public FOSSGIS Valhalla server, subject to its availability. Live traffic is
**not configured**, so none of these says anything about traffic.

| # | Check | Build | Where | Result | Evidence |
| --- | --- | --- | --- | --- | --- |
| L6 | Route calculated for observer-delivery-1 | **0.4.7 (current)** | Phone | PASS | One route, 1.28 km |
| L5 | Route calculated for road-arrival-1 | **0.4.7 (current)** | Phone | PASS | One route, 1.32 km, labeled with its traffic basis |
| L1 | `LiveValhallaCheck`: routes and a real failure | 0.4.6 (not repeated on 0.4.7) | Computer | PASS | 4 distinct routes, shortest 31.214 km; points at sea returned "no road near pin". One run, paced requests |
| L2 | `LiveValhallaCheck` | 0.3.0 | Computer | PASS | Historical |
| L3 | Calculate, compare, select routes | 0.4.0 | Phone | PASS | 31.21, 36.10, 40.27, 40.53 km with labels and traffic basis |
| L4 | Routing failure shown | 0.4.0 | Phone | PASS | "No road was found near a pin" |

## Corrections to the earlier record

The observations are unchanged. These correct how they were labeled or counted. The original
finalized run stays as it was in [report-export](report-export/), and its linked correction is in
[report-export-correction](report-export-correction/derived/report.html).

| # | Earlier record | Correction |
| --- | --- | --- |
| C1 | Force-stop listed as one PASS (export check 10a; report checks 10 and 33) | Split into D16 PASS, D17 FAIL, and D18 PASS, so the cleanup limitation is not hidden |
| C2 | "32 PASS, 0 FAIL, 7 NOT TESTED" as device totals | Replaced by the totals above. Route calculation and routing failure moved to live-provider checks, D17 now counts as a FAIL, and new checks were added |
| C3 | Release APK configuration listed among device checks as PASS (static) | Moved to static inspection |
| C4 | Session-boundary consent and backend pairing refusal listed among device checks | Moved to automated tests. The phone's own part remains as D24 |
| C5 | Road playback on the final build NOT TESTED | Closed for 0.4.6 by the run above. It was never run on 0.4.5 itself, and has not been run on 0.4.7 |
| C6 | Road-route arrival not listed as a gap | Added as D41, after the raw logs showed that no run reached the destination |
| C7 | Largest step ratio reported as 1.003 on 0.4.0 | Recalculating every recorded fix gives 1.006. Still within 1.05 |
| C8 | Invalid server responses on a device NOT TESTED | Closed for the listed replies by the 0.4.7 run (D38) |
| C9 | Reaching the end of a road route NOT TESTED | Closed by run road-arrival-1 (D41), on one short route at one speed |
| C10 | Delivery to another app NOT TESTED | Closed by run observer-delivery-1 (D36), for one test app through `LocationManager` |

## How acceptance was handled

- Consent used for automated testing is recorded as `AUTOMATED_TEST` on 0.4.1 and later, and was
  withdrawn afterwards. Events from 0.4.0 are recorded as `UNKNOWN`, because that build kept no
  origin. None of these is anyone's personal acceptance.
- **The label's limit:** while the test marker exists, every event is labeled `AUTOMATED_TEST`,
  including anything a person does on the phone at that moment. Consent events appeared in earlier
  testing that the scripts did not make, and the label cannot tell those apart.
- **Correction, kept:** because a person may have used the phone during testing, a correction marks
  26 of the first 29 receipt events as origin `UNKNOWN`. The other 3 already read `UNKNOWN`. The
  original records are unchanged. All 29 stay origin unknown.
- **The 0.4.6 run** added 2 events, an acceptance of the three required scopes and its withdrawal,
  both `AUTOMATED_TEST`. The receipt count matched what the script expected at each step.
- **The 0.4.7 run** added 2 more, an acceptance of all four scopes and its withdrawal, both
  `AUTOMATED_TEST`. All earlier events and the correction stayed unchanged.
- **The road-arrival-1 run** added 2 more, an acceptance of the three required scopes and its
  withdrawal, both `AUTOMATED_TEST`.
- **The observer-delivery-1 run** added 4 more, in two accept-and-withdraw cycles, all
  `AUTOMATED_TEST`. The second cycle was needed to delete the temporary route, because consent had
  already been withdrawn and that locks the app.
- **Current state:** 39 events, all four scopes `WITHDRAWN`, no consent in force.

**Disclosure:** the 0.4.0 run used a temporary low-level touch monitor, which recorded touch
times. One earlier 8-second diagnostic also read touch positions. This should not have been
collected. All of it is deleted, nothing is running, and the current protocol collects none.
Phases then seen to be disturbed were discarded. Later checks, including 0.4.7, ran with no
monitoring, so they rest on RouteMock's state and Android's records alone.

## Historical measurements

### Road playback on 0.4.6

Run on 2026-11-06 under [road-playback-2](../protocols/road-playback-2.md), with one added 90 km/h
phase. The debug APK was SHA-256 `8322e873620ac7f2234aade524c36394686e09b33a2d21705f091b79efd54ab4`,
and the file installed on the phone matched it. It was installed over the previous build without
clearing data. The route was the saved synthetic route, so this run made no routing request.

Fixes came from Android's location service, read outside the app. See the
[observations](delivery-observations-0.4.6.csv) and [export](report-export-0.4.6/derived/report.html).

| Measurement | Result | Tolerance | Result |
| --- | --- | --- | --- |
| Fixes observed | 189 over 193.21 s, all mock-flagged | All flagged | PASS |
| Worst distance from the road | 0.059 m (mean 0.022 m) | 1 m | PASS |
| Turns | 5 bearing changes over 30 degrees, the largest 101.9, worst 0.059 m from the road | 1 m | PASS |
| 120 km/h before pause | 33.334 m/s over 62.72 s, +0.001% | 2% | PASS |
| 120 km/h after resume | 33.333 m/s over 35.87 s, -0.001% | 2% | PASS |
| Change to 60 km/h | 16.665 m/s over 43.01 s, -0.008% | 2% | PASS |
| Change to 90 km/h | 24.999 m/s over 33.17 s, -0.002% | 2% | PASS |
| Distance against speed and moving time | 4926.6 m against 4926.7 m expected, over 178.20 s moving | 2% | PASS |
| Pause | 15 fixes over 13.71 s, one position, speed 0.0, no bearing | Exact | PASS |
| Resume | Continued from the paused position | No jump | PASS |
| Largest step against speed x gap | 1.005 | 1.05 | PASS |
| Stop cleanup | 0 mock providers, service, RouteMock wake lock, and notification | None left | PASS |

**Coverage:** this run covered the first 4.93 km of the route. It did not reach the destination.

### Earlier builds

From fixes recorded by Android's location service. All carried the mock flag. These are earlier
results, kept as observed.

| Measurement | 0.4.0 main run (684 fixes) | 0.4.1 regression (70 fixes) | Tolerance |
| --- | --- | --- | --- |
| Worst distance from the selected road | 0.063 m | 0.051 m | 1 m |
| Speed at 120 km/h | 33.333 m/s over 59.72 s, +0.000% | 33.333 m/s over 44.28 s, 0.00% | 2% |
| Speed at 60 km/h | 16.666 m/s over 180.33 s, -0.001% | Not run | 2% |
| Paused fixes | 54, one position per pause, speed 0.0, no bearing | 12, one position, speed 0.0, no bearing | Exact |
| Largest step against speed x gap | 1.006 (see C7) | About 1.0 | 1.05 |

**0.4.4:** 56 fixes during a session offline from the dashboard: all mock-flagged, worst 0.054 m from
the road, 33.333 m/s measured over 35.02 s, and 33.328 m/s over 8.38 s after resuming, against 33.333
selected. See [the observations](delivery-observations-final-build.csv).

**An invalid run was discarded.** A first offline attempt removed only the `adb reverse` tunnel.
The revocation still reached the server, most likely through an existing connection, so that run
proved nothing. The results above come from a repeat with the server stopped. A process-death
attempt with no session running was also discarded.

**Exposure before synchronization, as measured:** the server still showed consent and remote
control on, and the device unrevoked, and it accepted a new command, which stayed `pending`. The
phone did not act on it. The credential remained usable on the server, but it existed only as a
hash there and encrypted on the phone, where nothing used it except the queued revocation. A
dashboard operator would be misled until sync. If the phone never reconnects, it stays that way
until the operator revokes it, and queued commands expire within 5 minutes.

## Findings

1. **Mocking changed the phone's time zone.** With automatic time zone on, Android moved to
   Europe/Berlin during earlier testing. After one stop it stayed changed for several minutes, then
   returned by itself. The Terms warn about this.
2. **Force-stop leaves test providers registered** until the app is reopened. See D17.
3. **The Simulated and Delivered markers overlap**, so one label can hide the other.
4. **Re-pairing bug, fixed:** see D28.
5. **A leftover test artifact:** the crash test left a system error window that lingered until
   it was cleared. The app itself was not affected.

## Limitations

One phone, three played routes, one routing provider, and one provider response per route. Arrival was
observed on the two short routes at 30 km/h. Runs on the long route covered only its start. Repeated phases are not independent routes. Polling
observes most but not all fixes. Delivery was shown to one test app only, and no claim is made about
any other app.
