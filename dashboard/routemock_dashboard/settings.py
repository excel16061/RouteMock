"""Settings for the RouteMock dashboard.

Local development is the only supported mode so far. Production requires
ROUTEMOCK_DEBUG=0, a real secret, HTTPS, and the deployment review in docs/DEPLOYMENT.md.
"""
import os
import secrets
from pathlib import Path

from django.core.exceptions import ImproperlyConfigured
from django.utils.csp import CSP

BASE_DIR = Path(__file__).resolve().parent.parent
VERSION = "0.1.1"

DEBUG = os.environ.get("ROUTEMOCK_DEBUG", "1") == "1"


SECRET_NAME = "ROUTEMOCK_SECRET_KEY"
PLACEHOLDER_PREFIX = "replace-with"


def parse_secret_file(text: str) -> str:
    """Reads NAME=value, or the bare value older local files held. Placeholders are refused."""
    lines = [line.strip() for line in text.splitlines() if line.strip() and not line.lstrip().startswith("#")]
    values = [line.split("=", 1)[1].strip() for line in lines if line.startswith(f"{SECRET_NAME}=")]
    value = values[0] if values else (lines[0] if len(lines) == 1 and "=" not in lines[0] else "")
    if not value or value.startswith(PLACEHOLDER_PREFIX):
        raise ImproperlyConfigured(f"dashboard/.env has no usable {SECRET_NAME}. Delete it to create a new one.")
    return value


def _secret_key() -> str:
    """A real secret from the environment, or a local file created on first run."""
    key = os.environ.get(SECRET_NAME)
    if key:
        return key
    if not DEBUG:
        raise ImproperlyConfigured("Set ROUTEMOCK_SECRET_KEY outside source control.")
    path = BASE_DIR / ".env"
    if not path.exists():
        path.touch(mode=0o600)
        path.write_text(f"{SECRET_NAME}={secrets.token_urlsafe(64)}\n")
    return parse_secret_file(path.read_text())


SECRET_KEY = _secret_key()
ALLOWED_HOSTS = ["127.0.0.1", "localhost"] if DEBUG else os.environ.get("ROUTEMOCK_ALLOWED_HOSTS", "").split(",")

INSTALLED_APPS = [
    "django.contrib.auth",
    "django.contrib.contenttypes",
    "django.contrib.sessions",
    "django.contrib.messages",
    "django_otp",
    "django_otp.plugins.otp_totp",
    "django_otp.plugins.otp_static",
    "axes",
    "core",
]

MIDDLEWARE = [
    "django.middleware.security.SecurityMiddleware",
    "django.middleware.csp.ContentSecurityPolicyMiddleware",
    "django.contrib.sessions.middleware.SessionMiddleware",
    "django.middleware.common.CommonMiddleware",
    "django.middleware.csrf.CsrfViewMiddleware",
    "django.contrib.auth.middleware.AuthenticationMiddleware",
    "django_otp.middleware.OTPMiddleware",
    "core.middleware.SessionEpochMiddleware",
    "django.contrib.messages.middleware.MessageMiddleware",
    "django.middleware.clickjacking.XFrameOptionsMiddleware",
    "axes.middleware.AxesMiddleware",
]

ROOT_URLCONF = "routemock_dashboard.urls"
TEMPLATES = [
    {
        "BACKEND": "django.template.backends.django.DjangoTemplates",
        "APP_DIRS": True,
        "OPTIONS": {
            "context_processors": [
                "django.template.context_processors.request",
                "django.contrib.auth.context_processors.auth",
                "django.contrib.messages.context_processors.messages",
            ]
        },
    }
]

DATABASES = {
    "default": {
        "ENGINE": "django.db.backends.sqlite3",
        "NAME": os.environ.get("ROUTEMOCK_DB", BASE_DIR / "dashboard.sqlite3"),
    }
}
DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"

# Passwords: Argon2id first. Long passphrases are allowed, and common ones refused.
PASSWORD_HASHERS = ["django.contrib.auth.hashers.Argon2PasswordHasher"]
AUTH_PASSWORD_VALIDATORS = [
    {"NAME": "django.contrib.auth.password_validation.UserAttributeSimilarityValidator"},
    {"NAME": "django.contrib.auth.password_validation.MinimumLengthValidator", "OPTIONS": {"min_length": 15}},
    {"NAME": "django.contrib.auth.password_validation.CommonPasswordValidator"},
    {"NAME": "django.contrib.auth.password_validation.NumericPasswordValidator"},
]
AUTHENTICATION_BACKENDS = ["axes.backends.AxesStandaloneBackend", "django.contrib.auth.backends.ModelBackend"]

# Brute force: lock by username and address together, and always show one generic error.
AXES_FAILURE_LIMIT = 5
AXES_COOLOFF_TIME = 0.25  # Hours.
AXES_LOCKOUT_PARAMETERS = [["username", "ip_address"]]
AXES_RESET_ON_SUCCESS = True
AXES_LOCKOUT_CALLABLE = "core.auth_views.lockout_response"
AXES_ENABLE_ACCESS_FAILURE_LOG = False
AXES_VERBOSE = False
AXES_SENSITIVE_PARAMETERS = ["password", "otp", "token", "code"]

LOGIN_URL = "login"

# Sessions: short-lived, not readable by scripts, sent to this site only.
SESSION_COOKIE_AGE = 60 * 60 * 8
SESSION_SAVE_EVERY_REQUEST = True
SESSION_EXPIRE_AT_BROWSER_CLOSE = True
SESSION_COOKIE_HTTPONLY = True
SESSION_COOKIE_SAMESITE = "Strict"
CSRF_COOKIE_SAMESITE = "Strict"
CSRF_COOKIE_HTTPONLY = True
# Local-development exception: loopback runs over HTTP, so Secure is off only in DEBUG.
SESSION_COOKIE_SECURE = not DEBUG
CSRF_COOKIE_SECURE = not DEBUG
SECURE_SSL_REDIRECT = not DEBUG
SECURE_HSTS_SECONDS = 0 if DEBUG else 31536000
SECURE_HSTS_INCLUDE_SUBDOMAINS = not DEBUG
SECURE_PROXY_SSL_HEADER = None

SECURE_CONTENT_TYPE_NOSNIFF = True
SECURE_REFERRER_POLICY = "same-origin"
X_FRAME_OPTIONS = "DENY"
SECURE_CSP = {
    "default-src": [CSP.NONE],
    "img-src": [CSP.SELF, "data:"],
    "style-src": [CSP.SELF, CSP.UNSAFE_INLINE],
    "form-action": [CSP.SELF],
    "base-uri": [CSP.NONE],
    "frame-ancestors": [CSP.NONE],
}

# Uploads: small, kept outside any served directory, and only ever sent after a check.
EVIDENCE_ROOT = Path(os.environ.get("ROUTEMOCK_EVIDENCE_DIR", BASE_DIR / "private-evidence"))
EVIDENCE_MAX_BYTES = 10 * 1024 * 1024
DATA_UPLOAD_MAX_MEMORY_SIZE = 1024 * 1024
FILE_UPLOAD_MAX_MEMORY_SIZE = 1024 * 1024

# Logs carry event names only. Passwords, tokens, and coordinates are never passed to them.
LOGGING = {
    "version": 1,
    "disable_existing_loggers": False,
    "handlers": {"console": {"class": "logging.StreamHandler"}},
    "root": {"handlers": ["console"], "level": "WARNING"},
}

USE_TZ = True
TIME_ZONE = "UTC"
LANGUAGE_CODE = "en-us"
