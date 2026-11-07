# Licensing decision needed

**No license has been applied.** There is no license file in this project or in the GitHub
repository. Until one is chosen, the default is "all rights reserved": others can view public code,
but they have no clear right to use, change, or share it. This is the owner's decision. This page is
not legal advice.

## Choices awaiting a decision

**1. Software** (`app/`, `dashboard/`, `tools/`, build files)

| Option | Others may | They must | Consequence |
| --- | --- | --- | --- |
| **Apache-2.0** | Use, change, sell, keep changes closed | Keep the license and notices, and mark changed files | Includes an explicit patent grant. Matches most Android dependencies |
| **MIT** | The same | Keep the copyright and license text | Shortest. No explicit patent grant |
| **GPL-3.0** | Use, change, sell | Release distributed versions under GPL-3.0, with source | Stops closed forks, but limits use in closed products |
| No license | View only | | Others cannot lawfully reuse the code. Weakest for a portfolio or for research reuse |

**2. Documentation** (`README.md`, `docs/`, except what is listed under fixed terms)

| Option | Others may | They must | Consequence |
| --- | --- | --- | --- |
| **CC BY 4.0** | Copy, adapt, sell | Credit you and show changes | Common for research writing |
| **CC BY-SA 4.0** | The same | Credit you, and share adaptations under the same terms | Keeps adaptations open, but is harder to combine |
| Same as the software | As for the software | As for the software | One license to explain, though software licenses suit prose less well |
| All rights reserved | Read only | | Others need permission to reuse it |

**3. Synthetic sample data** (chosen start and end points, hand-built test data, measured results
that are not OpenStreetMap-derived)

| Option | Others may | They must | Consequence |
| --- | --- | --- | --- |
| **CC0** | Anything | Nothing | Simplest reuse, and you cannot require credit |
| **CC BY 4.0** | Anything | Credit you | Keeps your name attached |

## Fixed terms

These do not change, whatever is chosen above.

| Material | Terms | What it means |
| --- | --- | --- |
| Road geometry: `docs/samples/selected-road-geometry.geojson`, `docs/samples/road-arrival-1-geometry.geojson`, `docs/samples/observer-delivery-1-geometry.geojson`, recorded fixtures in `app/src/test/resources/fixtures/` | **ODbL 1.0**, derived from OpenStreetMap | Must credit "© OpenStreetMap contributors". Adapted databases that are shared stay under the ODbL |
| Recorded positions along that route: `docs/samples/delivery-observations*.csv` and export copies | **Treat as ODbL** | They follow OpenStreetMap-derived geometry. Treating them this way is the cautious reading, and a reviewer should confirm it |
| Screenshots showing the map | Carry the map's attribution | Keep "OpenFreeMap © OpenMapTiles, Data from OpenStreetMap" visible |
| Third-party components | See [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) | Those licenses stay with those components. The project license does not replace them |
| Gradle wrapper files | Apache-2.0 | Keep the header in `gradlew` |
| Consent and legal documents | **Drafts pending review** | Leave these out of any license until [legal review](legal/README.md) is done |

The in-app terms govern use of the app. They are not a copyright license.

## Redistribution

- **Publishing source only:** the choices above apply. Third-party binaries are fetched at build
  time, apart from the Gradle wrapper.
- **Distributing an APK:** this also redistributes the libraries inside it. BSD, MIT, and Apache-2.0
  terms require their notices to go with the binary. The APK shows map attribution but has **no
  screen of open-source notices**, so one would be needed before distributing it.
- **Routing and tiles:** the FOSSGIS server and OpenFreeMap have their own terms. Check them before
  distributing an app that relies on them, and keep the on-screen attribution.
- **Compatibility:** the dependencies listed in the notices are permissive, so none of the software
  options above conflicts with them as far as this review found. JUnit (EPL-1.0) is used in tests
  only and is not shipped.

## Suggestion

For academic work others can build on: Apache-2.0 for software, CC BY 4.0 for documentation, CC0 or
CC BY 4.0 for synthetic data, with OpenStreetMap-derived data staying under the ODbL.
