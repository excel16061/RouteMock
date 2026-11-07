# Verification report

**Source: RouteMock 0.4.7. Last build tested on the phone: 0.4.6.** This is the master record of what
was checked, how, and on which build. Evidence of different kinds is kept apart, and results from
earlier builds are marked historical.

Version 0.4.7 changes how malformed dashboard replies are handled, with no change to playback,
consent, or location code. Its automated tests and static inspection were run, but it has **not been
installed on a phone**, so no device result below applies to it directly.

**Hardware:** one physical phone, a REVVL 6 Pro on Android 13. Dashboard tests with "two devices"
used two **backend device records** in a test database, never two phones.

**Scope:** one road route was played, 31.21 km, the "Shortest of returned routes" from four routes in
a live response. One routing provider (the FOSSGIS Valhalla server) and one map tile provider
(OpenFreeMap) were used. Stationary sessions used a synthetic point. The source was uncommitted, so
no revision identifier exists.

## Totals by kind of evidence

| Kind | What it shows | PASS | FAIL | NOT TESTED | Other |
| --- | --- | --- | --- | --- | --- |
| [Physical-device checks](#physical-device-checks) | Behavior on the one real phone | 34 | 1 | 7 | |
| [Automated tests](#automated-tests) | Logic on a computer, with no phone | 388 | 0 | 0 | 1 skipped |
| [Static APK inspection](#static-apk-inspection) | What is inside the built file | 6 | 0 | 0 | |
| [Live-provider checks](#live-provider-checks) | Responses from the real routing service | 4 | 0 | 0 | |

- **Device split:** 7 PASS on 0.4.6, the last build tested on the phone. The other 27 PASS and the
  1 FAIL are historical, from 0.4.0 to 0.4.5, and were not repeated on 0.4.6.
- **Live-provider split:** 1 PASS on 0.4.6, and 3 historical.
- **The 1 FAIL** is the force-stop cleanup limitation in check D17. Android gives the app no chance
  to clean up at that moment, so the app cannot fix it, but the outcome is still not a pass.

The evidence kinds are not added together, because a unit test and a phone check do not prove the
same thing.

## Latest road playback: 0.4.6

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

## Physical-device checks

"Latest" means run on 0.4.6. "Historical" means the result is from the build shown and was not
repeated on 0.4.6, which differs from 0.4.5 only by backup rules and its version number.

### Latest device build, 0.4.6 (7 PASS)

| # | Check | Build | Result | Evidence |
| --- | --- | --- | --- | --- |
| D1 | Fixes follow the road, including turns | 0.4.6 | PASS | Table above |
| D2 | Distance against speed and moving time | 0.4.6 | PASS | Table above |
| D3 | Pause, resume, and speed change | 0.4.6 | PASS | Table above |
| D4 | Stop cleanup | 0.4.6 | PASS | Table above |
| D5 | Android refuses to back up the app | 0.4.6 | PASS | `bmgr backupnow`: "Backup is not allowed". Device-to-device transfer itself was not exercised |
| D6 | Upgrade keeps saved data | 0.4.6 | PASS | Saved routes and consent receipt byte-identical before and after install |
| D7 | Automated consent labeled | 0.4.1, 0.4.6 | PASS | Events recorded `AUTOMATED_TEST`, subject to the label's limit below |

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

### NOT TESTED (7)

| # | Check | Why |
| --- | --- | --- |
| D36 | Delivery to another app | Only RouteMock may be tested on this phone |
| D37 | Low-memory process death | `adb` could not cause it: `kill` was denied, and `am kill` leaves foreground services alone |
| D38 | Invalid server responses on a device | Unit tests only |
| D39 | Release APK at runtime | Its signature differs from the debug build, so installing it needs an uninstall, which was ruled out |
| D40 | Phone restart clearing abandoned test providers | No restart was performed |
| D41 | Reaching the end of a road route | No recorded run got there. The longest covered about 7.5 km of 31.21 km. Arrival is covered by unit tests only |
| D42 | Android 14 or later | Only Android 13 is available |

Invalid server responses now have stronger handling in 0.4.7, proven by unit tests only, so D38 stays
NOT TESTED.

## Automated tests

These ran on a computer, with no phone. Final run on 0.4.7:

| Suite | Total | Passed | Failed | Errors | Skipped |
| --- | --- | --- | --- | --- | --- |
| Android unit tests | 298 | 297 | 0 | 0 | 1 |
| Dashboard backend tests | 91 | 91 | 0 | 0 | 0 |
| **Together** | **389** | **388** | **0** | **0** | **1** |

The skipped test is `LiveValhallaCheck`, which needs a network and is counted under
[live-provider checks](#live-provider-checks) when run on request.

Some behavior is proven **only** here, not on a phone:

| Behavior | Why not on a phone |
| --- | --- |
| Consent required at the session boundary | The service is not exported and the UI is gated, so a phone cannot reach it |
| Dashboard refuses pairing without consent (HTTP 403) | Backend behavior. The phone's side is D24 |
| Invalid server responses | See D38 |
| Arrival at the end of a road route | See D41 |
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
| L1 | `LiveValhallaCheck`: routes and a real failure | 0.4.6 (latest) | Computer | PASS | 4 distinct routes, shortest 31.214 km; points at sea returned "no road near pin". One run, paced requests |
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
- **Current state:** 31 events, all four scopes `WITHDRAWN`, no consent in force.

**Disclosure:** the 0.4.0 run used a temporary low-level touch monitor, which recorded touch
times. One earlier 8-second diagnostic also read touch positions. This should not have been
collected. All of it is deleted, nothing is running, and the current protocol collects none.
Phases then seen to be disturbed were discarded. Later checks, including 0.4.6, ran with no
monitoring, so they rest on RouteMock's state and Android's records alone.

## Historical measurements

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

One phone, one played route, one routing provider, one provider response for the played route.
Road runs covered only the start of the route. Repeated phases are not independent routes. Polling
observes most but not all fixes. No claim is made about other apps.
