"""Every owned record has an owner, and every query must go through it."""
import hashlib
import secrets
import uuid

from django.conf import settings
from django.core.exceptions import ValidationError
from django.db import models
from django.utils import timezone

User = settings.AUTH_USER_MODEL


def hash_secret(value: str) -> str:
    """For long random tokens only. Passwords use the framework's Argon2id hasher."""
    return hashlib.sha256(value.encode()).hexdigest()


class Profile(models.Model):
    class Role(models.TextChoices):
        ADMIN = "admin"
        VIEWER = "viewer"

    user = models.OneToOneField(User, on_delete=models.CASCADE)
    role = models.CharField(max_length=10, choices=Role.choices, default=Role.VIEWER)
    # Raising this signs out every existing session for the user.
    session_epoch = models.PositiveIntegerField(default=0)


class Device(models.Model):
    SCOPES = ("commands", "consent_upload", "run_upload")

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    owner = models.ForeignKey(User, on_delete=models.CASCADE)
    label = models.CharField(max_length=60, help_text="Pseudonym. Never a name or identifier.")
    token_hash = models.CharField(max_length=64, unique=True)
    scopes = models.JSONField(default=list)
    remote_control_enabled = models.BooleanField(default=False)
    # False stops every upload and command, whatever the credential's scopes say.
    consent_active = models.BooleanField(default=False)
    # The phone's user allows simulation. Upload consent alone is not enough for commands.
    simulation_consent_active = models.BooleanField(default=False)
    model_name = models.CharField(max_length=60, blank=True)
    android_version = models.CharField(max_length=20, blank=True)
    created_at = models.DateTimeField(default=timezone.now)
    last_seen_at = models.DateTimeField(null=True, blank=True)
    revoked_at = models.DateTimeField(null=True, blank=True)

    @property
    def active(self) -> bool:
        return self.revoked_at is None


class PairingChallenge(models.Model):
    owner = models.ForeignKey(User, on_delete=models.CASCADE)
    code_hash = models.CharField(max_length=64, unique=True)
    label = models.CharField(max_length=60)
    expires_at = models.DateTimeField()
    used_at = models.DateTimeField(null=True, blank=True)

    TTL_SECONDS = 600

    @staticmethod
    def new_code() -> str:
        return secrets.token_urlsafe(12)

    @property
    def usable(self) -> bool:
        return self.used_at is None and timezone.now() < self.expires_at


