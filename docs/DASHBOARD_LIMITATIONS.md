# Dashboard limitations

The dashboard is for local use. It has not been deployed, audited, or penetration tested. Each
limitation below is stated separately so none is hidden behind a general claim.

## Implemented and not implemented

| Capability | Status |
| --- | --- |
| Password hashing | Implemented: Argon2id |
| Two-factor sign-in | Implemented: TOTP authenticator apps only |
| Passkeys / WebAuthn | **Not implemented** |
| Recovery codes | Implemented: 8 single-use codes |
| Self-service account recovery | **Not implemented** |
| Malware scanning of uploads | **Not implemented** |
| Deleting records | **Not implemented** |
| Retention limits | **Not implemented** |
| Email or notifications | **Not implemented** |

## Deletion

- There is no way to delete devices, runs, evidence, consent records, or audit events from the
  dashboard. Removing them needs direct database and file access.
- Revoking a device stops it. It does not delete anything.
- A user's withdrawal on the phone does not delete what was already uploaded.
- Nothing expires automatically, including audit events and consent records.

## Password protections

- The policy is a 15-character minimum, a common-password list, a numeric check, and a similarity
  check. Predictable long passwords pass. Measured examples: `password12345678`,
  `passwordpassword`, `qwertyuiop123456`, and `aaaaaaaaaaaaaaaa`.
- There is no check against breached-password lists.
- Lockout is 5 failures per username and address, for 15 minutes. Guessing spread across many
  addresses is limited only by the second factor.
- Lockouts are stored in the database. They have not been tested under a production setup.

## Multi-factor authentication

- TOTP is mandatory, but can be phished in real time. It is not phishing-resistant.
- The TOTP secret is stored in the database so codes can be verified. A database compromise
  exposes it. It is not encrypted at rest by the application.
- Recovery codes are also stored unencrypted, by `django-otp`. A database compromise exposes them.
- There is no way to add a second authenticator, or to list or rename one.

## Recovery

- If an authenticator is lost, a recovery code can be used. They are shown once.
- If all are lost, recovery needs server access: `manage.py reset_mfa USERNAME`. This is a
  deliberate choice, and it means anyone with server access can reset a second factor.
- There is no password reset by email. A lost password needs server access too.
- `reset_mfa` asks for no confirmation.

## Upload handling

- Files are limited to 10 MiB and to PNG, PDF, JSON, CSV, and TXT.
- Types are checked by content where possible: a signature for PNG and PDF, parsing for JSON, and
  UTF-8 decoding for CSV and TXT. A valid file can still have harmful content.
- **Uploads are not scanned for malware.** Treat downloads as untrusted.
- Downloads are forced as attachments, so they do not run in the site's origin.
- Files are stored unencrypted in a local folder, and there is no storage quota.

## Sessions and access

- Sessions last up to 8 hours. There is no list of active sessions, only "Sign out everywhere".
- Only two roles exist, administrator and viewer. Viewers are created with the same local command.
- Reauthentication lasts 5 minutes after confirming a password and code.

## Devices and consent

- The server trusts a consent receipt from a valid pairing. It cannot prove a person decided.
- Device tokens do not expire. They last until revoked.
- A phone that withdraws while offline stays active on the server until it reconnects or the
  operator revokes it.
- The pairing rate limit uses per-process memory, so it resets on restart and is not shared
  between server processes.

## Deployment configuration

Everything here is unverified, because nothing has been deployed:

- Only `runserver` on loopback, over HTTP, has been used. It is not a production server.
- `Secure` cookies, HSTS, and the HTTPS redirect are enabled only when `ROUTEMOCK_DEBUG=0`, and
  have not been exercised behind real TLS.
- `SECURE_PROXY_SSL_HEADER` is unset, so a proxy deployment needs review or the HTTPS redirect may
  loop.
- `SECURE_HSTS_PRELOAD` is off on purpose.
- The database is SQLite. A shared deployment needs a proper database and tested backups.
- The login limiter relies on the client address, which a proxy can hide or spoof if misconfigured.
- There is no log shipping, alerting, or intrusion detection.
- Backups of the database and evidence folder are not provided.
- Dependencies are pinned but not automatically updated or scanned.
