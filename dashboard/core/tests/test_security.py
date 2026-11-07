import io
import json
import logging
import tempfile
import time
import zipfile
from datetime import timedelta
from unittest import mock

from django.contrib.auth import get_user_model
from django.core.cache import cache
from django.core.exceptions import ValidationError
from django.core.files.uploadedfile import SimpleUploadedFile
from django.core.management import CommandError, call_command
from django.test import Client, TestCase, override_settings
from django.utils import timezone
from django_otp.oath import totp
from django_otp.plugins.otp_static.models import StaticDevice
from django_otp.plugins.otp_totp.models import TOTPDevice

from core import evidence
from core.models import (
    AuditEvent,
    CheckResult,
    Command,
    ConsentRecord,
    Device,
    EvidenceFile,
    PairingChallenge,
    Profile,
    RouteDraft,
    RouteDraftVersion,
    TestRun,
    hash_secret,
)

PASSWORD = "a-long-test-only-passphrase-9417"
DRAFT = '{"mode":"STATIONARY","start":{"latitude":10.0,"longitude":20.0}}'


def receipt(*types):
    doc = {"scope": "DASHBOARD_UPLOAD", "version": 1, "sha256": "0" * 64}
    return {"schema": 1, "events": [{"type": kind, "documents": [doc]} for kind in types]}


def full_receipt(withdrawn=()):
    scopes = ("TERMS", "PRIVACY", "MOCK_LOCATION", "DASHBOARD_UPLOAD")
    docs = [{"scope": scope, "version": 1, "sha256": "0" * 64} for scope in scopes]
    events = [{"type": "ACCEPTED", "documents": docs}]
    if withdrawn:
        events.append({"type": "WITHDRAWN", "documents": [d for d in docs if d["scope"] in withdrawn]})
    return {"schema": 1, "events": events}


def make_user(name, role=Profile.Role.ADMIN):
    user = get_user_model().objects.create_user(username=name, password=PASSWORD)
    Profile.objects.update_or_create(user=user, defaults={"role": role})
    device = TOTPDevice.objects.create(user=user, name="a", confirmed=True)
    return user, device


def code_for(device):
    return f"{totp(device.bin_key, step=device.step, t0=device.t0, digits=device.digits):06d}"


def signed_in(user, device, reauth=True):
    """A client with a verified session, as the real flow leaves it."""
    client = Client()
    client.force_login(user)
    session = client.session
    session["otp_device_id"] = device.persistent_id
    if reauth:
        session["reauth_at"] = time.time()
    session.save()
    return client


def paired(owner, label="Phone", remote=False):
    token = "token-" + label + "-" + str(owner.pk) + "-with-plenty-of-length"
    device = Device.objects.create(
        owner=owner,
        label=label,
        token_hash=hash_secret(token),
        scopes=list(Device.SCOPES),
        remote_control_enabled=remote,
        consent_active=True,
        simulation_consent_active=True,
    )
    return device, {"HTTP_AUTHORIZATION": f"Bearer {token}"}


def draft_for(owner):
    draft = RouteDraft.objects.create(owner=owner, name="Synthetic")
    version = RouteDraftVersion.objects.create(draft=draft, number=1, payload=json.loads(DRAFT))
    return draft, version


def command_for(owner, device, version, **fields):
    defaults = {"expires_at": timezone.now() + timedelta(minutes=5)}
    return Command.objects.create(owner=owner, device=device, draft_version=version, **{**defaults, **fields})


class Base(TestCase):
    def setUp(self):
        cache.clear()
        self.alice, self.alice_totp = make_user("alice")
        self.bob, self.bob_totp = make_user("bob")
        self.client_a = signed_in(self.alice, self.alice_totp)
        self.client_b = signed_in(self.bob, self.bob_totp)


class UnauthenticatedAccess(TestCase):
    PAGES = ["/", "/research", "/consent", "/audit", "/pairing", "/account"]

    def test_pages_redirect_to_login(self):
        for page in self.PAGES:
            self.assertEqual(Client().get(page).status_code, 302, page)

    def test_password_alone_does_not_reach_any_page(self):
        user, _ = make_user("carol")
        client = Client()
        client.force_login(user)
        for page in self.PAGES:
            response = client.get(page)
            self.assertEqual(response.status_code, 302, page)
            self.assertNotEqual(response["Location"], page)

    def test_device_api_needs_a_token(self):
        self.assertEqual(Client().get("/api/device/commands").status_code, 401)
        self.assertEqual(Client().post("/api/device/runs", "{}", "application/json").status_code, 401)

    def test_a_wrong_token_is_refused(self):
        response = Client().get("/api/device/commands", HTTP_AUTHORIZATION="Bearer nonsense")
        self.assertEqual(response.status_code, 401)

    def test_no_public_registration_exists(self):
        for path in ("/register", "/signup", "/admin/", "/accounts/signup"):
            self.assertEqual(Client().get(path).status_code, 404, path)


