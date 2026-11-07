"""Every lookup is filtered by owner, so a guessed ID behaves like a missing record."""
import json
from datetime import timedelta

from django.conf import settings
from django.http import Http404, HttpResponse, JsonResponse
from django.shortcuts import get_object_or_404, redirect, render
from django.utils import timezone
from django.views.decorators.http import require_http_methods, require_POST

from . import evidence as ev
from .device_api import new_command
from .models import (
    AuditEvent,
    Command,
    ConsentRecord,
    Device,
    EvidenceFile,
    PairingChallenge,
    RouteDraft,
    RouteDraftVersion,
    SimulationProfile,
    TestRun,
    hash_secret,
)
from .security import admin_required, audit, mfa_required, reauth_required


@mfa_required
def home(request):
    runs = TestRun.objects.filter(owner=request.user).order_by("-created_at")
    return render(
        request,
        "core/home.html",
        {
            "devices": Device.objects.filter(owner=request.user).order_by("label"),
            "drafts": RouteDraft.objects.filter(owner=request.user).order_by("name"),
            "profiles": SimulationProfile.objects.filter(owner=request.user).order_by("name"),
            "runs": [(run, run.counts()) for run in runs[:50]],
        },
    )


@reauth_required
@require_http_methods(["GET", "POST"])
def pairing(request):
    code = None
    if request.method == "POST":
        label = request.POST.get("label", "").strip()[:60] or "Test device"
        code = PairingChallenge.new_code()
        PairingChallenge.objects.create(
            owner=request.user,
            code_hash=hash_secret(code),
            label=label,
            expires_at=timezone.now() + timedelta(seconds=PairingChallenge.TTL_SECONDS),
        )
        audit(request.user, "pairing.created")
    response = render(request, "core/pairing.html", {"code": code})
    response["Cache-Control"] = "no-store"
    return response


@reauth_required
@require_POST
def device_action(request, device_id):
    device = get_object_or_404(Device, pk=device_id, owner=request.user)
    action = request.POST.get("action")
    if action == "revoke":
        device.revoked_at = timezone.now()
        device.remote_control_enabled = False
        device.save()
        audit(request.user, "device.revoked", device.id)
    elif action == "remote_on" and device.active and device.consent_active and device.simulation_consent_active:
        device.remote_control_enabled = True
        device.save()
        audit(request.user, "device.remote_enabled", device.id)
    elif action == "remote_off":
        device.remote_control_enabled = False
        device.save()
        audit(request.user, "device.remote_disabled", device.id)
    return redirect("home")


@mfa_required
def draft_detail(request, draft_id):
    draft = get_object_or_404(RouteDraft, pk=draft_id, owner=request.user)
    return render(
        request,
        "core/draft.html",
        {
            "draft": draft,
            "versions": draft.versions.order_by("-number"),
            "devices": Device.objects.filter(
                owner=request.user,
                revoked_at__isnull=True,
                remote_control_enabled=True,
                consent_active=True,
                simulation_consent_active=True,
            ),
            "commands": Command.objects.filter(owner=request.user, draft_version__draft=draft).order_by("-created_at")[:20],
        },
    )


DRAFT_FIELDS = {"mode", "start", "destination", "speed_kmh"}


def _valid_payload(text: str):
    try:
        payload = json.loads(text)
    except ValueError:
        return None
    if not isinstance(payload, dict) or not set(payload).issubset(DRAFT_FIELDS):
        return None
    if payload.get("mode") not in ("STATIONARY", "ROAD", "STRAIGHT_LINE"):
        return None
    for key in ("start", "destination"):
        point = payload.get(key)
        if point is None and key == "destination":
            continue
        if not isinstance(point, dict):
            return None
        latitude, longitude = point.get("latitude"), point.get("longitude")
        if not isinstance(latitude, (int, float)) or not isinstance(longitude, (int, float)):
            return None
        if not (-90 <= latitude <= 90 and -180 <= longitude <= 180):
            return None
    speed = payload.get("speed_kmh", 50)
    if not isinstance(speed, (int, float)) or not 0 < speed <= 1200:
        return None
    return payload


@admin_required
@require_POST
def draft_save(request):
    """Adds a version. A running simulation on a phone is never touched by this."""
    payload = _valid_payload(request.POST.get("payload", ""))
    name = request.POST.get("name", "").strip()[:60]
    if payload is None or not name:
        return HttpResponse("Invalid draft.", status=400, content_type="text/plain")
    draft_id = request.POST.get("draft_id")
    if draft_id:
        draft = get_object_or_404(RouteDraft, pk=draft_id, owner=request.user)
    else:
        draft = RouteDraft.objects.create(owner=request.user, name=name)
    number = (draft.latest.number if draft.latest else 0) + 1
    RouteDraftVersion.objects.create(draft=draft, number=number, payload=payload)
    audit(request.user, "draft.versioned", draft.id)
    return redirect("draft", draft_id=draft.id)


