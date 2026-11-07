"""The phone's API. Phones always call out to here; nothing ever connects to a phone."""
import hmac
import json
import secrets
from datetime import timedelta
from functools import wraps

from django.core.cache import cache
from django.db import transaction
from django.http import JsonResponse
from django.utils import timezone
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_GET, require_POST

from .models import Command, ConsentRecord, Device, PairingChallenge, TestRun, hash_secret
from .security import audit

MAX_BODY_BYTES = 64 * 1024
PAIRING_ATTEMPTS_PER_ADDRESS = 10

RUN_FIELDS = {"title", "protocol_version", "app_version", "selected_speed_kmh", "monotonic_duration_s"}


UPLOAD_SCOPE = "DASHBOARD_UPLOAD"
SIMULATION_SCOPES = ("TERMS", "PRIVACY", "MOCK_LOCATION")


def receipt_grants(receipt, scope: str) -> bool:
    """True if the latest event naming this scope in a receipt is not a withdrawal."""
    if not isinstance(receipt, dict) or not isinstance(receipt.get("events"), list):
        return False
    for event in reversed(receipt["events"]):
        if not isinstance(event, dict) or not isinstance(event.get("documents"), list):
            return False
        if any(isinstance(item, dict) and item.get("scope") == scope for item in event["documents"]):
            return event.get("type") in ("ACCEPTED", "REPLACED")
    return False


def simulation_allowed(receipt) -> bool:
    return all(receipt_grants(receipt, scope) for scope in SIMULATION_SCOPES)


def expire_unfinished_commands(device):
    Command.objects.filter(
        device=device, status__in=[Command.Status.PENDING, Command.Status.DELIVERED]
    ).update(status=Command.Status.EXPIRED)


def end_device_access(device, revoke: bool):
    """Stops remote control and unfinished commands, and optionally the credential."""
    device.consent_active = False
    device.simulation_consent_active = False
    device.remote_control_enabled = False
    if revoke:
        device.revoked_at = timezone.now()
    device.save()
    expire_unfinished_commands(device)


def error(status: int, code: str) -> JsonResponse:
    return JsonResponse({"error": code}, status=status)


def parse_json(request):
    if len(request.body) > MAX_BODY_BYTES:
        return None
    try:
        body = json.loads(request.body)
    except ValueError:
        return None
    return body if isinstance(body, dict) else None


def device_auth(scope=None):
    def decorator(view):
        @wraps(view)
        def wrapper(request, *args, **kwargs):
            header = request.headers.get("Authorization", "")
            if not header.startswith("Bearer "):
                return error(401, "unauthorized")
            supplied = hash_secret(header[7:])
            device = Device.objects.filter(token_hash=supplied, revoked_at__isnull=True).first()
            # Same answer for an unknown token as for a revoked one.
            if device is None or not hmac.compare_digest(device.token_hash, supplied):
                return error(401, "unauthorized")
            if scope is not None and scope not in device.scopes:
                return error(403, "forbidden")
            if scope is not None and not device.consent_active:
                return error(403, "consent_required")
            Device.objects.filter(pk=device.pk).update(last_seen_at=timezone.now())
            request.device = device
            return view(request, *args, **kwargs)

        return wrapper

    return decorator


@csrf_exempt
@require_POST
def pair(request):
    """Exchanges a single-use code, shown to a signed-in administrator, for a credential."""
    address = request.META.get("REMOTE_ADDR", "")
    key = f"pair-attempts:{address}"
    if cache.get(key, 0) >= PAIRING_ATTEMPTS_PER_ADDRESS:
        return error(429, "rate_limited")
    cache.set(key, cache.get(key, 0) + 1, 600)

    body = parse_json(request)
    if body is None or not isinstance(body.get("code"), str):
        return error(400, "bad_request")
    # Checked first, so a phone without consent does not use up the code.
    if not receipt_grants(body.get("consent_receipt"), UPLOAD_SCOPE):
        return error(403, "consent_required")
    with transaction.atomic():
        challenge = PairingChallenge.objects.select_for_update().filter(code_hash=hash_secret(body["code"])).first()
        if challenge is None or not challenge.usable:
            return error(400, "invalid_code")
        challenge.used_at = timezone.now()
        challenge.save()
        token = secrets.token_urlsafe(32)
        device = Device.objects.create(
            owner=challenge.owner,
            label=challenge.label,
            token_hash=hash_secret(token),
            scopes=list(Device.SCOPES),
            consent_active=True,
            simulation_consent_active=simulation_allowed(body["consent_receipt"]),
            model_name=str(body.get("model", ""))[:60],
            android_version=str(body.get("android_version", ""))[:20],
        )
        ConsentRecord.objects.create(owner=challenge.owner, device=device, receipt=body["consent_receipt"])
    audit(challenge.owner, "device.paired", device.id)
    return JsonResponse({"device_id": str(device.id), "token": token, "label": device.label})


