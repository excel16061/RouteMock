"""Access decorators. Views use these rather than checking anything by hand."""
import time
from functools import wraps

from django.core.exceptions import PermissionDenied
from django.shortcuts import redirect
from django.urls import reverse

from .models import AuditEvent, Profile

REAUTH_WINDOW_SECONDS = 300


def audit(user, action, target=""):
    AuditEvent.objects.create(owner=user if getattr(user, "pk", None) else None, action=action, target=str(target)[:80])


def mfa_required(view):
    """Signed in with a password and a second factor, or redirected to finish."""

    @wraps(view)
    def wrapper(request, *args, **kwargs):
        if not request.user.is_authenticated:
            return redirect("login")
        if not request.user.is_verified():
            return redirect("mfa")
        return view(request, *args, **kwargs)

    return wrapper


def admin_required(view):
    @wraps(view)
    @mfa_required
    def wrapper(request, *args, **kwargs):
        if Profile.objects.get(user=request.user).role != Profile.Role.ADMIN:
            raise PermissionDenied
        return view(request, *args, **kwargs)

    return wrapper


def reauth_required(view):
    """Sensitive changes need the password and code again within the last few minutes."""

    @wraps(view)
    @admin_required
    def wrapper(request, *args, **kwargs):
        if time.time() - request.session.get("reauth_at", 0) > REAUTH_WINDOW_SECONDS:
            return redirect(f"{reverse('reauth')}?next={request.path}")
        return view(request, *args, **kwargs)

    return wrapper
