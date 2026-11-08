# Dashboard guide

The dashboard is a private web application for one person's testing, on their own computer. **Keep
it local.** Publishing this code does not make the dashboard safe to host, and it has not been
deployed anywhere.

Run commands from the `dashboard/` folder. They were checked against this project with a
fresh install and a throwaway database, except where this guide says **not exercised**.

## 1. Prerequisites and installation

You need Python 3.12 or later (tested on 3.15). To pair a phone you also need Android platform tools
(`adb`), USB debugging on the phone, and a debug build of RouteMock.

```sh
cd dashboard
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

## 2. Database

```sh
.venv/bin/python manage.py migrate
```

This creates `dashboard/dashboard.sqlite3`, readable only by you. The first command you run also
creates `dashboard/.env` with a random secret. You do not need to edit it.

If you created a database with an earlier version, restrict it yourself:

```sh
chmod 600 dashboard.sqlite3
```

## 3. Administrator account

```sh
.venv/bin/python manage.py bootstrap_admin
```

It asks for a username and a password, which you type at a hidden prompt. **There are no default
accounts.** The minimum is 15 characters, but predictable long passwords are accepted, so use a long,
random, unique passphrase from a password manager.

## 4. Start the dashboard

```sh
.venv/bin/python manage.py runserver 127.0.0.1:8000
```

Open <http://127.0.0.1:8000> in a browser. Stop it with Ctrl+C. The server refuses any address other
than loopback.

## 5. First sign-in

1. Enter your username and password.
2. Scan the QR code with an authenticator app, or type the setup key into it.
3. Enter the 6-digit code.
4. **Store the 8 recovery codes offline**, for example in a password manager. They are shown once.

Do not screenshot the setup key, recovery codes, or pairing codes, and do not paste them into chats
or tickets.

After first enrollment, some actions ask for your password and a new code. An authenticator code
works only once, so wait for the next one if it was just used. This confirmation lasts 5 minutes.

## 6. Pair a phone over USB

With the dashboard running and the phone connected by USB:

```sh
adb reverse tcp:8000 tcp:8000
```

1. In the dashboard, open **Pair device**, enter a label such as `Test phone A`, and create a code.
   Use a pseudonym, not a real name. The code works once, for 10 minutes.
2. On the phone, open **More** and choose **Allow** for dashboard pairing.
3. Enter `http://127.0.0.1:8000` and the code, then tap **Pair**.

Only debug builds accept that address. Other addresses must use HTTPS. The tunnel exists only while
the USB cable is connected and `adb` is running.

## 7. Consent scopes

Consent is given on the phone, by the phone's user.

| Scope | Required | Allows |
| --- | --- | --- |
| Responsible Use and Terms | Yes | Using the app |
| Privacy Notice | Yes | Using the app |
| Mock Location Consent | Yes | Simulating location, locally or on an approved remote request |
| Dashboard pairing and data upload | No | Pairing, uploading consent receipts and session summaries, and receiving requests |

All four documents are **drafts pending legal review**. See [CONSENT_SCOPES.md](CONSENT_SCOPES.md).

## 8. How the pieces work

**Route drafts.** Under **Overview**, save a draft as JSON. Saving again adds a version and never
changes a running simulation:

```json
{"mode":"STATIONARY","start":{"latitude":36.0,"longitude":-118.0}}
```

Use synthetic coordinates. A draft may also have `destination` and `speed_kmh`. **Road routes cannot
be started remotely**, because a road route must be calculated on the phone. The phone ignores such a
request.

**Remote requests.**

1. On the phone, turn on **Allow remote start requests**.
2. In the dashboard, choose **Turn on** for that device.
3. Open the draft, pick the device, and request the simulation.
4. The phone checks every 15 seconds while RouteMock is on screen, and asks its user.
5. Nothing runs until the user chooses **Approve**. **Reject** ends the request.

Requests expire after 5 minutes. The status moves forward only: pending, delivered, accepted or
rejected, then executed or failed.

**Uploads.** After a session, a paired phone uploads a summary with its title, app version, speed,
and duration. The server accepts no other fields, so coordinates cannot arrive this way. The consent
receipt is also uploaded.

**Runs and reports.** Uploaded sessions appear under **Test runs**. On a draft run you can upload
evidence: PNG, PDF, JSON, CSV, or TXT, up to 10 MiB. Downloads are always attachments. **Finalize**
locks a run. To change one afterwards, create a linked correction. Exports are JSON, CSV, HTML, and a
ZIP with a SHA-256 manifest. Hashes show change, not that a result is true.

The web pages have no form for adding check results to a run.

## 9. Stopping and removing access

| Goal | How |
| --- | --- |
| Stop a simulation | On the phone: **Stop** in Simulation, or in the notification. It works with no connection |
| Stop remote requests | Dashboard: **Turn off**. Phone: turn off **Allow remote start requests** |
| Revoke a device | Dashboard: **Revoke**. Its credential stops working at once. Records stay |
| Disconnect from the phone | On the phone: **Disconnect**, which also ends the credential on the dashboard |
| Withdraw consent | On the phone: **Withdraw** beside a scope, or **Withdraw all consent** |
| Remove the USB tunnel | `adb reverse --remove tcp:8000`, or `adb reverse --remove-all` |
| Stop the dashboard | Ctrl+C |