def _deliverable(device):
    now = timezone.now()
    Command.objects.filter(
        device=device, status__in=[Command.Status.PENDING, Command.Status.DELIVERED], expires_at__lte=now
    ).update(status=Command.Status.EXPIRED)
    return Command.objects.filter(device=device, status=Command.Status.PENDING, expires_at__gt=now)


@require_GET
@device_auth("commands")
def commands(request):
    device = request.device
    if not device.remote_control_enabled or not device.simulation_consent_active:
        return JsonResponse({"remote_control": False, "commands": []})
    items = []
    for command in _deliverable(device):
        command.status = Command.Status.DELIVERED
        command.save()
        items.append(
            {
                "id": str(command.id),
                "kind": command.kind,
                "nonce": command.nonce,
                "expires_at": command.expires_at.isoformat(),
                "draft": command.draft_version.payload,
                "draft_version": command.draft_version.number,
            }
        )
    return JsonResponse({"remote_control": True, "commands": items})


@csrf_exempt
@require_POST
@device_auth("commands")
def command_status(request, command_id):
    body = parse_json(request)
    if body is None:
        return error(400, "bad_request")
    with transaction.atomic():
        # Scoped to this device: another device's command looks as if it does not exist.
        command = Command.objects.select_for_update().filter(pk=command_id, device=request.device).first()
        if command is None:
            return error(404, "not_found")
        if not hmac.compare_digest(command.nonce, str(body.get("nonce", ""))):
            return error(403, "bad_nonce")
        if command.expired and command.status in (Command.Status.PENDING, Command.Status.DELIVERED):
            command.status = Command.Status.EXPIRED
            command.save()
            return error(409, "expired")
        requested = body.get("status")
        if requested not in Command.TRANSITIONS.get(command.status, set()):
            return error(409, "invalid_transition")
        command.status = requested
        command.error = str(body.get("error", ""))[:200]
        command.save()
    audit(request.device.owner, f"command.{requested}", command.id)
    return JsonResponse({"status": command.status})


@csrf_exempt
@require_POST
@device_auth("consent_upload")
def upload_consent(request):
    body = parse_json(request)
    if body is None or not isinstance(body.get("events"), list):
        return error(400, "bad_request")
    ConsentRecord.objects.create(owner=request.device.owner, device=request.device, receipt=body)
    audit(request.device.owner, "consent.uploaded", request.device.id)
    device = request.device
    granted = receipt_grants(body, UPLOAD_SCOPE)
    simulation = simulation_allowed(body)
    if not granted:
        end_device_access(device, revoke=False)
        audit(device.owner, "consent.withdrawn", device.id)
    elif not simulation:
        # Uploads may continue, but nothing may ask this phone to simulate.
        device.simulation_consent_active = False
        device.remote_control_enabled = False
        device.save()
        expire_unfinished_commands(device)
        audit(device.owner, "consent.simulation_withdrawn", device.id)
    else:
        device.simulation_consent_active = True
        device.save()
    return JsonResponse({"stored": True, "consent_active": granted, "simulation_consent_active": granted and simulation})


@csrf_exempt
@require_POST
@device_auth()
def withdraw(request):
    """The phone ends its own access. Needs no consent, or it could never be used."""
    end_device_access(request.device, revoke=True)
    audit(request.device.owner, "device.withdrawn", request.device.id)
    return JsonResponse({"revoked": True})


@csrf_exempt
@require_POST
@device_auth("run_upload")
def upload_run(request):
    """A fixed list of fields, so location data cannot arrive by accident."""
    body = parse_json(request)
    if body is None or not set(body).issubset(RUN_FIELDS) or not isinstance(body.get("title"), str):
        return error(400, "bad_request")
    run = TestRun.objects.create(
        owner=request.device.owner,
        device=request.device,
        title=body["title"][:120],
        protocol_version=str(body.get("protocol_version", "unspecified"))[:20],
        app_version=str(body.get("app_version", ""))[:20],
        selected_speed_kmh=body.get("selected_speed_kmh") if isinstance(body.get("selected_speed_kmh"), (int, float)) else None,
        monotonic_duration_s=body.get("monotonic_duration_s")
        if isinstance(body.get("monotonic_duration_s"), (int, float))
        else None,
        device_model=request.device.model_name,
        android_version=request.device.android_version,
    )
    audit(request.device.owner, "run.uploaded", run.run_id)
    return JsonResponse({"run_id": str(run.run_id)})


def new_command(owner, device, draft_version) -> Command:
    return Command.objects.create(
        owner=owner,
        device=device,
        draft_version=draft_version,
        expires_at=timezone.now() + timedelta(seconds=Command.TTL_SECONDS),
    )
