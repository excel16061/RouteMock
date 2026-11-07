# Privacy and retention

See the in-app documents in `app/src/main/assets/legal/` for what users are shown.

| Data | Kept where | Kept for | How to remove |
| --- | --- | --- | --- |
| Saved routes | Phone | Until deleted | Saved Routes, or uninstall |
| Consent receipt | Phone | Until uninstalled, as a record | Uninstall |
| Real location | Phone memory | Until the process ends | Automatic |
| Dashboard records | Dashboard database | Until the operator deletes them | No in-app deletion yet; this is a gap |
| Audit events | Dashboard database | No automatic limit yet | Operator action |
| Provider logs | The provider | Their policy | Ask the provider |

No legal name, signature, ID, or date of birth is collected. Device records hold a chosen
label, model, and Android version. Serial numbers and advertising IDs are not read.

Withdrawing required consent stops any simulation at once and returns to the setup screen.
Withdrawing upload consent stops uploads and remote requests, removes the credential from the
phone, and tells the dashboard to revoke it, which also cancels unfinished commands. Withdrawal
takes effect on the phone at once. If the phone is offline, the revocation is kept encrypted and
is sent when the app next runs with a connection. Until then the dashboard still shows the device
as connected and can queue commands, which the phone will not act on and which expire. If the
phone never reconnects, the operator must revoke the device. Withdrawing a required consent also
ends remote control on the dashboard, while uploads may continue. Withdrawal does not delete
what was already uploaded.

Receipts record who acted: `USER`, `AUTOMATED_TEST` for debug test runs, or `UNKNOWN` for
events made before origins existed. Only a debug build can record `AUTOMATED_TEST`, and only
through a marker file that needs `adb`.
