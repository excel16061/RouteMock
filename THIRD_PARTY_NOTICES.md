# Third-party notices

This inventory is read from package metadata. It is not legal advice. Check the licence
files of each dependency, including transitive ones, before distributing a build.

## Android app

| Component | Version | Licence |
| --- | --- | --- |
| MapLibre Native Android | 13.7.1 | BSD (per its Maven metadata; the native library contains further notices) |
| AndroidX Core, Activity, Lifecycle | 1.18.0, 1.13.0, 2.10.0 | Apache-2.0 |
| Jetpack Compose (BOM 2026.06.01) | as resolved | Apache-2.0 |
| Kotlin, kotlinx.coroutines, kotlinx.serialization | 2.4.21, 1.11.0, 1.12.0 | Apache-2.0 |
| JUnit (tests only) | 4.13.2 | EPL-1.0 |
| Gradle wrapper | 9.6.1 | Apache-2.0 |

## Dashboard

| Component | Version | Licence |
| --- | --- | --- |
| Django | 6.1.3 | BSD-3-Clause |
| asgiref | 3.13.0 | BSD-3-Clause |
| sqlparse | 0.6.1 | BSD |
| argon2-cffi, argon2-cffi-bindings | 25.1.0, 26.1.0 | MIT |
| cffi | 2.1.1 | MIT-0 |
| pycparser | 3.12 | BSD-3-Clause |
| django-axes | 9.0.0 | MIT |
| django-otp | 1.7.5 | Unlicense |
| qrcode | 8.2 | BSD |

## Data and services

| Source | Terms | Attribution required |
| --- | --- | --- |
| OpenStreetMap data | ODbL 1.0 | © OpenStreetMap contributors |
| OpenFreeMap tiles | [Terms](https://openfreemap.org/tos/) | OpenFreeMap © OpenMapTiles, Data from OpenStreetMap |
| FOSSGIS Valhalla server | [Terms](https://www.fossgis.de/arbeitsgruppen/osm-server/nutzungsbedingungen/) | Attribution plus an error-reporting link |

Files in `docs/samples/` and `app/src/test/resources/fixtures/` contain geometry derived
from OpenStreetMap data, which remains under the ODbL.
