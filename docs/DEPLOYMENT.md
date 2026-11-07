# Dashboard deployment

**The dashboard has not been deployed, and it must not be exposed until this review is
done.** It is built and tested for local use only.

## Running it locally

```sh
cd dashboard
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python manage.py migrate
.venv/bin/python manage.py bootstrap_admin
.venv/bin/python manage.py runserver 127.0.0.1:8000
```

The [dashboard guide](DASHBOARD_GUIDE.md) covers setup and use in full. `bootstrap_admin` asks for a username and a hidden password of your choosing. The first
sign-in enrolls an authenticator app and shows recovery codes once. If those are all lost,
someone with server access can run `manage.py reset_mfa USERNAME`. There are no default
accounts, and the project ships no fixtures, seed data, pairing codes, or credentials.

Do this on a machine you control, and do not paste the password anywhere. Choose a long,
unique, random passphrase: the policy will accept predictable long passwords, so length alone
is not enough. Store recovery codes offline. Take no screenshots of them, of the setup key, or
of pairing codes. `dashboard/.env` holds a generated local secret, is git-ignored, and is
never used when `ROUTEMOCK_DEBUG=0`.

Tests: `.venv/bin/python manage.py test core`.

### Pairing a phone locally

```sh
adb reverse tcp:8000 tcp:8000
```

Create a pairing code in the dashboard. On the phone, open More, choose Allow for dashboard
pairing (the dashboard refuses a phone without that consent), and enter `http://127.0.0.1:8000` with the code. Only debug builds accept that address.

## Requirements before any exposure

- [ ] `ROUTEMOCK_DEBUG=0`, a real `ROUTEMOCK_SECRET_KEY`, and `ROUTEMOCK_ALLOWED_HOSTS` set
- [ ] HTTPS terminated correctly, and `SECURE_PROXY_SSL_HEADER` reviewed if behind a proxy
- [ ] A production WSGI server rather than `runserver`, which refuses anything but loopback
- [ ] PostgreSQL or similar, with restricted access and tested backups
- [ ] A shared cache, as the pairing limiter uses per-process memory
- [ ] Evidence folder outside the web root with restricted permissions
- [ ] Log handling and retention decided, and dependency updates scheduled
- [ ] Deletion of dashboard records implemented
- [ ] Stored MFA keys and recovery codes protected, as they are unencrypted in the database
- [ ] Passkeys considered for phishing resistance
- [ ] Legal review of the consent documents
- [ ] An independent security review

These are deployment-dependent and **not tested**.

## Estimated costs

None today: it runs locally, and no paid service is used. These are rough estimates, not
quotes, and should be checked before relying on them.

| Item | Rough cost |
| --- | --- |
| Small virtual server | About $5 to $15 a month |
| Domain name | About $10 to $20 a year |
| TLS certificate | Free with Let's Encrypt |
| Managed database, if wanted | About $15 or more a month |
| Self-hosted Valhalla | Depends on region; planet-scale data needs far larger servers |
