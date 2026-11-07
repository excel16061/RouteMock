"""Evidence storage and exports. Raw files stay separate from derived reports."""
import csv
import hashlib
import io
import json
import re
import zipfile
from pathlib import Path

from django.conf import settings
from django.template.loader import render_to_string

from .models import EvidenceFile, TestRun

ALLOWED = {
    ".png": ("image/png", b"\x89PNG\r\n\x1a\n"),
    ".pdf": ("application/pdf", b"%PDF-"),
    ".json": ("application/json", None),
    ".csv": ("text/csv", None),
    ".txt": ("text/plain", None),
}
SAFE_NAME = re.compile(r"[^A-Za-z0-9._-]")


class RejectedUpload(Exception):
    pass


def check_upload(name: str, data: bytes) -> tuple[str, str]:
    """Returns a safe name and type, judged by content as well as by extension."""
    if len(data) > settings.EVIDENCE_MAX_BYTES:
        raise RejectedUpload("File is too large.")
    if not data:
        raise RejectedUpload("File is empty.")
    safe = SAFE_NAME.sub("_", Path(name).name)[:120]
    suffix = Path(safe).suffix.lower()
    if suffix not in ALLOWED:
        raise RejectedUpload("File type is not allowed.")
    content_type, magic = ALLOWED[suffix]
    if magic is not None and not data.startswith(magic):
        raise RejectedUpload("File content does not match its type.")
    if magic is None:
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError as error:
            raise RejectedUpload("File content does not match its type.") from error
        if suffix == ".json":
            try:
                json.loads(text)
            except ValueError as error:
                raise RejectedUpload("File content does not match its type.") from error
    return safe, content_type


def store(run: TestRun, name: str, data: bytes, kind: str) -> EvidenceFile:
    safe, content_type = check_upload(name, data)
    digest = hashlib.sha256(data).hexdigest()
    record = EvidenceFile(
        run=run, name=safe, kind=kind, content_type=content_type, size=len(data), sha256=digest, stored_as=""
    )
    record.stored_as = f"{record.id}.bin"
    folder = Path(settings.EVIDENCE_ROOT)
    folder.mkdir(parents=True, exist_ok=True, mode=0o700)
    (folder / record.stored_as).write_bytes(data)
    record.save()
    return record


def read(record: EvidenceFile) -> bytes:
    return (Path(settings.EVIDENCE_ROOT) / record.stored_as).read_bytes()


def run_as_dict(run: TestRun) -> dict:
    return {
        "schema": "routemock-run/1",
        "run_id": str(run.run_id),
        "state": run.state,
        "corrects": str(run.corrects_id) if run.corrects_id else None,
        "title": run.title,
        "protocol_version": run.protocol_version,
        "versions": {"app": run.app_version, "backend": run.backend_version},
        "source_revision": run.source_revision or None,
        "working_tree_dirty": run.working_tree_dirty,
        "route_fixture": run.route_fixture,
        "selected_speed_kmh": run.selected_speed_kmh,
        "configuration": run.configuration,
        "device": {"model": run.device_model, "android_version": run.android_version},
        "started_at_utc": run.started_at_utc.isoformat() if run.started_at_utc else None,
        "finished_at_utc": run.finished_at_utc.isoformat() if run.finished_at_utc else None,
        "monotonic_duration_s": run.monotonic_duration_s,
        "limitations": run.limitations,
        "deviations": run.deviations,
        "counts": run.counts(),
        "checks": [
            {
                "check_id": check.check_id,
                "title": check.title,
                "expected": check.expected,
                "tolerance": check.tolerance,
                "observed": check.observed,
                "result": check.result,
                "source": check.source,
                "basis": check.basis,
            }
            for check in run.checks.order_by("id")
        ],
        "evidence": [
            {"name": item.name, "kind": item.kind, "sha256": item.sha256, "size": item.size}
            for item in run.evidence.order_by("name")
        ],
    }


def checks_csv(run: TestRun) -> str:
    buffer = io.StringIO()
    writer = csv.writer(buffer)
    writer.writerow(["check_id", "title", "expected", "tolerance", "observed", "result", "source", "basis"])
    for check in run.checks.order_by("id"):
        # Prefixing stops a spreadsheet from running a value as a formula.
        writer.writerow(
            [
                ("'" + value if value[:1] in ("=", "+", "-", "@") else value)
                for value in (
                    check.check_id,
                    check.title,
                    check.expected,
                    check.tolerance,
                    check.observed,
                    check.result,
                    check.source,
                    check.basis,
                )
            ]
        )
    return buffer.getvalue()


def report_html(run: TestRun) -> str:
    return render_to_string("core/report.html", {"run": run, "data": run_as_dict(run)})


def export_zip(run: TestRun) -> bytes:
    """A bundle with a SHA-256 manifest. Hashes show change, not that a result is true."""
    files = {
        "derived/run.json": json.dumps(run_as_dict(run), indent=2).encode(),
        "derived/checks.csv": checks_csv(run).encode(),
        "derived/report.html": report_html(run).encode(),
    }
    for item in run.evidence.all():
        files[f"{item.kind}/{item.name}"] = read(item)
    manifest = "".join(f"{hashlib.sha256(data).hexdigest()}  {path}\n" for path, data in sorted(files.items()))
    note = (
        "# SHA-256 manifest. Hashes detect changes relative to a manifest you already trust.\n"
        "# They do not prove that any observation is true.\n"
    )
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        for path, data in files.items():
            archive.writestr(path, data)
        archive.writestr("MANIFEST.sha256", note + manifest)
    return buffer.getvalue()
