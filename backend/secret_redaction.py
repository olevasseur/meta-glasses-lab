"""Redact configured credentials from subprocess diagnostics and model job results."""

import os
import re


def redact(text, credentials=None):
    values = [value for key, value in os.environ.items()
              if re.search(r'(SECRET|TOKEN|KEY|PASSWORD|CREDENTIAL)', key) and len(value) >= 4]
    if credentials is not None:
        values += [getattr(credentials, key, None) for key in ('token', 'refresh_token', 'client_secret')]
    for value in sorted({v for v in values if isinstance(v, str) and len(v) >= 4}, key=len, reverse=True):
        text = text.replace(value, '[REDACTED]')
    return text
