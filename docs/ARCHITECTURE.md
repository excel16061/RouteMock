# Architecture and data flow

```
                   HTTPS, sent only when asked
 Android app  ───────────────────────────────►  Routing provider (pins, IP address)
 (RouteMock)  ───────────────────────────────►  Map tile provider (areas viewed, IP address)
      │
      │ HTTPS, phone-initiated, only if paired and allowed
      ▼
 Dashboard (Django, private)  ◄──── browser, signed in with password and second factor
      │
      └── SQLite and private evidence folder (local development)
```

The phone never listens for connections. The dashboard cannot reach it: the phone polls
while the app is visible, and only if remote requests are turned on.

## Android app

| Package | Responsibility |
| --- | --- |
| `domain` | Coordinates, geodesy, routes, validation, map drafts |
| `playback` | Position from monotonic elapsed time |
| `simulation` | Session control and the `LocationSink` contract |
| `mock` | Android test providers, session journal, readiness |
| `service` | Foreground service and notification |
| `routing` | `RoutingProvider`, Valhalla client, comparison, secure HTTP |
| `location` | Real versus mock fix classification |
| `consent` | Consent documents and the append-only receipt |
| `dashboard` | Pairing, outbound requests, remote request validation |
| `data` | Saved routes in a private JSON file |
| `ui` | Compose screens and the ViewModel |

Simulation state lives in the process, so screens cannot alter it. A dashboard request
only ever produces a dialog; approval on the phone is the only path to starting.

## Dashboard

A Django 6.1 project using server-rendered templates, with no JavaScript. Everything is in
the `core` app: `models.py`, `views.py` (browser), `device_api.py` (phones), `auth_views.py`,
`security.py`, and `evidence.py`.

## Where data lives

| Data | Location | Leaves the phone? |
| --- | --- | --- |
| Saved routes | App storage | Never |
| Consent receipt | App storage | Only if paired with upload allowed |
| Real location | Memory | Never by RouteMock, unless you put a pin on it and calculate a route |
| Pins | App storage | To the routing provider when you tap Calculate |
| Viewed map areas | Not stored by RouteMock | To the tile provider |
| Device credential | Encrypted with an Android Keystore key | Sent only to the paired dashboard |
| Session summaries | Dashboard | Title, version, speed, duration only |
