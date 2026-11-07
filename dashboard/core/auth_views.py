import base64
import io
import time

import qrcode
import qrcode.image.svg
from django.contrib.auth import authenticate, login, logout, update_session_auth_hash
from django.contrib.auth.password_validation import validate_password
from django.core.exceptions import ValidationError
from django.http import HttpResponse
from django.shortcuts import redirect, render
from django.utils.http import url_has_allowed_host_and_scheme
from django.views.decorators.http import require_http_methods, require_POST
from django_otp import login as otp_login
from django_otp import match_token
from django_otp.plugins.otp_static.models import StaticDevice, StaticToken
from django_otp.plugins.otp_totp.models import TOTPDevice

from .models import Profile
from .security import audit, mfa_required, reauth_required

GENERIC_FAILURE = "Those details were not accepted."


def lockout_response(request, *args, **kwargs):
    """Identical to a wrong password, so a lockout reveals nothing about the account."""
    return render(request, "core/login.html", {"error": GENERIC_FAILURE}, status=200)


@require_http_methods(["GET", "POST"])
def login_view(request):
    if request.method == "POST":
        user = authenticate(
            request,
            username=request.POST.get("username", ""),
            password=request.POST.get("password", ""),
        )
        if user is None:
            audit(None, "login.failed")
            return render(request, "core/login.html", {"error": GENERIC_FAILURE})
        login(request, user)  # Also rotates the session key.
        audit(user, "login.password_ok")
        return redirect("mfa")
    return render(request, "core/login.html")


@require_POST
def logout_view(request):
    if request.user.is_authenticated:
        audit(request.user, "logout")
    logout(request)  # Deletes the session on the server, not only the cookie.
    return redirect("login")


def _qr_data_uri(url: str) -> str:
    buffer = io.BytesIO()
    qrcode.make(url, image_factory=qrcode.image.svg.SvgImage).save(buffer)
    return "data:image/svg+xml;base64," + base64.b64encode(buffer.getvalue()).decode()


def _new_recovery_codes(user) -> list[str]:
    device, _ = StaticDevice.objects.get_or_create(user=user, name="recovery")
    device.token_set.all().delete()
    codes = [StaticToken.random_token() for _ in range(8)]
    for code in codes:
        device.token_set.create(token=code)
    return codes


@require_http_methods(["GET", "POST"])
def mfa_view(request):
    """A second factor is mandatory: a first login enrolls, every later one verifies."""
    if not request.user.is_authenticated:
        return redirect("login")
    if request.user.is_verified():
        return redirect("home")

    confirmed = TOTPDevice.objects.filter(user=request.user, confirmed=True).exists()
    if confirmed:
        if request.method == "POST":
            device = match_token(request.user, request.POST.get("code", "").strip())
            if device is not None:
                otp_login(request, device)
                request.session["reauth_at"] = time.time()
                audit(request.user, "login.mfa_ok")
                return redirect("home")
            audit(request.user, "login.mfa_failed")
            return render(request, "core/mfa.html", {"error": GENERIC_FAILURE})
        return render(request, "core/mfa.html")

    device = TOTPDevice.objects.filter(user=request.user, confirmed=False).first()
    if device is None:
        device = TOTPDevice.objects.create(user=request.user, name="authenticator", confirmed=False)
    if request.method == "POST" and device.verify_token(request.POST.get("code", "").strip()):
        device.confirmed = True
        device.save()
        otp_login(request, device)
        audit(request.user, "mfa.enrolled")
        # Shown once here. django-otp keeps them unencrypted, so protect the database file.
        response = render(request, "core/recovery_codes.html", {"codes": _new_recovery_codes(request.user)})
        response["Cache-Control"] = "no-store"
        return response
    response = render(
        request,
        "core/mfa_setup.html",
        {
            "qr": _qr_data_uri(device.config_url),
            "secret": base64.b32encode(device.bin_key).decode(),
            "error": GENERIC_FAILURE if request.method == "POST" else None,
        },
    )
    response["Cache-Control"] = "no-store"
    return response


@mfa_required
@require_http_methods(["GET", "POST"])
def reauth_view(request):
    target = request.GET.get("next", "home")
    if not url_has_allowed_host_and_scheme(target, allowed_hosts={request.get_host()}):
        target = "home"
    if request.method == "POST":
        user = authenticate(request, username=request.user.get_username(), password=request.POST.get("password", ""))
        if user is not None and match_token(request.user, request.POST.get("code", "").strip()) is not None:
            request.session["reauth_at"] = time.time()
            audit(request.user, "reauth.ok")
            return redirect(target)
        audit(request.user, "reauth.failed")
        return render(request, "core/reauth.html", {"error": GENERIC_FAILURE})
    return render(request, "core/reauth.html")


@reauth_required
@require_http_methods(["GET", "POST"])
def account_view(request):
    error = None
    if request.method == "POST":
        action = request.POST.get("action")
        if action == "password":
            password = request.POST.get("new_password", "")
            try:
                validate_password(password, request.user)
            except ValidationError as problem:
                error = " ".join(problem.messages)
            else:
                request.user.set_password(password)
                request.user.save()
                update_session_auth_hash(request, request.user)
                audit(request.user, "password.changed")
        elif action == "sign_out_everywhere":
            profile = Profile.objects.get(user=request.user)
            profile.session_epoch += 1
            profile.save()
            audit(request.user, "sessions.revoked")
            return redirect("login")
        elif action == "recovery_codes":
            audit(request.user, "recovery_codes.regenerated")
            response = render(request, "core/recovery_codes.html", {"codes": _new_recovery_codes(request.user)})
            response["Cache-Control"] = "no-store"
            return response
    return render(request, "core/account.html", {"error": error})


def health(request):
    return HttpResponse("ok", content_type="text/plain")
