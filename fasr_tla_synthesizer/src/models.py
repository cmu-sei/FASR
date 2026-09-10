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

"""Model catalog and configuration for RTL2TLA.

This module is the single source of truth for which LLMs RTL2TLA can talk to
and how to reach them. Models are managed exclusively by the user via
``~/.rtl2tla/models.json``.

Secrets (API keys) are only ever written to the user's model file and are
never logged or persisted through the app.
"""

from __future__ import annotations

import os
import json
from dataclasses import dataclass, asdict, field
from pathlib import Path
from typing import Optional


# ---------------------------------------------------------------------------
# Secret store
# ---------------------------------------------------------------------------

def secret_store_path() -> Path:
    """Return the path to the model secret store.

    Defaults to ``<repo_root>/.rtl2tla/models.json`` with an override via
    ``RTL2TLA_MODELS_STORE``.
    """
    # Repo-relative default: <src>/.. / .rtl2tla / models.json
    repo_root = Path(__file__).resolve().parent.parent
    default = repo_root / ".rtl2tla" / "models.json"
    return Path(os.environ.get("RTL2TLA_MODELS_STORE", default))


def _ensure_store_dir() -> None:
    store_dir = secret_store_path().parent
    os.makedirs(store_dir, exist_ok=True)


def load_user_models() -> dict[str, dict]:
    """Load user-registered models. ``{name -> config dict}``, registration order kept."""
    path = secret_store_path()
    if not path.exists():
        return {}
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    if not isinstance(payload, dict):
        return {}
    # Keep only well-formed entries.
    cleaned: dict[str, dict] = {}
    for name, cfg in payload.items():
        if not isinstance(name, str) or not name:
            continue
        if not isinstance(cfg, dict) or "model_id" not in cfg:
            continue
        cleaned[name] = cfg
    return cleaned


def save_user_models(models: dict[str, dict]) -> Path:
    """Persist user-registered models to ``~/.rtl2tla/models.json`` (never commits this)."""
    _ensure_store_dir()
    path = secret_store_path()
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(models, fh, indent=2, ensure_ascii=False)
    return path


def add_user_model(name: str, cfg: dict) -> Optional[Path]:
    """Register/replace a user model. Returns the store path on success, None on failure."""
    if not name or not isinstance(cfg, dict) or "model_id" not in cfg:
        return None
    cfg = dict(cfg)
    cfg.setdefault("temperature", 0.0)
    cfg.setdefault("max_tokens", 50000)
    cfg.setdefault("openai_compatible", True)
    cfg.setdefault("api_key", "local")
    cfg.setdefault("base_url", "")
    models = load_user_models()
    models[name] = cfg
    return save_user_models(models)


def remove_user_model(name: str) -> bool:
    """Remove a user model. Returns True if something was removed."""
    models = load_user_models()
    if name not in models:
        return False
    del models[name]
    save_user_models(models)
    return True


# ---------------------------------------------------------------------------
# ModelConfig
# ---------------------------------------------------------------------------


@dataclass
class ModelConfig:
    """Everything needed to build one ``dspy.LM``.

    ``api_key`` may be a literal value, ``"local"`` (no key needed), or
    ``"env:VAR_NAME"`` (resolved from the environment at build time).
    """

    name: str
    model_id: str
    base_url: str = ""
    api_key: str = "local"
    temperature: float = 0.0
    max_tokens: int = 50000
    openai_compatible: bool = True
    description: str = ""

    def resolve(self, models: "dict[str, ModelConfig]") -> "ModelConfig":
        """Resolve ``env:`` references (defensively, so this is idempotent)."""
        api_key = self.api_key
        if isinstance(api_key, str) and api_key.startswith("env:"):
            var = api_key[len("env:"):]
            api_key = os.environ.get(var, "")
        return ModelConfig(
            name=self.name,
            model_id=self.model_id,
            base_url=self.base_url or "",
            api_key=api_key,
            temperature=self.temperature,
            max_tokens=self.max_tokens,
            openai_compatible=self.openai_compatible,
            description=self.description,
        )


def _user_configs() -> dict[str, dict]:
    """User models as raw config dicts."""
    return load_user_models()


def load_all() -> list[ModelConfig]:
    """Return all selectable configs from user models in registration order."""
    out: list[ModelConfig] = []
    user_models = load_user_models()
    for name, user_cfg in user_models.items():
        try:
            out.append(ModelConfig(
                name=name,
                model_id=str(user_cfg.get("model_id", "")),
                base_url=user_cfg.get("base_url", "") or "",
                api_key=user_cfg.get("api_key", "local"),
                temperature=float(user_cfg.get("temperature", 0.0)),
                max_tokens=int(user_cfg.get("max_tokens", 50000)),
                openai_compatible=bool(user_cfg.get("openai_compatible", True)),
                description=str(user_cfg.get("description", "")),
            ))
        except (TypeError, ValueError):
            # Malformed entry: skip.
            continue
    return out


def get_config(name: str) -> ModelConfig:
    """Return the config for ``name``. Raises KeyError otherwise."""
    results = load_all()
    for cfg in results:
        if cfg.name == name:
            return cfg
    raise KeyError(name)


def get_config_or(name: Optional[str]) -> ModelConfig:
    """Return ``name`` config or the first user model.

    Raises ValueError if no models are configured.
    """
    if name:
        return get_config(name)
    configs = load_all()
    if not configs:
        raise ValueError(
            "No models configured. Add a model via /add model or edit ~/.rtl2tla/models.json"
        )
    return configs[0]


# ---------------------------------------------------------------------------
# Secret masking
# ---------------------------------------------------------------------------

def mask_secret(value: str) -> str:
    """Return a redacted view of an api_key for logging."""
    if not value:
        return "<empty>"
    if value.startswith("env:"):
        return f"<env:{value[len('env:'):]}>"
    if value == "local":
        return "local"
    head, tail = value[:4], value[-4:]
    masked = "*" * max(0, len(value) - len(head) - len(tail))
    return f"{head}{masked}{tail}"


# ---------------------------------------------------------------------------
# LM building
# ---------------------------------------------------------------------------


def build_llm(config: ModelConfig) -> object:
    """Resolve + build a ``dspy.LM`` for ``config``.

    ``api_key`` values of ``"local"`` mean no key; ``"env:VAR"`` are resolved
    from the environment.
    """
    import dspy

    resolved = config.resolve(load_all())
    api_key = resolved.api_key
    if api_key == "local":
        api_key = "local"
    return dspy.LM(
        api_key=api_key,
        model=resolved.model_id,
        base_url=resolved.base_url or None,
        temperature=resolved.temperature,
        max_tokens=resolved.max_tokens,
    )
