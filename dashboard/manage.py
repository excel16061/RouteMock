#!/usr/bin/env python
import os
import sys

if __name__ == "__main__":
    # The database holds credentials, so nothing created from here is readable by others.
    os.umask(0o077)
    os.environ.setdefault("DJANGO_SETTINGS_MODULE", "routemock_dashboard.settings")
    from django.core.management import execute_from_command_line

    execute_from_command_line(sys.argv)
