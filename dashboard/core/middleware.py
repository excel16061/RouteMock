from django.contrib.auth import logout

from .models import Profile


class SessionEpochMiddleware:
    """Ends any session from before the user last chose to sign out everywhere."""

    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        if request.user.is_authenticated:
            profile, _ = Profile.objects.get_or_create(user=request.user)
            if request.session.get("epoch", profile.session_epoch) != profile.session_epoch:
                logout(request)
            else:
                request.session["epoch"] = profile.session_epoch
        return self.get_response(request)