Withdrawing takes effect on the phone at once. If it is offline, the server learns when the app next
runs with a connection, and until then it still shows the old state. The phone ignores requests
meanwhile. See [CONSENT_SCOPES.md](CONSENT_SCOPES.md).

**After a force-stop**, or if the notification disappears without Stop, open RouteMock. Test
providers stay registered until you do, and apps can read a stale simulated position. See
[FORCE_STOP_RECOVERY.md](FORCE_STOP_RECOVERY.md).

## 10. Password, MFA, and sessions

| Situation | What to do |
| --- | --- |
| Lost authenticator | Sign in with a recovery code, then replace your codes under **Account** |
| Change password | **Account** |
| Forgotten password | `.venv/bin/python manage.py changepassword USERNAME` (hidden prompt; **not exercised**) |
| Lost authenticator and all recovery codes | `.venv/bin/python manage.py reset_mfa USERNAME`, then enroll again |
| Locked out after 5 failures | Wait 15 minutes, or run `.venv/bin/python manage.py axes_reset_username USERNAME` (**not exercised**) |
| A session may be stolen | **Account**, then **Sign out everywhere** |
| Replace recovery codes | **Account**. Old codes stop working |

`reset_mfa` asks for no confirmation. Anyone with access to these files can use it.

Sessions end after 8 hours without activity, or when the browser closes. Signing out deletes the
session on the server.

## 11. Local data, retention, and backups

| Item | Location | Holds |
| --- | --- | --- |
| Database | `dashboard/dashboard.sqlite3` | Password hashes, **unencrypted MFA keys and recovery codes**, token hashes, consent records, runs, audit events |
| Uploaded evidence | `dashboard/private-evidence/` | Unencrypted uploads |
| Local secret | `dashboard/.env` | The key that signs sessions |

Git ignores all three. Never commit or share them. `dashboard/.env.example` holds placeholders only.

**Retention:** nothing expires, and there is no delete button. Revoking and withdrawing keep existing
records. Removing data means editing the database and files directly. Do not store real locations or
personal data here.

**Backups are not automatic.** To make one, stop the server, then from `dashboard/`:

```sh
mkdir -m 700 ~/routemock-dashboard-backup
cp -p dashboard.sqlite3 .env ~/routemock-dashboard-backup/
cp -Rp private-evidence ~/routemock-dashboard-backup/
```

Keep it on encrypted storage, as it holds everything above. To restore, stop the server and copy the
files back. A fresh `dashboard/.env` only signs everyone out. The `private-evidence` line fails if
nothing has been uploaded yet, which is harmless.

## 12. Troubleshooting

| Symptom | Likely cause and fix |
| --- | --- |
| "That port is already in use" | Another server is running. Stop it, or use another port such as `127.0.0.1:8001` |
| "The development server only runs on loopback" | Use `127.0.0.1:8000` |
| "no usable ROUTEMOCK_SECRET_KEY" | `dashboard/.env` holds a placeholder. Delete it, and a new one is created |
| "Those details were not accepted" | Wrong details or a lockout. The message is the same on purpose |
| Authenticator code refused | Check the computer's and phone's clocks. A code works once |
| Sent back to "Confirm it is you" | The 5-minute confirmation ended. Enter your password and a new code |
| Phone cannot reach the dashboard | Check `adb reverse --list` shows `tcp:8000`, the server is running, and the build is a debug build |
| Pairing refused | Consent for dashboard pairing is missing, or the code expired or was used |
| Too many pairing attempts | Wait 10 minutes, or restart the dashboard |
| Request never reaches the phone | RouteMock must be on screen, remote requests on at both ends, and the required consents in place. Expired after 5 minutes |
| Remote road route does nothing | Not supported. See section 8 |
| Dashboard shows old consent | The phone has not synced. Open RouteMock with a connection, or revoke the device |

## 13. Not supported

- Hosting on the internet or a shared network
- Passkeys or other phishing-resistant sign-in
- Malware scanning of uploads
- Deleting records, or retention limits
- Self-service account recovery, and email or notifications
- A second authenticator
- Remote road routes
- Pairing a release build with a local dashboard
- Live traffic

**Deployment blockers.** All of these remain open, and none has been tested:

- Production settings and a real secret, with TLS and proxy handling reviewed
- A production server rather than `runserver`
- A production database, with restricted access and tested backups
- A shared cache for the pairing limit
- Encryption or stronger protection for stored MFA material and evidence
- Log handling, retention, and deletion
- Legal review of the consent documents
- An independent security review

See [DEPLOYMENT.md](DEPLOYMENT.md) and [DASHBOARD_LIMITATIONS.md](DASHBOARD_LIMITATIONS.md).