class Login(TestCase):
    def setUp(self):
        cache.clear()
        self.user, self.totp = make_user("dave")

    def test_full_login_needs_password_then_code(self):
        client = Client()
        client.post("/login", {"username": "dave", "password": PASSWORD})
        self.assertEqual(client.get("/").status_code, 302)
        client.post("/mfa", {"code": code_for(self.totp)})
        self.assertEqual(client.get("/").status_code, 200)

    def test_wrong_password_and_unknown_user_look_identical(self):
        wrong = Client().post("/login", {"username": "dave", "password": "wrong"})
        missing = Client().post("/login", {"username": "nobody", "password": "wrong"})
        self.assertEqual(wrong.status_code, missing.status_code)
        self.assertContains(wrong, "Those details were not accepted.")
        self.assertContains(missing, "Those details were not accepted.")

    def test_repeated_failures_lock_out_even_the_right_password(self):
        client = Client()
        for _ in range(6):
            client.post("/login", {"username": "dave", "password": "wrong"})
        response = client.post("/login", {"username": "dave", "password": PASSWORD})
        self.assertContains(response, "Those details were not accepted.")
        self.assertEqual(client.get("/mfa").status_code, 302)

    def test_a_wrong_code_does_not_verify(self):
        client = Client()
        client.post("/login", {"username": "dave", "password": PASSWORD})
        client.post("/mfa", {"code": "000000"})
        self.assertEqual(client.get("/").status_code, 302)

    def test_session_key_rotates_at_login(self):
        client = Client()
        client.get("/login")
        before = client.session.session_key
        client.post("/login", {"username": "dave", "password": PASSWORD})
        self.assertNotEqual(before, client.session.session_key)

    def test_logout_ends_the_session_on_the_server(self):
        client = signed_in(self.user, self.totp)
        self.assertEqual(client.get("/").status_code, 200)
        stolen = client.cookies["sessionid"].value
        client.post("/logout")
        replay = Client()
        replay.cookies["sessionid"] = stolen
        self.assertEqual(replay.get("/").status_code, 302)

    def test_sign_out_everywhere_ends_other_sessions(self):
        first = signed_in(self.user, self.totp)
        second = signed_in(self.user, self.totp)
        self.assertEqual(second.get("/").status_code, 200)
        first.post("/account", {"action": "sign_out_everywhere"})
        self.assertEqual(second.get("/").status_code, 302)

    def test_logout_is_not_a_get(self):
        client = signed_in(self.user, self.totp)
        self.assertEqual(client.get("/logout").status_code, 405)

    def test_csrf_is_enforced_on_browser_forms(self):
        client = Client(enforce_csrf_checks=True)
        self.assertEqual(client.post("/login", {"username": "dave", "password": PASSWORD}).status_code, 403)

    def test_passwords_are_stored_as_argon2id(self):
        self.assertTrue(self.user.password.startswith("argon2$argon2id$"))

    def test_recovery_codes_work_once(self):
        static = StaticDevice.objects.create(user=self.user, name="recovery")
        static.token_set.create(token="onetimecode")
        for expected in (200, 302):
            client = Client()
            client.post("/login", {"username": "dave", "password": PASSWORD})
            client.post("/mfa", {"code": "onetimecode"})
            self.assertEqual(client.get("/").status_code, expected)

    def test_security_headers_are_present(self):
        response = Client().get("/login")
        self.assertEqual(response["X-Frame-Options"], "DENY")
        self.assertEqual(response["X-Content-Type-Options"], "nosniff")
        self.assertIn("default-src 'none'", response["Content-Security-Policy"])
        self.assertIn("frame-ancestors 'none'", response["Content-Security-Policy"])

    def test_session_cookie_is_httponly_and_strict(self):
        client = Client()
        client.post("/login", {"username": "dave", "password": PASSWORD})
        cookie = client.cookies["sessionid"]
        self.assertTrue(cookie["httponly"])
        self.assertEqual(cookie["samesite"], "Strict")


class Reauthentication(Base):
    def test_sensitive_actions_need_recent_confirmation(self):
        client = signed_in(self.alice, self.alice_totp, reauth=False)
        device, _ = paired(self.alice)
        for response in (
            client.post("/pairing", {"label": "x"}),
            client.post(f"/devices/{device.id}", {"action": "remote_on"}),
            client.get("/account"),
        ):
            self.assertEqual(response.status_code, 302)
            self.assertIn("/reauth", response["Location"])
        device.refresh_from_db()
        self.assertFalse(device.remote_control_enabled)

    def test_old_confirmation_expires(self):
        session = self.client_a.session
        session["reauth_at"] = time.time() - 301
        session.save()
        self.assertIn("/reauth", self.client_a.get("/account")["Location"])

    def test_reauth_needs_password_and_code(self):
        client = signed_in(self.alice, self.alice_totp, reauth=False)
        client.post("/reauth", {"password": "wrong", "code": code_for(self.alice_totp)})
        self.assertEqual(client.get("/account").status_code, 302)
        client.post("/reauth", {"password": PASSWORD, "code": code_for(self.alice_totp)})
        self.assertEqual(client.get("/account").status_code, 200)

    def test_reauth_does_not_redirect_off_site(self):
        client = signed_in(self.alice, self.alice_totp, reauth=False)
        response = client.post(
            "/reauth?next=https://evil.example/", {"password": PASSWORD, "code": code_for(self.alice_totp)}
        )
        self.assertNotIn("evil.example", response["Location"])


