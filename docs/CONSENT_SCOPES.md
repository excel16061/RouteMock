# Consent scopes

| Scope | Required | Allows |
| --- | --- | --- |
| Responsible Use and Terms | Yes | Using the app |
| Privacy Notice | Yes | Using the app |
| Mock Location Consent | Yes | Starting simulation, locally or by remote request |
| Dashboard Pairing and Data Upload | No | Pairing, uploads, and receiving remote requests |

## What each withdrawal does

| Withdrawn | Local simulation | Remote starts | Uploads | Dashboard pairing |
| --- | --- | --- | --- | --- |
| **Mock consent** (or Terms or Privacy) | **Stops at once**, and cannot start | **Stopped**: phone ignores requests, server ends remote control and expires queued commands | May continue | Kept |
| **Upload consent** | **Keeps running**, and can start | **Stopped**, as the pairing ends | **Stopped** | Ended and revoked |
| **Withdraw all** | **Stops** | **Stopped** | **Stopped** | Ended and revoked |

Upload consent also ends remote requests because they travel over the same pairing. Withdrawing it
never stops a local simulation, which is a separate decision.

With a required consent withdrawn, the app shows the consent screen and nothing else. Withdraw all
and receipt export remain available there.

## What happens while offline

Withdrawal takes effect on the phone immediately, connected or not. Only the server can lag.

| | Phone, at once | Server, until delivered | Delivered by |
| --- | --- | --- | --- |
| Mock consent offline | Simulation stopped, requests ignored | Still shows simulation consent and remote control on | Queued receipt, on reconnect or next launch |
| Upload consent or Withdraw all offline | Credential removed from use, uploads and requests stopped | Still shows the device connected | Queued revocation, on reconnect or next launch |

During that delay the server can accept new commands, which the phone will not act on and which
expire after 5 minutes. The dashboard's display is simply out of date. Delivery retries about every
60 seconds while the app runs. **The app must run to deliver it:** if it is closed and never
reopened, the server is not told, and the operator must revoke the device. A queued revocation
holds the credential encrypted on the phone, and uses it only for that one request.

## Records

Events are appended, never rewritten, with one exception: 0.4.2 replaced a wrongly defaulted `USER`
label on 0.4.0 events with `UNKNOWN`. Later concerns are recorded as a separate `corrections` entry,
which leaves the event as it was. See the [verification report](samples/verification-report.md).
