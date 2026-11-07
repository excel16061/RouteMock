# Sample data

Nothing here records a real journey. Do not read these files as travel.

| File | What it is | Category |
| --- | --- | --- |
| `synthetic-route.json` | Test inputs, provider metadata, and one measurement | Labeled within the file |
| `selected-road-geometry.geojson` | The selected route geometry | Live provider response for synthetic points. Data © OpenStreetMap contributors (ODbL) |
| `delivery-observations.csv` | Fixes as recorded by Android | Independently observed Android delivery, for the main run |
| `delivery-observations-final-build.csv` | The same, for 0.4.4 | Independently observed Android delivery |
| `delivery-observations-0.4.6.csv` | The same, for 0.4.6 | Independently observed Android delivery |
| `delivery-observations-0.4.7.csv` | The same, for the current build | Independently observed Android delivery |
| `verification-report.md` | Master record of device, automated, static, and live-provider results | Report |
| `final-handoff.md` | Current status | Report |
| `report-export/` | The original finalized run as exported by the dashboard: JSON, CSV, HTML, PDF, and manifest. Kept unchanged | Real measurements, entered from the verification report |
| `report-export-correction/` | A linked correction of that run. It splits the force-stop result | No new observations |
| `report-export-0.4.6/` | The finalized road run on 0.4.6 | Real measurements |
| `report-export-0.4.7/` | The finalized road and dashboard-reply run on the current build | Real measurements |
| `delivery-observations-road-arrival-1.csv` | Fixes from the full-route arrival run | Independently observed Android delivery |
| `road-arrival-1-geometry.geojson` | Geometry of the arrival route | Live provider response for synthetic points. Data © OpenStreetMap contributors (ODbL) |
| `report-export-road-arrival-1/` | The finalized arrival run | Real measurements |
| `delivery-observations-observer-delivery-1.csv` | The location observer's export: only mock-flagged fixes, with counts of ignored fixes | Observed by a separate app |
| `observer-delivery-1-geometry.geojson` | Geometry of the observer route | Live provider response for synthetic points. Data © OpenStreetMap contributors (ODbL) |
| `report-export-observer-delivery-1/` | The finalized observer run | Real measurements |

**Categories used across the project:**

- **Synthetic test input:** points chosen for testing, in central Berlin and Potsdam.
- **Simulator state:** what RouteMock believes it is doing. This is not evidence of delivery.
- **Independent Android observation:** entries from Android's location service, read outside the app.
- **Live provider response:** real output from the public Valhalla server.
- **Fixture:** stored responses in `app/src/test/resources/fixtures/`. `valhalla_recorded_*` were captured from the live service once; other unit test data is hand-built.

`report-export/MANIFEST.sha256` covers everything in that folder except `report.pdf`, which was
printed from `derived/report.html` afterwards. Check it from that folder with
`grep -v '^#' MANIFEST.sha256 | shasum -a 256 -c`.

The five newer export folders have their own `MANIFEST.sha256`, checked the same way, and no PDF.

`../EVIDENCE_MANIFEST.sha256` covers this folder, `../screenshots/`, and the main reports. It does
not list itself. Hashes detect changes
relative to a copy you trust. They do not show that an observation is true.

Times in the CSV are relative, and device identifiers are removed. Raw logs stay in the
git-ignored `device-logs/` folder.