class RoleAccess(TestCase):
    def setUp(self):
        cache.clear()
        self.admin, _ = make_user("admin1")
        self.viewer, totp_device = make_user("viewer1", Profile.Role.VIEWER)
        self.client_v = signed_in(self.viewer, totp_device)

    def test_viewer_can_read_but_not_change(self):
        self.assertEqual(self.client_v.get("/").status_code, 200)
        device, _ = paired(self.viewer)
        draft, _ = draft_for(self.viewer)
        run = TestRun.objects.create(owner=self.viewer, title="r", protocol_version="1")
        for path, data in (
            ("/pairing", {"label": "x"}),
            (f"/devices/{device.id}", {"action": "revoke"}),
            ("/drafts/save", {"name": "n", "payload": DRAFT}),
            (f"/drafts/{draft.id}/start", {"device_id": str(device.id)}),
            (f"/runs/{run.run_id}/finalize", {}),
        ):
            self.assertEqual(self.client_v.post(path, data).status_code, 403, path)
        self.assertEqual(self.client_v.get("/audit").status_code, 403)
        device.refresh_from_db()
        self.assertIsNone(device.revoked_at)


class CrossAccountAccess(Base):
    def test_another_accounts_records_look_missing(self):
        device, _ = paired(self.bob)
        draft, _ = draft_for(self.bob)
        run = TestRun.objects.create(owner=self.bob, title="secret run", protocol_version="1")
        item = EvidenceFile.objects.create(run=run, name="a.txt", kind="raw", stored_as="x", content_type="text/plain", size=1, sha256="0" * 64)
        for path in (f"/drafts/{draft.id}", f"/runs/{run.run_id}", f"/evidence/{item.id}", f"/runs/{run.run_id}/export/json", f"/runs/{run.run_id}/export/zip"):
            self.assertEqual(self.client_a.get(path).status_code, 404, path)
        for path, data in (
            (f"/devices/{device.id}", {"action": "revoke"}),
            (f"/runs/{run.run_id}/finalize", {}),
            (f"/drafts/{draft.id}/start", {"device_id": str(device.id)}),
            ("/drafts/save", {"name": "n", "payload": DRAFT, "draft_id": str(draft.id)}),
        ):
            self.assertEqual(self.client_a.post(path, data).status_code, 404, path)
        device.refresh_from_db()
        self.assertIsNone(device.revoked_at)

    def test_lists_show_only_your_own_records(self):
        TestRun.objects.create(owner=self.bob, title="bobs private run", protocol_version="1")
        paired(self.bob, "bobs phone")
        self.assertNotContains(self.client_a.get("/"), "bobs private run")
        self.assertNotContains(self.client_a.get("/"), "bobs phone")
        self.assertNotContains(self.client_a.get("/research"), "bobs private run")

    def test_a_command_cannot_target_another_accounts_device(self):
        draft, _ = draft_for(self.alice)
        device, _ = paired(self.bob, remote=True)
        response = self.client_a.post(f"/drafts/{draft.id}/start", {"device_id": str(device.id)})
        self.assertEqual(response.status_code, 404)
        self.assertFalse(Command.objects.exists())

    def test_a_device_cannot_see_or_touch_another_devices_commands(self):
        _, version = draft_for(self.alice)
        mine, _ = paired(self.alice, "mine", remote=True)
        _, other_headers = paired(self.alice, "other", remote=True)
        command = command_for(self.alice, mine, version, status=Command.Status.DELIVERED)
        self.assertEqual(Client().get("/api/device/commands", **other_headers).json()["commands"], [])
        response = Client().post(
            f"/api/device/commands/{command.id}/status",
            json.dumps({"nonce": command.nonce, "status": "accepted"}),
            "application/json",
            **other_headers,
        )
        self.assertEqual(response.status_code, 404)


class Pairing(Base):
    def make_code(self, **fields):
        code = PairingChallenge.new_code()
        defaults = {"expires_at": timezone.now() + timedelta(minutes=10)}
        PairingChallenge.objects.create(owner=self.alice, code_hash=hash_secret(code), label="Phone", **{**defaults, **fields})
        return code

    def pair(self, code, consent=None):
        body = {"code": code, "consent_receipt": consent if consent is not None else receipt("ACCEPTED")}
        return Client().post("/api/device/pair", json.dumps(body), "application/json")

    def test_a_code_pairs_once_with_remote_control_off(self):
        code = self.make_code()
        response = self.pair(code)
        self.assertEqual(response.status_code, 200)
        self.assertFalse(Device.objects.get().remote_control_enabled)
        self.assertEqual(self.pair(code).status_code, 400)
        self.assertEqual(Device.objects.count(), 1)

    def test_an_expired_code_is_refused(self):
        code = self.make_code(expires_at=timezone.now() - timedelta(seconds=1))
        self.assertEqual(self.pair(code).status_code, 400)
        self.assertFalse(Device.objects.exists())

    def test_guessing_is_rate_limited(self):
        statuses = [self.pair(f"guess{i}").status_code for i in range(12)]
        self.assertEqual(statuses[-1], 429)

    def test_codes_and_tokens_are_stored_only_as_hashes(self):
        code = self.make_code()
        token = self.pair(code).json()["token"]
        self.assertFalse(PairingChallenge.objects.filter(code_hash=code).exists())
        self.assertFalse(Device.objects.filter(token_hash=token).exists())

    def test_creating_a_code_shows_it_once_and_stores_no_copy(self):
        response = self.client_a.post("/pairing", {"label": "Test"})
        self.assertEqual(response["Cache-Control"], "no-store")
        self.assertNotContains(self.client_a.get("/pairing"), "<code>")


