# Evidence and report schema

A dashboard run exports as `routemock-run/1` JSON. Bundles have this layout:

```
derived/run.json      The run and its checks
derived/checks.csv    The same checks for analysis
derived/report.html   Readable report
raw/                  Original evidence files
MANIFEST.sha256       Hash of every file above
```

Check with `shasum -a 256 -c` after removing the comment lines from the manifest.

**Hashes detect changes relative to a manifest you already trust. They do not show that an
observation is true**, and they do not show who created it.

| Field | Meaning |
| --- | --- |
| `run_id` | Unique ID |
| `state` | `draft`, or `final`, which can no longer change |
| `corrects` | The finalized run this one corrects |
| `protocol_version` | The protocol followed |
| `versions`, `source_revision`, `working_tree_dirty` | What was tested. Null means unknown. |
| `started_at_utc`, `finished_at_utc` | UTC timestamps |
| `monotonic_duration_s` | Duration in seconds from a monotonic clock |
| `checks[].result` | `PASS`, `FAIL`, `NOT TESTED`, or `INCONCLUSIVE` |
| `checks[].source` | `synthetic fixture`, `manual observation`, or `automated check` |
| `checks[].basis` | `simulator state`, `independent Android observation`, or `other` |

Units are metres, seconds, km/h, and degrees unless a field says otherwise.

PDF: print `report.html` with a browser, for example
`"Google Chrome" --headless --print-to-pdf=report.pdf report.html`.
