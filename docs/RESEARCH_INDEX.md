# Research evidence index

RouteMock is a location-simulation and testing platform. This index separates what was built
from what was measured. **No novelty, effectiveness, or security claim is made.**

| Protocol | Evidence | Scope | Limitations |
| --- | --- | --- | --- |
| [road-playback-2](protocols/road-playback-2.md) | [Report](samples/verification-report.md), [0.4.7 observations](samples/delivery-observations-0.4.7.csv), [0.4.7 export](samples/report-export-0.4.7/derived/report.html), [0.4.6 export](samples/report-export-0.4.6/derived/report.html), [earlier observations](samples/delivery-observations.csv), [earlier export](samples/report-export/report.pdf), [its correction](samples/report-export-correction/derived/report.html) | One device, one synthetic route | Not independent cases; no run reached the destination; delivery to other apps not measured |
| [road-arrival-1](protocols/road-arrival-1.md) | [Report](samples/verification-report.md), [observations](samples/delivery-observations-road-arrival-1.csv), [export](samples/report-export-road-arrival-1/derived/report.html) | One device, one short synthetic route, one speed | One run; delivery to other apps not measured |
| [observer-delivery-1](protocols/observer-delivery-1.md) | [Report](samples/verification-report.md), [export](samples/delivery-observations-observer-delivery-1.csv), [run](samples/report-export-observer-delivery-1/derived/report.html) | One test app, one phone, one route | One run; `LocationManager` only |

Engineering work is not a research finding. Repeated runs of one route are repetitions, not
independent routes or vulnerability cases.
