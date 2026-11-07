from django.urls import path

from core import auth_views, device_api, views

urlpatterns = [
    path("", views.home, name="home"),
    path("health", auth_views.health),
    path("login", auth_views.login_view, name="login"),
    path("logout", auth_views.logout_view, name="logout"),
    path("mfa", auth_views.mfa_view, name="mfa"),
    path("reauth", auth_views.reauth_view, name="reauth"),
    path("account", auth_views.account_view, name="account"),
    path("pairing", views.pairing, name="pairing"),
    path("devices/<uuid:device_id>", views.device_action, name="device_action"),
    path("drafts/save", views.draft_save, name="draft_save"),
    path("drafts/<uuid:draft_id>", views.draft_detail, name="draft"),
    path("drafts/<uuid:draft_id>/start", views.request_start, name="request_start"),
    path("runs/<uuid:run_id>", views.run_detail, name="run"),
    path("runs/<uuid:run_id>/finalize", views.run_finalize, name="run_finalize"),
    path("runs/<uuid:run_id>/correct", views.run_correct, name="run_correct"),
    path("runs/<uuid:run_id>/evidence", views.evidence_upload, name="evidence_upload"),
    path("runs/<uuid:run_id>/export/<str:fmt>", views.run_export, name="run_export"),
    path("evidence/<uuid:item_id>", views.evidence_download, name="evidence_download"),
    path("research", views.research_index, name="research"),
    path("consent", views.consent_records, name="consent"),
    path("audit", views.audit_log, name="audit"),
    path("api/device/pair", device_api.pair),
    path("api/device/commands", device_api.commands),
    path("api/device/commands/<uuid:command_id>/status", device_api.command_status),
    path("api/device/consent", device_api.upload_consent),
    path("api/device/runs", device_api.upload_run),
    path("api/device/withdraw", device_api.withdraw),
]
