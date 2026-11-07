from django.contrib.auth import get_user_model
from django.core.management.base import BaseCommand, CommandError
from django_otp.plugins.otp_static.models import StaticDevice
from django_otp.plugins.otp_totp.models import TOTPDevice

from core.models import Profile
from core.security import audit


class Command(BaseCommand):
    help = "Local recovery if an authenticator and all recovery codes are lost. Needs server access."

    def add_arguments(self, parser):
        parser.add_argument("username")

    def handle(self, *args, **options):
        user = get_user_model().objects.filter(username=options["username"]).first()
        if user is None:
            raise CommandError("No such account.")
        TOTPDevice.objects.filter(user=user).delete()
        StaticDevice.objects.filter(user=user).delete()
        profile = Profile.objects.get(user=user)
        profile.session_epoch += 1
        profile.save()
        audit(user, "mfa.reset_locally")
        self.stdout.write("Second factor removed and sessions ended. Enroll again at next sign-in.")
