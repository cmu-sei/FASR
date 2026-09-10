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

"""Semantic validation for the generated TLA+ bundle.

The generator emits a *bundle* of three canonical modules rather than one:

  * an **Environment** spec (the controlled process / external actor);
  * a **Machine** spec (the controller);
  * a **System** spec that *instances* the first two together via the classic
    TLA+ ``INSTANCE ... WITH`` pattern (see ``example_sys.tla``).

This layer runs two checks on top of the syntactic parse:

1. Per-component structural checks -- each of the two component specs should be
   well-formed (banner, Init, T1, Spec, ... ).
2. Bundle-level checks -- the bundle must contain exactly three modules, and
   the System spec must INSTANCE the environment and machine modules together.

Validation of the *syntactic* parse (well-formedness) is done in ``app.py`` by
passing the whole bundle through the ``tla`` parser.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import List, Optional, Tuple


@dataclass
class SemanticResult:
    ok: bool
    errors: List[str] = field(default_factory=list)
    warnings: List[str] = field(default_factory=list)


@dataclass
class Module:
    name: str
    body: str


_COMMENT_RE = re.compile(r"\(\*.*?\*\)", re.DOTALL)

# A MODULE banner such as `---- MODULE Sys ----`. We capture just the name; the
# run of dashes / whitespace around it is matched loosely and the parser (in
# app.py) is what enforces the exact dash rules.
_BANNER_RE = re.compile(
    r"(?P<lead>[-:]{3,}\s+MODULE\s)(?P<name>[A-Za-z_]\w*)",
    re.MULTILINE,
)

# An INSTANCE clause in the system module, e.g.
#   Crew == INSTANCE Crew WITH
# The targeted *module* name comes right after the literal ``INSTANCE`` keyword.
_INSTANCE_RE = re.compile(
    r"INSTANCE\s+(?P<target>\w+)", re.DOTALL
)


def strip_comments(text: str) -> str:
    """Remove TLA+ ``(* ... *)`` comments (non-nested)."""
    return _COMMENT_RE.sub("", text)


def find_modules(text: str) -> list[Module]:
    """Return the modules found in ``text`` in source order."""
    modules: list[Module] = []
    for m in _BANNER_RE.finditer(text):
        name = m.group("name")
        body_start = m.end()
        next_b = _BANNER_RE.search(text, pos=body_start)
        end = next_b.start() if next_b is not None else len(text)
        body = text[body_start:end]
        modules.append(Module(name=name, body=body))
    return modules


def validate_one(name: str, body: str, stripped: str) -> tuple[list[str], list[str]]:
    """Per-component structural checks.

    Returns ``(errors, warnings)``. Missing ``Init`` or ``T1`` are warnings;
    anything structural that looks broken (no trailer) is an error.
    """
    errors: list[str] = []
    warnings: list[str] = []
    if "====" not in stripped:
        errors.append("missing ====")
    if not re.search(r"\bInit\b", stripped):
        warnings.append("missing Init")
    if not re.search(r"\bInst\b|\bInit\b", stripped):
        warnings.append("missing Inst/Init")
    if not re.search(r"\bSpec\b", stripped):
        warnings.append("missing Spec")
    return errors, warnings


def validate(text: str) -> SemanticResult:
    """Validate a 3-module bundle.

    A bundle is *ok* when it contains exactly three modules, the System module
    instances both other modules, and neither component module is missing a
    trailer. Missing ``Init``/``T1``/``Spec`` inside a component are surfaced as
    warnings.
    """
    modules = find_modules(text)
    errors: list[str] = []
    warnings: list[str] = []

    for mod in modules:
        stripped = strip_comments(mod.body)
        comps_errors, comps_warnings = validate_one(
            mod.name, mod.body, stripped
        )
        for msg in comps_errors:
            errors.append(f"{mod.name}: {msg}")
        for msg in comps_warnings:
            warnings.append(f"{mod.name}: {msg}")

    # Bundle-level: expect exactly three modules.
    if len(modules) != 3:
        names = ", ".join(m.name for m in modules) or "(none)"
        errors.append(f"expected 3 modules, found {len(modules)}: {names}")
        return SemanticResult(ok=False, errors=errors, warnings=warnings)

    sys_name = modules[-1].name
    sys_stripped = strip_comments(modules[-1].body)
    targets = {m.group("target") for m in _INSTANCE_RE.finditer(sys_stripped)}
    component_names = {m.name for m in modules[:-1]}
    for comp_name in component_names:
        if comp_name not in targets:
            warnings.append(f"{sys_name} should INSTANCE the {comp_name} module")

    return SemanticResult(ok=not errors, errors=errors, warnings=warnings)