class RouteDraft(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    owner = models.ForeignKey(User, on_delete=models.CASCADE)
    name = models.CharField(max_length=60)
    created_at = models.DateTimeField(default=timezone.now)

    @property
    def latest(self):
        return self.versions.order_by("-number").first()


class RouteDraftVersion(models.Model):
    """Editing a draft adds a version. Nothing here reaches a phone unasked."""

    draft = models.ForeignKey(RouteDraft, on_delete=models.CASCADE, related_name="versions")
    number = models.PositiveIntegerField()
    payload = models.JSONField()
    created_at = models.DateTimeField(default=timezone.now)

    class Meta:
        constraints = [models.UniqueConstraint(fields=["draft", "number"], name="unique_draft_version")]

    def save(self, *args, **kwargs):
        if self.pk:
            raise ValidationError("Draft versions cannot be changed.")
        super().save(*args, **kwargs)


class SimulationProfile(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    owner = models.ForeignKey(User, on_delete=models.CASCADE)
    name = models.CharField(max_length=60)
    speed_kmh = models.FloatField()
    repeat = models.BooleanField(default=False)


class Command(models.Model):
    class Status(models.TextChoices):
        PENDING = "pending"
        DELIVERED = "delivered"
        ACCEPTED = "accepted"
        REJECTED = "rejected"
        EXECUTED = "executed"
        FAILED = "failed"
        EXPIRED = "expired"

    # Each status may only move forward, so a replayed report cannot rewrite history.
    TRANSITIONS = {
        Status.DELIVERED: {Status.ACCEPTED, Status.REJECTED, Status.FAILED},
        Status.ACCEPTED: {Status.EXECUTED, Status.FAILED},
    }
    TTL_SECONDS = 300

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    owner = models.ForeignKey(User, on_delete=models.CASCADE)
    device = models.ForeignKey(Device, on_delete=models.CASCADE)
    kind = models.CharField(max_length=30, default="start_simulation")
    draft_version = models.ForeignKey(RouteDraftVersion, on_delete=models.PROTECT)
    nonce = models.CharField(max_length=43, default=secrets.token_urlsafe)
    status = models.CharField(max_length=10, choices=Status.choices, default=Status.PENDING)
    error = models.CharField(max_length=200, blank=True)
    created_at = models.DateTimeField(default=timezone.now)
    expires_at = models.DateTimeField()
    updated_at = models.DateTimeField(auto_now=True)

    @property
    def expired(self) -> bool:
        return timezone.now() >= self.expires_at


class TestRun(models.Model):
    class State(models.TextChoices):
        DRAFT = "draft"
        FINAL = "final"

    run_id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    owner = models.ForeignKey(User, on_delete=models.CASCADE)
    device = models.ForeignKey(Device, null=True, blank=True, on_delete=models.SET_NULL)
    corrects = models.ForeignKey("self", null=True, blank=True, on_delete=models.PROTECT, related_name="corrections")
    state = models.CharField(max_length=5, choices=State.choices, default=State.DRAFT)
    title = models.CharField(max_length=120)
    protocol_version = models.CharField(max_length=20)
    app_version = models.CharField(max_length=20, blank=True)
    backend_version = models.CharField(max_length=20, blank=True)
    source_revision = models.CharField(max_length=64, blank=True)
    working_tree_dirty = models.BooleanField(null=True, blank=True)
    route_fixture = models.CharField(max_length=120, blank=True)
    selected_speed_kmh = models.FloatField(null=True, blank=True)
    configuration = models.JSONField(default=dict, blank=True)
    device_model = models.CharField(max_length=60, blank=True)
    android_version = models.CharField(max_length=20, blank=True)
    started_at_utc = models.DateTimeField(null=True, blank=True)
    finished_at_utc = models.DateTimeField(null=True, blank=True)
    monotonic_duration_s = models.FloatField(null=True, blank=True)
    limitations = models.TextField(blank=True)
    deviations = models.TextField(blank=True)
    created_at = models.DateTimeField(default=timezone.now)

    def save(self, *args, **kwargs):
        if not self._state.adding:
            stored = TestRun.objects.get(pk=self.pk)
            if stored.state == self.State.FINAL:
                raise ValidationError("A finalized run cannot change. Create a linked correction.")
        super().save(*args, **kwargs)

    def counts(self) -> dict:
        totals = {choice: 0 for choice in CheckResult.Result.values}
        for result in self.checks.values_list("result", flat=True):
            totals[result] += 1
        return totals


class CheckResult(models.Model):
    class Result(models.TextChoices):
        PASS = "PASS"
        FAIL = "FAIL"
        NOT_TESTED = "NOT TESTED"
        INCONCLUSIVE = "INCONCLUSIVE"

    class Source(models.TextChoices):
        SYNTHETIC_FIXTURE = "synthetic fixture"
        MANUAL = "manual observation"
        AUTOMATED = "automated check"

    class Basis(models.TextChoices):
        SIMULATOR = "simulator state"
        ANDROID = "independent Android observation"
        OTHER = "other"

    run = models.ForeignKey(TestRun, on_delete=models.CASCADE, related_name="checks")
    check_id = models.CharField(max_length=40)
    title = models.CharField(max_length=160)
    expected = models.TextField()
    tolerance = models.CharField(max_length=120, blank=True)
    observed = models.TextField(blank=True)
    result = models.CharField(max_length=12, choices=Result.choices)
    source = models.CharField(max_length=20, choices=Source.choices)
    basis = models.CharField(max_length=40, choices=Basis.choices, default=Basis.OTHER)

    def save(self, *args, **kwargs):
        if self.run.state == TestRun.State.FINAL:
            raise ValidationError("A finalized run cannot change.")
        super().save(*args, **kwargs)


class EvidenceFile(models.Model):
    class Kind(models.TextChoices):
        RAW = "raw"
        DERIVED = "derived"

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    run = models.ForeignKey(TestRun, on_delete=models.CASCADE, related_name="evidence")
    name = models.CharField(max_length=120)
    kind = models.CharField(max_length=8, choices=Kind.choices)
    stored_as = models.CharField(max_length=80)
    content_type = models.CharField(max_length=40)
    size = models.PositiveIntegerField()
    sha256 = models.CharField(max_length=64)
    uploaded_at = models.DateTimeField(default=timezone.now)


class ConsentRecord(models.Model):
    """A copy of a receipt, held only if the phone's owner allowed the upload."""

    owner = models.ForeignKey(User, on_delete=models.CASCADE)
    device = models.ForeignKey(Device, on_delete=models.CASCADE)
    receipt = models.JSONField()
    received_at = models.DateTimeField(default=timezone.now)


class AuditEvent(models.Model):
    """Never holds passwords, tokens, recovery codes, or coordinates."""

    owner = models.ForeignKey(User, null=True, blank=True, on_delete=models.SET_NULL)
    action = models.CharField(max_length=60)
    target = models.CharField(max_length=80, blank=True)
    created_at = models.DateTimeField(default=timezone.now)