@reauth_required
@require_POST
def request_start(request, draft_id):
    """Only asks. The phone shows the request, and its user must approve each one."""
    draft = get_object_or_404(RouteDraft, pk=draft_id, owner=request.user)
    device = get_object_or_404(
        Device,
        pk=request.POST.get("device_id"),
        owner=request.user,
        revoked_at__isnull=True,
        remote_control_enabled=True,
        consent_active=True,
        simulation_consent_active=True,
    )
    command = new_command(request.user, device, draft.latest)
    audit(request.user, "command.requested", command.id)
    return redirect("draft", draft_id=draft.id)


@mfa_required
def run_detail(request, run_id):
    run = get_object_or_404(TestRun, pk=run_id, owner=request.user)
    return render(
        request,
        "core/run.html",
        {"run": run, "counts": run.counts(), "checks": run.checks.order_by("id"), "items": run.evidence.order_by("kind", "name")},
    )


@admin_required
@require_POST
def run_finalize(request, run_id):
    run = get_object_or_404(TestRun, pk=run_id, owner=request.user)
    if run.state == TestRun.State.DRAFT:
        run.state = TestRun.State.FINAL
        run.save()
        audit(request.user, "run.finalized", run.run_id)
    return redirect("run", run_id=run.run_id)


@admin_required
@require_POST
def run_correct(request, run_id):
    """Corrections are new draft runs linked to the original, which stays as it was."""
    original = get_object_or_404(TestRun, pk=run_id, owner=request.user, state=TestRun.State.FINAL)
    correction = TestRun.objects.create(
        owner=request.user,
        corrects=original,
        title=f"Correction of {original.title}"[:120],
        protocol_version=original.protocol_version,
    )
    audit(request.user, "run.correction_created", correction.run_id)
    return redirect("run", run_id=correction.run_id)


@admin_required
@require_POST
def evidence_upload(request, run_id):
    run = get_object_or_404(TestRun, pk=run_id, owner=request.user, state=TestRun.State.DRAFT)
    upload = request.FILES.get("file")
    if upload is None or upload.size > settings.EVIDENCE_MAX_BYTES:
        return HttpResponse("File rejected.", status=400, content_type="text/plain")
    kind = request.POST.get("kind")
    if kind not in EvidenceFile.Kind.values:
        return HttpResponse("File rejected.", status=400, content_type="text/plain")
    try:
        item = ev.store(run, upload.name, upload.read(), kind)
    except ev.RejectedUpload as problem:
        return HttpResponse(str(problem), status=400, content_type="text/plain")
    audit(request.user, "evidence.uploaded", item.id)
    return redirect("run", run_id=run.run_id)


@mfa_required
def evidence_download(request, item_id):
    item = get_object_or_404(EvidenceFile, pk=item_id, run__owner=request.user)
    response = HttpResponse(ev.read(item), content_type=item.content_type)
    # Always a download: uploaded content is never displayed in the site's origin.
    response["Content-Disposition"] = f'attachment; filename="{item.name}"'
    response["X-Content-Type-Options"] = "nosniff"
    audit(request.user, "evidence.downloaded", item.id)
    return response


@mfa_required
def run_export(request, run_id, fmt):
    run = get_object_or_404(TestRun, pk=run_id, owner=request.user)
    audit(request.user, f"run.exported.{fmt}", run.run_id)
    if fmt == "json":
        return JsonResponse(ev.run_as_dict(run), json_dumps_params={"indent": 2})
    if fmt == "csv":
        response = HttpResponse(ev.checks_csv(run), content_type="text/csv")
        response["Content-Disposition"] = f'attachment; filename="run-{run.run_id}.csv"'
        return response
    if fmt == "html":
        return HttpResponse(ev.report_html(run))
    if fmt == "zip":
        response = HttpResponse(ev.export_zip(run), content_type="application/zip")
        response["Content-Disposition"] = f'attachment; filename="run-{run.run_id}.zip"'
        return response
    raise Http404


@mfa_required
def research_index(request):
    runs = TestRun.objects.filter(owner=request.user).order_by("protocol_version", "-created_at")
    return render(request, "core/research.html", {"rows": [(run, run.counts()) for run in runs]})


@mfa_required
def consent_records(request):
    return render(
        request,
        "core/consent.html",
        {"records": ConsentRecord.objects.filter(owner=request.user).select_related("device").order_by("-received_at")},
    )


@admin_required
def audit_log(request):
    return render(
        request,
        "core/audit.html",
        {"events": AuditEvent.objects.filter(owner=request.user).order_by("-created_at")[:200]},
    )


