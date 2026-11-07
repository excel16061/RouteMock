import getpass

from django.contrib.auth import get_user_model
from django.contrib.auth.password_validation import validate_password
from django.core.exceptions import ValidationError
from django.core.management.base import BaseCommand, CommandError

from core.models import Profile
from core.security import audit


class Command(BaseCommand):
    help = "Creates an account interactively. The password is never echoed, logged, or stored in clear."

    def add_arguments(self, parser):
        parser.add_argument("--role", choices=Profile.Role.values, default=Profile.Role.ADMIN)

    def handle(self, *args, **options):
        users = get_user_model()
        username = input("Username: ").strip()
        if not username:
            raise CommandError("A username is required.")
        if users.objects.filter(username=username).exists():
            raise CommandError("That username is already in use.")
        password = getpass.getpass("Password (15 or more characters, hidden): ")
        if password != getpass.getpass("Repeat password: "):
            raise CommandError("The passwords did not match.")
        candidate = users(username=username)
        try:
            validate_password(password, candidate)
        except ValidationError as problem:
            raise CommandError(" ".join(problem.messages)) from problem
        user = users.objects.create_user(username=username, password=password)
        Profile.objects.update_or_create(user=user, defaults={"role": options["role"]})
        audit(user, "account.bootstrapped")
        self.stdout.write("Account created. Enroll an authenticator at first sign-in.")
