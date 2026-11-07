# Threat model

This records known risks and how far each is addressed. RouteMock is not certified and has
had no independent audit. "Tested" means an automated test in this repository covers it.

| Threat | Mitigations | Tested | Remaining risk |
| --- | --- | --- | --- |
| Data copied off the phone by backup or transfer | `allowBackup=false`, every storage domain excluded for backup and device transfer, device credential sealed by a Keystore key | Yes | Device-to-device transfer was not exercised on a phone. Rooted devices and `run-as` on debug builds are out of scope. |
| Malformed reply from a dashboard | Replies are checked, credentials must match a format, and failures never end the process | Yes, and on the phone | Listed reply shapes only, over loopback HTTP. |
| Stolen password | Second factor is mandatory, Argon2id storage, 15-character minimum, common-password check | Yes | Phishing can capture a code, as TOTP is not phishing-resistant. Passkeys are not implemented. |
| Password guessing | Lockout per username and address, one generic error | Yes | Spread-out guessing from many addresses is limited only by the second factor. |
| Compromised session | HttpOnly, SameSite=Strict, 8-hour limit, rotation at login, server-side logout, sign out everywhere, reauthentication for sensitive changes | Yes | Malware on the administrator's computer is out of scope. Secure cookies depend on deployment. |
| Unauthorized pairing | 96-bit code, 10-minute life, single use, stored hashed, attempts limited, created only after reauthentication | Yes | Anyone who sees a live code could use it first. The limiter uses per-process cache memory. |
| Replayed commands | Per-command nonce, expiry, forward-only status, delivered once, nonces remembered on the phone, approval on the phone | Yes | The phone's nonce memory ends with the process; expiry and delivered-once still apply. |
| Cross-account access | Every query filtered by owner, so guessed IDs return 404; UUIDs | Yes | A future view that forgets the filter. Keep the tests in step. |
| Malicious uploads | 10 MiB limit, allow-listed types checked by content, safe names, stored outside served paths, always downloaded as attachments | Yes | Files are not virus-scanned. Reviewers should treat them as untrusted. |
| Location data leakage | Phone uploads only fixed fields, real location is never uploaded, logs hold no coordinates | Yes | Route drafts contain whatever coordinates a user types. Providers see pins and map areas. |
| Tampered evidence | Finalized runs are immutable through the app, corrections are linked, exports have SHA-256 manifests | Yes | Someone with database or disk access can change records. A hash proves nothing unless the manifest is kept somewhere trusted. |
| Hostile network | HTTPS only, platform certificate checks, redirects refused, responses size-limited and validated | Yes | Depends on the device's certificate store. Local development is HTTP on loopback. |
| Consent bypassed | Checked at the session boundary, not only the UI; backend needs a receipt to pair and holds its own consent state; withdrawal revokes server-side | Yes | The backend cannot prove a person made the decision: it trusts a receipt from a valid pairing. A phone offline at withdrawal leaves the credential active on the server until the queued revocation is delivered or the operator revokes it. The phone acts on nothing in the meantime. |
| Test data becoming production data | No fixtures or seed accounts; migrations create no users; production mode refuses to start without a secret; test databases are throwaway | Yes | An operator could still copy a development database. Do not. |
| Weak chosen password | 15-character minimum and common-password list | Yes | Predictable long passwords are accepted, such as `password12345678`. There is no breached-password check. MFA is the main defense. |
| Other apps on the phone | Service not exported, PendingIntents immutable, credential encrypted with a Keystore key | Partly | A rooted device can defeat all of this. |

## Local-development exceptions

- The dashboard runs over HTTP on loopback, so `Secure` cookies and HSTS are off in `DEBUG`.
- Debug builds of the app may reach `http://127.0.0.1` only, for `adb reverse`. Release
  builds refuse it, and it is enforced in both the network configuration and the client.

## Not protected by this design

Deployment protections cannot be tested locally: TLS configuration, `Secure` cookies, HSTS,
backups, firewalling, operating-system patching, and log handling. See
[DEPLOYMENT.md](DEPLOYMENT.md).