class ConsentEnforcement(Base):
    """The backend does not take the phone's word for it: it holds its own state."""

    def setUp(self):
        super().setUp()
        self.device, self.headers = paired(self.alice, remote=True)
        _, self.version = draft_for(self.alice)

    def code(self):
        code = PairingChallenge.new_code()
        PairingChallenge.objects.create(
            owner=self.alice, code_hash=hash_secret(code), label="P", expires_at=timezone.now() + timedelta(minutes=5)
        )
        return code

    def pair(self, code, consent):
        return Client().post("/api/device/pair", json.dumps({"code": code, "consent_receipt": consent}), "application/json")

    def upload_receipt(self, body):
        return Client().post("/api/device/consent", json.dumps(body), "application/json", **self.headers)

    def test_pairing_without_a_consent_receipt_is_refused(self):
        before = Device.objects.count()
        for consent in (None, {}, {"events": []}, {"events": "x"}, receipt("WITHDRAWN"), receipt("ACCEPTED", "WITHDRAWN")):
            self.assertEqual(self.pair(self.code(), consent).status_code, 403, consent)
        self.assertEqual(Device.objects.count(), before)

    def test_refused_pairing_does_not_use_up_the_code(self):
        code = self.code()
        self.assertEqual(self.pair(code, None).status_code, 403)
        self.assertEqual(self.pair(code, receipt("ACCEPTED")).status_code, 200)

    def test_a_consent_acceptance_after_withdrawal_counts(self):
        self.assertEqual(self.pair(self.code(), receipt("ACCEPTED", "WITHDRAWN", "ACCEPTED")).status_code, 200)

    def test_pairing_stores_the_receipt(self):
        self.pair(self.code(), receipt("ACCEPTED"))
        self.assertEqual(ConsentRecord.objects.count(), 1)

    def test_a_withdrawal_receipt_ends_uploads_commands_and_remote_control(self):
        command = command_for(self.alice, self.device, self.version)
        response = self.upload_receipt(receipt("ACCEPTED", "WITHDRAWN"))
        self.assertFalse(response.json()["consent_active"])
        self.device.refresh_from_db()
        self.assertFalse(self.device.remote_control_enabled)
        self.assertFalse(self.device.consent_active)
        command.refresh_from_db()
        self.assertEqual(command.status, "expired")
        self.assertEqual(Client().get("/api/device/commands", **self.headers).json()["error"], "consent_required")
        run = Client().post("/api/device/runs", '{"title":"x"}', "application/json", **self.headers)
        self.assertEqual(run.status_code, 403)
        self.assertEqual(self.upload_receipt(receipt("ACCEPTED")).status_code, 403)

    def test_a_device_without_consent_cannot_be_given_remote_control_or_commands(self):
        self.device.consent_active = False
        self.device.remote_control_enabled = False
        self.device.save()
        self.client_a.post(f"/devices/{self.device.id}", {"action": "remote_on"})
        self.device.refresh_from_db()
        self.assertFalse(self.device.remote_control_enabled)
        response = self.client_a.post(f"/drafts/{self.version.draft.id}/start", {"device_id": str(self.device.id)})
        self.assertEqual(response.status_code, 404)

    def test_withdrawing_mock_consent_ends_remote_control_but_not_uploads(self):
        command = command_for(self.alice, self.device, self.version)
        body = self.upload_receipt(full_receipt(withdrawn=("MOCK_LOCATION",))).json()
        self.assertEqual((body["consent_active"], body["simulation_consent_active"]), (True, False))
        self.device.refresh_from_db()
        self.assertFalse(self.device.remote_control_enabled)
        self.assertIsNone(self.device.revoked_at)
        command.refresh_from_db()
        self.assertEqual(command.status, "expired")
        self.assertEqual(Client().get("/api/device/commands", **self.headers).json()["commands"], [])
        run = Client().post("/api/device/runs", '{"title":"x"}', "application/json", **self.headers)
        self.assertEqual(run.status_code, 200)

    def test_remote_control_cannot_be_turned_back_on_without_simulation_consent(self):
        self.upload_receipt(full_receipt(withdrawn=("TERMS",)))
        self.client_a.post(f"/devices/{self.device.id}", {"action": "remote_on"})
        self.device.refresh_from_db()
        self.assertFalse(self.device.remote_control_enabled)
        response = self.client_a.post(f"/drafts/{self.version.draft.id}/start", {"device_id": str(self.device.id)})
        self.assertEqual(response.status_code, 404)

    def test_a_flipped_flag_alone_delivers_nothing_without_simulation_consent(self):
        self.device.simulation_consent_active = False
        self.device.save()
        command_for(self.alice, self.device, self.version)
        self.assertEqual(Client().get("/api/device/commands", **self.headers).json()["commands"], [])

    def test_simulation_consent_can_return_after_a_new_acceptance(self):
        self.upload_receipt(full_receipt(withdrawn=("MOCK_LOCATION",)))
        body = self.upload_receipt(full_receipt()).json()
        self.assertTrue(body["simulation_consent_active"])
        self.device.refresh_from_db()
        self.assertFalse(self.device.remote_control_enabled)

    def test_pairing_with_upload_consent_only_cannot_receive_commands(self):
        token = self.pair(self.code(), receipt("ACCEPTED")).json()["token"]
        device = Device.objects.get(token_hash=hash_secret(token))
        self.assertFalse(device.simulation_consent_active)

    def test_a_late_revocation_after_commands_were_queued_expires_them(self):
        command = command_for(self.alice, self.device, self.version, status=Command.Status.DELIVERED)
        Client().post("/api/device/withdraw", "{}", "application/json", **self.headers)
        command.refresh_from_db()
        self.assertEqual(command.status, "expired")

    def test_repeating_a_revocation_is_refused_as_already_finished(self):
        first = Client().post("/api/device/withdraw", "{}", "application/json", **self.headers)
        second = Client().post("/api/device/withdraw", "{}", "application/json", **self.headers)
        self.assertEqual((first.status_code, second.status_code), (200, 401))

    def test_a_phone_can_revoke_its_own_credential(self):
        command = command_for(self.alice, self.device, self.version)
        response = Client().post("/api/device/withdraw", "{}", "application/json", **self.headers)
        self.assertEqual(response.status_code, 200)
        self.device.refresh_from_db()
        self.assertIsNotNone(self.device.revoked_at)
        self.assertFalse(self.device.remote_control_enabled)
        command.refresh_from_db()
        self.assertEqual(command.status, "expired")
        self.assertEqual(Client().get("/api/device/commands", **self.headers).status_code, 401)

    def test_revoking_itself_works_after_consent_was_withdrawn(self):
        self.upload_receipt(receipt("WITHDRAWN"))
        self.assertEqual(Client().post("/api/device/withdraw", "{}", "application/json", **self.headers).status_code, 200)

    def test_one_phones_withdrawal_leaves_another_phone_alone(self):
        other, other_headers = paired(self.alice, "Other", remote=True)
        Client().post("/api/device/withdraw", "{}", "application/json", **self.headers)
        other.refresh_from_db()
        self.assertTrue(other.consent_active)
        self.assertEqual(Client().get("/api/device/commands", **other_headers).status_code, 200)

    def test_withdrawal_needs_a_valid_token(self):
        self.assertEqual(Client().post("/api/device/withdraw", "{}", "application/json").status_code, 401)


