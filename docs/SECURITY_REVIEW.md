# Security review

A review of the source, build outputs, and a live local run, made before publication. It is not an
independent audit or penetration test, and RouteMock is **not** claimed to be immune to attack.

Official references used: Android's
[network security configuration](https://developer.android.com/privacy-and-security/security-config)
and [Auto Backup](https://developer.android.com/identity/data/autobackup) guides, and Django's
[deployment checklist](https://docs.djangoproject.com/en/6.1/howto/deployment/checklist/).

## Problems found and fixed

| Problem | Fix | Regression test |
| --- | --- | --- |
| A malformed dashboard reply, such as `{"commands":5}`, threw an exception nothing caught. The app would crash, and a crash during a session leaves test providers behind | Replies are checked, and any failure becomes a failed request | Yes. Confirmed to fail without the fix |
| A pairing reply could store a credential that crashes the app when sent as a header | Credentials must match the expected format | Yes. Confirmed to fail without the fix |
| An unexpected error in the HTTP client ended the process | Reported as a failed request | Yes. Confirmed to fail without the fix |
| The dashboard database was created readable by other local users, while holding unencrypted MFA material | `manage.py` restricts everything it creates | Yes. Confirmed to fail without the fix |
| `runserver` could be started on all interfaces with debug on | Refused anywhere but loopback | Yes |
| A code comment said recovery codes were never stored in the clear, but `django-otp` stores them unencrypted | Corrected, and documented as a limitation | Not applicable |
| `dashboard/.env` held a bare value, and `.gitignore` would have blocked `.env.example` | Now `NAME=value`, placeholders refused, example provided | Yes |
| Android backup rules on older versions relied on one attribute | Exclude-all rules added | Yes. Confirmed to fail without the fix |

## Verified

"Live" means a real local server. "Test" means an automated test. "Phone" means the physical test
phone, on the build in the verification report. "Build" means the release APK file.

| Area | Result | How |
| --- | --- | --- |
| Remote connections use HTTPS, with platform certificate and hostname checks | No custom trust manager, hostname verifier, or SSL handler anywhere in the source | Source search |
| Release rejects cleartext and trusts system certificates only | One `base-config`, cleartext off, `system` anchors | Build |
| Debug loopback exception is narrow | Only `127.0.0.1`, no subdomains, in `src/debug` only, and absent from release | Build, source |
| Credentials are not forwarded across hosts | Redirects are never followed; the token goes only to the requested URL | Test |
| Timeouts, cancellation, response limits | 20 s connect and read timeouts, cancellation closes the socket, 8 MiB limit | Test |
| Safe errors | Malformed replies become failed requests, and a session keeps running | Test, Phone |
| No logging of credentials or locations | The app contains no log calls. The dashboard logs event names only | Source search |
| No server secrets in the APK or browser | Routing key empty in this build; no secret reaches templates | Build, source |
| Login, lockout, generic errors | Refused with one message; lockout after 5 failures | Test, Live |
| MFA required | Every page needs a second factor; enrollment is forced | Test, Live |
| Session expiry and revocation | 8 hours idle; **Sign out everywhere** ended an old session | Test, Live |
| Session cookie | `HttpOnly`, `SameSite=Strict`; `Secure` outside local use | Live |
| CSRF | Required on browser forms; exempt only on bearer-token phone endpoints | Test, Live |
| Authorization | Every record lookup is filtered by owner, and roles are enforced | Test |
| Pairing | Single use, 10-minute expiry, consent required, attempts limited | Test, Live |
| Replay protection | Wrong nonce refused (403), replayed status refused (409) | Test, Live |
| Device credentials | Separate random token per device, stored hashed, revocable | Test, Live |
| Upload boundary | Location fields refused (400) | Test, Live |
| Evidence downloads | Sign-in required; always an attachment with `nosniff`; content checked at upload | Test, Live |
| Browser content policy | CSP with no scripts allowed, framing denied | Live |
| Development server | Listened on `127.0.0.1`; `0.0.0.0` refused | Live |
| Network ADB | Off on the phone; `adb` itself on `127.0.0.1` only | Phone |
| Backup and transfer | All storage domains excluded; Android reports backup not allowed | Test, Build, Phone |
| Real and simulated data kept apart | Separate labels and markers; real marker never took mock fixes | Phone |

## Dependency and code scans

| Tool | Scope | Result |
| --- | --- | --- |
| `pip-audit` | All 10 installed dashboard packages | No known vulnerabilities |
| OSV | All 124 packages in the Android release runtime | No advisories |
| Bandit | Dashboard source | 2 low-severity false positives: variable names containing "PASS" and "SECRET" |
| Django `check --deploy` | Production settings | 1 warning, HSTS preload, left off deliberately until a domain exists |
| Gradle wrapper | `gradle-wrapper.jar` | Hash matches Gradle's published value |

These results reflect what those databases listed on 2026-11-06. An empty result is not proof that
none exist.

## Not tested

| Protection | Why |
| --- | --- |
| Rejecting cleartext at runtime in a release build | The release APK has not been run on a device |
| Reply handling over HTTPS, and shapes beyond those listed | The phone test used loopback HTTP and a fixed list of replies |
| Real TLS: `Secure` cookies, HSTS, HTTPS redirect | The dashboard has only run over loopback HTTP |
| Device-to-device transfer | Needs a second phone |
| Behavior on Android 14 and later | Only Android 13 is available |

## Known limitations

- **Debug builds** allow cleartext to `127.0.0.1` and `run-as` access to app data. Never distribute
  them.
- **A routing API key, if configured**, is compiled into the APK and sent in the URL, so anyone with
  the APK can extract it. Use only a key restricted by the provider, with its own limits. None is
  configured here.
- **Timeouts are per connection and read**, so a very slow server is bounded by the response limit and
  by cancellation, not by a total time limit.
- **Certificate pinning is not used**, on purpose, so a certificate change at a free provider does not
  break the app. A trusted certificate authority that misissues could be accepted.
- **Stored MFA keys and recovery codes are unencrypted** in the database, and evidence files are
  unencrypted. Protect the files.
- **TOTP can be phished**, predictable long passwords are accepted, and there is no breached-password
  check.
- **Device tokens do not expire** until revoked.
- **The first password holder enrolls MFA.** After `reset_mfa`, whoever signs in next sets it up.
- **The pairing limit is per process** and resets on restart.
- **The server trusts a consent receipt** from a valid pairing. It cannot prove a person decided.
- **Uploads are not malware-scanned.** Treat downloads as untrusted.
- **After a force-stop**, test providers stay until RouteMock is reopened, and a stale mock fix
  stays readable as last known. Reopening removes them (phone and Android 14 emulator).

See [THREAT_MODEL.md](THREAT_MODEL.md) and [DASHBOARD_LIMITATIONS.md](DASHBOARD_LIMITATIONS.md).
