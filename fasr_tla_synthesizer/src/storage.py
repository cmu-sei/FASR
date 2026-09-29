# FASR Source Code
# 
# Copyright 2026 Carnegie Mellon University.
# 
# NO WARRANTY. THIS CARNEGIE MELLON UNIVERSITY AND SOFTWARE ENGINEERING
# INSTITUTE MATERIAL IS FURNISHED ON AN "AS-IS" BASIS. CARNEGIE MELLON
# UNIVERSITY MAKES NO WARRANTIES OF ANY KIND, EITHER EXPRESSED OR IMPLIED, AS
# TO ANY MATTER INCLUDING, BUT NOT LIMITED TO, WARRANTY OF FITNESS FOR PURPOSE
# OR MERCHANTABILITY, EXCLUSIVITY, OR RESULTS OBTAINED FROM THE
# MATERIAL. CARNEGIE MELLON UNIVERSITY DOES NOT MAKE ANY WARRANTY OF ANY KIND
# WITH RESPECT TO FREEDOM FROM PATENT, TRADEMARK, OR COPYRIGHT INFRINGEMENT.
# 
# Licensed under a MIT (SEI)-style license, please see license.txt or contact
# permission@sei.cmu.edu for full terms.
# 
# [DISTRIBUTION STATEMENT A] This material has been approved for public
# release and unlimited distribution.  Please see Copyright notice for non-US
# Government use and distribution.
# 
# DM25-0946

"""Stable, user-writable storage locations for application data."""

from __future__ import annotations

import os
import sys
from pathlib import Path


APP_DIR_NAME = "RTL2TLA"


def repo_root() -> Path:
    """Return the source checkout root used by legacy storage locations."""
    return Path(__file__).resolve().parent.parent


def user_data_dir() -> Path:
    """Return a stable per-user application data directory.

    ``RTL2TLA_DATA_DIR`` overrides platform defaults. On Windows, prefer
    ``LOCALAPPDATA`` so the application does not need write access to its
    installation directory.
    """
    override = os.environ.get("RTL2TLA_DATA_DIR")
    if override:
        return Path(override).expanduser()

    if os.name == "nt":
        base = os.environ.get("LOCALAPPDATA") or os.environ.get("APPDATA")
        if base:
            return Path(base) / APP_DIR_NAME
        return Path.home() / f".{APP_DIR_NAME.lower()}"

    if sys.platform == "darwin":
        return Path.home() / "Library" / "Application Support" / APP_DIR_NAME

    base = os.environ.get("XDG_DATA_HOME")
    if base:
        return Path(base).expanduser() / APP_DIR_NAME.lower()
    return Path.home() / ".local" / "share" / APP_DIR_NAME.lower()


def sessions_dir() -> Path:
    """Return the configured directory for saved sessions."""
    override = os.environ.get("RTL2TLA_SESSIONS_DIR")
    if override:
        return Path(override).expanduser()
    return user_data_dir() / "sessions"


def legacy_models_path() -> Path:
    """Return the model-store path used by earlier releases."""
    return repo_root() / ".rtl2tla" / "models.json"


def legacy_sessions_dir() -> Path:
    """Return the session directory used by normal legacy launches."""
    return repo_root() / ".rtl2tla_sessions"
