"""Django's development server, refused anywhere but loopback."""
from django.core.management.base import CommandError
from django.core.management.commands.runserver import Command as DjangoRunserver

LOOPBACK = {"127.0.0.1", "::1", "localhost"}


class Command(DjangoRunserver):
    help = "Runs the development server on loopback only. It is not safe to expose."

    def run(self, **options):
        if self.addr not in LOOPBACK:
            raise CommandError("The development server only runs on loopback, for example 127.0.0.1:8000.")
        super().run(**options)
