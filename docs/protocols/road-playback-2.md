# Protocol road-playback-2

Tests whether injected fixes follow a selected road route at the selected speed.

**Inputs.** Synthetic points, 52.5167, 13.4142 to 52.3906, 13.0645. The route selected is the
"Shortest of returned routes" from a live Valhalla response, kept as a saved route. Speeds
are 120 and 60 km/h.

**Observation.** A host script polls `dumpsys location` and keeps only `last mock location`
entries for `gps`. This is Android's own record, so it is independent of the app's state. It
records latitude, longitude, elapsed realtime, speed, bearing, and the mock flag.

**Measurement.** Each fix is projected onto the saved geometry on a local plane. Distance from
the road is the projection's offset. Measured speed is the change in distance along the road
divided by the change in Android's elapsed-realtime timestamps, across each period with
constant reported speed.

**Tolerances.**

| Quantity | Tolerance | Why |
| --- | --- | --- |
| Distance from road | 1 m | Logged coordinates have six decimals, about 0.1 m |
| Measured speed | 2% of selected | Allows timing and rounding effects |
| Largest step | 1.05 x speed x gap | Any jump would exceed this |
| Paused speed | Exactly 0 | Required behavior |

**Interference.** Do not touch the phone during a run. This protocol collects no touchscreen
data and monitors no other app. If a result looks disturbed, check RouteMock's own state and
repeat the phase, recording the deviation.

**Change from version 1.** Version 1 used a temporary low-level touch monitor, and one short
diagnostic capture included touch positions. That collection was outside what this project
should gather. It has been removed, its data deleted, and it is not part of this protocol.

**Limits.** One device, one route, and one provider response. Polling sees most but not
necessarily all fixes. Repeated sessions are not independent routes. Delivery to other apps
is not measured.
