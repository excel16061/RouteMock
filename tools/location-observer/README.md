# RouteMock location observer

A small, separately installed Android app used to check D36: whether **another app** receives
RouteMock's simulated fixes. It is a test tool, not part of RouteMock.

| Property | Value |
| --- | --- |
| Application ID | `com.routemock.observer` |
| Permissions | `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` (Android requires both for precise location) |
| Not requested | Internet, background location, foreground service, storage |
| Components | One activity. No service, receiver, or provider |

## What it does

- Listens to Android's standard `LocationManager` GPS provider, only between **Start observation** and
  **Stop observation**, and only while on screen. Leaving the screen ends the observation.
- Checks the mock flag **before** reading a fix. A non-mock fix becomes a count, and its coordinates
  are never read, shown, or stored.
- Keeps each mock fix's flag, elapsed-realtime time, coordinates, and speed where available.
- Labels new listener updates `callback`. **Read last known** adds a `last_known` row from Android's
  cache. Cached rows are not evidence of delivery.
- **Mark RouteMock stopped** notes a moment, so later fixes can be compared with it.
- **Export CSV** writes to the app's private storage. It has no clock times or device data.

Exports are excluded from backup and device transfer.

## Build and check

From the repository root, with `env.sh` loaded:

```sh
./gradlew -p tools/location-observer clean assembleDebug testDebugUnitTest lintDebug
```

The APK is `tools/location-observer/build/outputs/apk/debug/location-observer-debug.apk`, and is not
tracked by Git.

Install it only on a phone you are allowed to test on, with the owner's approval. To read an export
from a debug build:

```sh
adb exec-out run-as com.routemock.observer cat files/exports/observation-1.csv
```

Treat an export as test evidence, and keep it out of Git until it has been reviewed. To remove the
tool, uninstall `com.routemock.observer`. This does not touch RouteMock's data.

Follow [observer-delivery-1](../../docs/protocols/observer-delivery-1.md) for the test.