class NoProductionDefaults(TestCase):
    """Runs against the database that migrations alone produce."""

    def test_a_fresh_database_has_no_accounts_credentials_or_sessions(self):
        from django.contrib.sessions.models import Session

        self.assertEqual(get_user_model().objects.count(), 0)
        self.assertEqual(Device.objects.count(), 0)
        self.assertEqual(PairingChallenge.objects.count(), 0)
        self.assertEqual(TOTPDevice.objects.count(), 0)
        self.assertEqual(Session.objects.count(), 0)

    def test_no_fixture_files_ship_with_the_project(self):
        from pathlib import Path

        from django.conf import settings

        found = [p for p in Path(settings.BASE_DIR, "core").rglob("*") if "fixtures" in p.parts]
        self.assertEqual(found, [])

    def test_production_mode_refuses_to_start_without_a_real_secret(self):
        import os
        import subprocess
        import sys

        env = {k: v for k, v in os.environ.items() if k != "ROUTEMOCK_SECRET_KEY"}
        env["ROUTEMOCK_DEBUG"] = "0"
        result = subprocess.run(
            [sys.executable, "manage.py", "check"], capture_output=True, text=True, env=env, cwd=settings_dir()
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("ROUTEMOCK_SECRET_KEY", result.stderr)


def settings_dir():
    from django.conf import settings

    return settings.BASE_DIR


class PasswordPolicyLimits(TestCase):
    """Records what the policy does and does not catch, so nobody assumes more."""

    def validate(self, password):
        from django.contrib.auth.password_validation import validate_password

        validate_password(password)

    def test_refused(self):
        for password in ("short", "1234567890123456"):
            with self.assertRaises(ValidationError):
                self.validate(password)

    def test_known_gap_predictable_long_passwords_are_accepted(self):
        for password in ("password12345678", "passwordpassword", "aaaaaaaaaaaaaaaa"):
            self.validate(password)


class RevokedCredentials(Base):
    def test_a_revoked_device_is_refused_everywhere(self):
        device, headers = paired(self.alice, remote=True)
        self.assertEqual(Client().get("/api/device/commands", **headers).status_code, 200)
        self.client_a.post(f"/devices/{device.id}", {"action": "revoke"})
        self.assertEqual(Client().get("/api/device/commands", **headers).status_code, 401)
        self.assertEqual(Client().post("/api/device/runs", '{"title":"x"}', "application/json", **headers).status_code, 401)

    def test_revoking_turns_remote_control_off(self):
        device, _ = paired(self.alice, remote=True)
        self.client_a.post(f"/devices/{device.id}", {"action": "revoke"})
        device.refresh_from_db()
        self.assertFalse(device.remote_control_enabled)

    def test_a_token_with_a_missing_scope_is_refused(self):
        device, headers = paired(self.alice)
        device.scopes = ["commands"]
        device.save()
        self.assertEqual(Client().post("/api/device/runs", '{"title":"x"}', "application/json", **headers).status_code, 403)


class Commands(Base):
    def setUp(self):
        super().setUp()
        _, self.version = draft_for(self.alice)
        self.device, self.headers = paired(self.alice, remote=True)

    def report(self, command, status, nonce=None):
        return Client().post(
            f"/api/device/commands/{command.id}/status",
            json.dumps({"nonce": nonce if nonce is not None else command.nonce, "status": status}),
            "application/json",
            **self.headers,
        )

    def test_remote_control_off_delivers_nothing(self):
        self.device.remote_control_enabled = False
        self.device.save()
        command_for(self.alice, self.device, self.version)
        body = Client().get("/api/device/commands", **self.headers).json()
        self.assertEqual(body, {"remote_control": False, "commands": []})

    def test_a_command_is_delivered_once(self):
        command_for(self.alice, self.device, self.version)
        first = Client().get("/api/device/commands", **self.headers).json()["commands"]
        second = Client().get("/api/device/commands", **self.headers).json()["commands"]
        self.assertEqual((len(first), len(second)), (1, 0))

    def test_full_lifecycle_is_reported_accurately(self):
        command = command_for(self.alice, self.device, self.version, status=Command.Status.DELIVERED)
        self.assertEqual(self.report(command, "accepted").status_code, 200)
        self.assertEqual(self.report(command, "executed").status_code, 200)
        command.refresh_from_db()
        self.assertEqual(command.status, "executed")

    def test_a_replayed_status_is_refused(self):
        command = command_for(self.alice, self.device, self.version, status=Command.Status.DELIVERED)
        self.report(command, "accepted")
        self.assertEqual(self.report(command, "accepted").status_code, 409)

    def test_executed_cannot_be_claimed_without_acceptance(self):
        command = command_for(self.alice, self.device, self.version, status=Command.Status.DELIVERED)
        self.assertEqual(self.report(command, "executed").status_code, 409)

    def test_a_wrong_nonce_is_refused(self):
        command = command_for(self.alice, self.device, self.version, status=Command.Status.DELIVERED)
        self.assertEqual(self.report(command, "accepted", nonce="wrong").status_code, 403)

    def test_an_expired_command_is_not_delivered_or_accepted(self):
        past = timezone.now() - timedelta(seconds=1)
        command = command_for(self.alice, self.device, self.version, expires_at=past)
        self.assertEqual(Client().get("/api/device/commands", **self.headers).json()["commands"], [])
        command.refresh_from_db()
        self.assertEqual(command.status, "expired")
        late = command_for(self.alice, self.device, self.version, expires_at=past, status=Command.Status.DELIVERED)
        self.assertEqual(self.report(late, "accepted").status_code, 409)

    def test_new_commands_need_remote_control_on(self):
        draft = self.version.draft
        self.device.remote_control_enabled = False
        self.device.save()
        self.assertEqual(self.client_a.post(f"/drafts/{draft.id}/start", {"device_id": str(self.device.id)}).status_code, 404)

    def test_saving_a_draft_sends_nothing_to_any_device(self):
        draft = self.version.draft
        self.client_a.post("/drafts/save", {"name": draft.name, "payload": DRAFT, "draft_id": str(draft.id)})
        self.assertEqual(draft.versions.count(), 2)
        self.assertFalse(Command.objects.exists())

    def test_draft_versions_cannot_be_rewritten(self):
        self.version.payload = {}
        with self.assertRaises(ValidationError):
            self.version.save()

    def test_invalid_drafts_are_refused(self):
        for payload in ('{"mode":"X"}', '{"mode":"ROAD","start":{"latitude":95,"longitude":0}}', "not json", '{"mode":"STATIONARY","start":{"latitude":1,"longitude":1},"extra":1}'):
            self.assertEqual(self.client_a.post("/drafts/save", {"name": "n", "payload": payload}).status_code, 400, payload)


class Uploads(Base):
    def setUp(self):
        super().setUp()
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        override = override_settings(EVIDENCE_ROOT=folder.name)
        override.enable()
        self.addCleanup(override.disable)
        self.run_record = TestRun.objects.create(owner=self.alice, title="r", protocol_version="1")

    def upload(self, name, data, kind="raw"):
        return self.client_a.post(
            f"/runs/{self.run_record.run_id}/evidence", {"file": SimpleUploadedFile(name, data), "kind": kind}
        )

    def test_a_valid_file_is_stored_with_its_hash(self):
        self.assertEqual(self.upload("notes.txt", b"hello").status_code, 302)
        item = EvidenceFile.objects.get()
        self.assertEqual(item.sha256, "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")

    def test_dangerous_or_mislabelled_files_are_refused(self):
        for name, data in (
            ("page.html", b"<script>alert(1)</script>"),
            ("run.exe", b"MZ"),
            ("image.png", b"not a png"),
            ("a.json", b"{broken"),
            ("a.txt", b"\xff\xfe\x00bad"),
            ("empty.txt", b""),
        ):
            self.assertEqual(self.upload(name, data).status_code, 400, name)
        self.assertFalse(EvidenceFile.objects.exists())

    def test_oversized_files_are_refused(self):
        with override_settings(EVIDENCE_MAX_BYTES=10):
            self.assertEqual(self.upload("a.txt", b"x" * 11).status_code, 400)

    def test_path_tricks_stay_inside_the_evidence_folder(self):
        safe, _ = evidence.check_upload("../../etc/passwd.txt", b"x")
        self.assertEqual(safe, "passwd.txt")

    def test_downloads_need_a_session_and_are_never_rendered_inline(self):
        self.upload("notes.txt", b"hello")
        item = EvidenceFile.objects.get()
        self.assertEqual(Client().get(f"/evidence/{item.id}").status_code, 302)
        response = self.client_a.get(f"/evidence/{item.id}")
        self.assertIn("attachment", response["Content-Disposition"])
        self.assertEqual(response["X-Content-Type-Options"], "nosniff")

    def test_no_upload_to_a_finalized_run(self):
        self.client_a.post(f"/runs/{self.run_record.run_id}/finalize")
        self.assertEqual(self.upload("a.txt", b"x").status_code, 404)


class ResearchRecords(Base):
    def setUp(self):
        super().setUp()
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        override = override_settings(EVIDENCE_ROOT=folder.name)
        override.enable()
        self.addCleanup(override.disable)
        self.record = TestRun.objects.create(owner=self.alice, title="r", protocol_version="1")
        CheckResult.objects.create(
            run=self.record, check_id="c1", title="t", expected="e", result="PASS", source="automated check"
        )

    def test_finalized_runs_cannot_change(self):
        self.client_a.post(f"/runs/{self.record.run_id}/finalize")
        self.record.refresh_from_db()
        self.record.title = "changed"
        with self.assertRaises(ValidationError):
            self.record.save()
        with self.assertRaises(ValidationError):
            CheckResult.objects.create(run=self.record, check_id="c2", title="t", expected="e", result="FAIL", source="manual observation")

    def test_corrections_link_back_and_leave_the_original(self):
        self.client_a.post(f"/runs/{self.record.run_id}/finalize")
        self.client_a.post(f"/runs/{self.record.run_id}/correct")
        correction = TestRun.objects.get(corrects=self.record)
        self.assertEqual(correction.state, "draft")
        self.record.refresh_from_db()
        self.assertEqual(self.record.title, "r")

    def test_counts_include_every_outcome(self):
        CheckResult.objects.create(run=self.record, check_id="c2", title="t", expected="e", result="NOT TESTED", source="manual observation")
        self.assertEqual(self.record.counts(), {"PASS": 1, "FAIL": 0, "NOT TESTED": 1, "INCONCLUSIVE": 0})

    def test_exports_match_their_manifest_and_separate_raw_from_derived(self):
        evidence.store(self.record, "raw.txt", b"raw", "raw")
        archive = zipfile.ZipFile(io.BytesIO(evidence.export_zip(self.record)))
        names = archive.namelist()
        self.assertIn("raw/raw.txt", names)
        self.assertIn("derived/run.json", names)
        import hashlib

        lines = [l for l in archive.read("MANIFEST.sha256").decode().splitlines() if not l.startswith("#")]
        self.assertEqual(len(lines), len(names) - 1)
        for line in lines:
            digest, path = line.split("  ")
            self.assertEqual(digest, hashlib.sha256(archive.read(path)).hexdigest())
        self.assertIn("do not prove", archive.read("MANIFEST.sha256").decode())

    def test_csv_export_neutralizes_formulas(self):
        CheckResult.objects.create(run=self.record, check_id="c3", title="=HYPERLINK(1)", expected="e", result="PASS", source="automated check")
        self.assertIn("'=HYPERLINK(1)", evidence.checks_csv(self.record))

    def test_html_report_escapes_stored_text(self):
        CheckResult.objects.create(run=self.record, check_id="c4", title="<script>x</script>", expected="e", result="PASS", source="automated check")
        self.assertNotIn("<script>x", evidence.report_html(self.record))


class DataLeakage(Base):
    def test_run_upload_refuses_location_fields(self):
        _, headers = paired(self.alice)
        for extra in ({"latitude": 1.0}, {"real_location": "x"}, {"device_serial": "abc"}):
            body = json.dumps({"title": "x", **extra})
            self.assertEqual(Client().post("/api/device/runs", body, "application/json", **headers).status_code, 400)
        self.assertFalse(TestRun.objects.exists())

    def test_oversized_device_bodies_are_refused(self):
        _, headers = paired(self.alice)
        body = json.dumps({"title": "x" * 70_000})
        self.assertEqual(Client().post("/api/device/runs", body, "application/json", **headers).status_code, 400)

    def test_secrets_never_reach_logs_or_audit_events(self):
        with self.assertLogs(level="DEBUG") as captured:
            logging.getLogger("probe").debug("start")
            Client().post("/login", {"username": "alice", "password": PASSWORD})
            Client().post("/login", {"username": "alice", "password": "wrong-secret-value"})
            client = signed_in(self.alice, self.alice_totp)
            page = client.post("/pairing", {"label": "x"}).content.decode()
        code = page.split("<code>")[1].split("</code>")[0]
        token = Client().post(
            "/api/device/pair", json.dumps({"code": code, "consent_receipt": receipt("ACCEPTED")}), "application/json"
        ).json()["token"]
        text = "\n".join(captured.output) + json.dumps(list(AuditEvent.objects.values_list("action", "target")))
        for secret in (PASSWORD, "wrong-secret-value", code, token):
            self.assertNotIn(secret, text)

    def test_exports_carry_no_token_hash_or_owner_details(self):
        device, _ = paired(self.alice)
        run = TestRun.objects.create(owner=self.alice, device=device, title="r", protocol_version="1")
        text = json.dumps(evidence.run_as_dict(run))
        self.assertNotIn(device.token_hash, text)
        self.assertNotIn("alice", text)


class Bootstrap(TestCase):
    def run_command(self, username, first, second=None):
        with mock.patch("builtins.input", return_value=username), mock.patch(
            "getpass.getpass", side_effect=[first, second if second is not None else first]
        ):
            out = io.StringIO()
            call_command("bootstrap_admin", stdout=out)
            return out.getvalue()

    def test_creates_an_admin_without_showing_the_password(self):
        output = self.run_command("erin", PASSWORD)
        self.assertNotIn(PASSWORD, output)
        user = get_user_model().objects.get(username="erin")
        self.assertEqual(Profile.objects.get(user=user).role, "admin")
        self.assertTrue(user.check_password(PASSWORD))

    def test_refuses_weak_or_mismatched_passwords(self):
        for first, second in (("short", "short"), ("1234567890123456", "1234567890123456"), (PASSWORD, PASSWORD + "x")):
            with self.assertRaises(CommandError):
                self.run_command("frank", first, second)
        self.assertFalse(get_user_model().objects.filter(username="frank").exists())

    def test_no_default_account_exists(self):
        self.assertFalse(get_user_model().objects.exists())


class LocalConfigurationTests(TestCase):
    def test_the_secret_file_format_and_placeholder(self):
        from django.core.exceptions import ImproperlyConfigured

        from routemock_dashboard.settings import parse_secret_file

        self.assertEqual(parse_secret_file("# note\nROUTEMOCK_SECRET_KEY=abc=def\n"), "abc=def")
        self.assertEqual(parse_secret_file("older-bare-value\n"), "older-bare-value")
        for text in ("", "# only a comment\n", "OTHER=value\n", "ROUTEMOCK_SECRET_KEY=replace-with-a-long-random-value\n"):
            with self.assertRaises(ImproperlyConfigured):
                parse_secret_file(text)

    def test_the_example_file_holds_only_the_refused_placeholder(self):
        from pathlib import Path

        from django.conf import settings
        from django.core.exceptions import ImproperlyConfigured

        from routemock_dashboard.settings import parse_secret_file

        with self.assertRaises(ImproperlyConfigured):
            parse_secret_file((Path(settings.BASE_DIR) / ".env.example").read_text())

    def test_the_development_server_refuses_anything_but_loopback(self):
        with mock.patch("django.core.management.commands.runserver.Command.run") as started:
            for address in ("0.0.0.0:8000", "192.0.2.10:8000", "[::]:8000"):
                with self.assertRaises(CommandError):
                    call_command("runserver", address, use_reloader=False, skip_checks=True)
            self.assertFalse(started.called)
            call_command("runserver", "127.0.0.1:8000", use_reloader=False, skip_checks=True)
            self.assertTrue(started.called)

    def test_files_created_by_manage_py_are_private(self):
        import os
        import subprocess
        import sys
        from pathlib import Path

        from django.conf import settings

        with tempfile.TemporaryDirectory() as folder:
            database = Path(folder) / "private.sqlite3"
            subprocess.run(
                [sys.executable, "manage.py", "migrate", "-v0"],
                cwd=settings.BASE_DIR,
                env={**os.environ, "ROUTEMOCK_DB": str(database)},
                check=True,
            )
            self.assertEqual(database.stat().st_mode & 0o077, 0)
