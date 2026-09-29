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

"""Semantic validation for generated TLA+ bundles using TLC.

The generator emits exactly three modules: two component modules followed by a
composition module. Syntax is checked separately by SANY in
``tla_validator.py``. TLC model-checks the composition module with its generated
configuration. Only a completed successful search is accepted; a timeout is
reported as incomplete.
"""

from __future__ import annotations

import os
import re
import subprocess
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import List

from .tla_validator import split_tla_bundle


@dataclass
class SemanticResult:
    ok: bool
    errors: List[str] = field(default_factory=list)
    warnings: List[str] = field(default_factory=list)
    incomplete: bool = False
    diagnostics: str = ""


_COMMENT_RE = re.compile(r"\(\*.*?\*\)", re.DOTALL)
_INSTANCE_RE = re.compile(r"INSTANCE\s+(?P<target>\w+)")


def config_errors(config: str) -> list[str]:
    """Check required generation-contract entries; TLC parses the full config."""
    clean = _COMMENT_RE.sub("", config)
    clean = re.sub(r"\\\*[^\n]*", "", clean)
    # Configuration sections may contain entries on subsequent lines.
    directive = (
        r"SPECIFICATION|INIT|NEXT|CONSTANTS?|INVARIANTS?|PROPERT(?:Y|IES)|"
        r"CONSTRAINTS?|ACTION_CONSTRAINTS?|SYMMETRY|VIEW|ALIAS|"
        r"CHECK_DEADLOCK|POSTCONDITION"
    )
    sections = re.findall(
        rf"^\s*({directive})\b(.*?)(?=^\s*(?:{directive})\b|\Z)",
        clean, re.MULTILINE | re.DOTALL,
    )
    errors = []
    if not any(key == "SPECIFICATION" and value.strip() == "Spec" for key, value in sections):
        errors.append("TLC configuration must select SPECIFICATION Spec")
    invariants = {
        name for key, value in sections if key in {"INVARIANT", "INVARIANTS"}
        for name in value.split()
    }
    for name in ("TypeOK", "Safety"):
        if name not in invariants:
            errors.append(f"TLC configuration must check INVARIANT {name}")
    if any(key == "CHECK_DEADLOCK" and value.strip() == "FALSE" for key, value in sections):
        errors.append("TLC configuration must not disable deadlock checking")
    return errors


def _jar_path() -> Path:
    """Resolve ``tla2tools.jar`` consistently with the syntax validator."""
    default_jar = Path(__file__).resolve().parent.parent / "tla2tools.jar"
    return Path(os.getenv("TLA2TOOLS_JAR", default_jar)).resolve()


def _tlc_error(output: str) -> str:
    """Extract a compact, useful error from TLC's console output."""
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    for marker in (
        "Semantic errors:",
        "Parsing or semantic analysis error",
        "Error:",
    ):
        for index, line in enumerate(lines):
            if marker.lower() in line.lower():
                detail = " ".join(lines[index:index + 3])
                return detail[:500]
    return " ".join(lines[-3:])[:500] or "TLC exited without an error message"


def validate(text: str, tlc_config: str, *, timeout_seconds: float = 30) -> SemanticResult:
    """Model-check the configured model, rejecting incomplete searches."""
    modules = split_tla_bundle(str(text).strip())
    if len(modules) == 1 and modules[0]["name"] == "<bundle>":
        modules = []

    # Keep this contract independent of TLC: a syntactically valid one- or
    # two-module specification is still not a valid generated bundle.
    if len(modules) != 3:
        names = ", ".join(module["name"] for module in modules) or "(none)"
        return SemanticResult(
            ok=False,
            errors=[f"expected 3 modules, found {len(modules)}: {names}"],
        )

    composition = modules[-1]
    composition_name = composition["name"]
    component_names = {module["name"] for module in modules[:-1]}
    targets = {
        match.group("target")
        for match in _INSTANCE_RE.finditer(
            _COMMENT_RE.sub("", composition["text"])
        )
    }
    warnings = [
        f"{composition_name} should INSTANCE the {name} module"
        for name in component_names
        if name not in targets
    ]

    if not tlc_config.strip():
        return SemanticResult(ok=False, errors=["missing TLC configuration"])
    errors = config_errors(tlc_config)
    if errors:
        return SemanticResult(ok=False, errors=errors, warnings=warnings)

    jar_path = _jar_path()
    if not jar_path.is_file():
        raise RuntimeError(
            f"tla2tools.jar not found at '{jar_path}'; set TLA2TOOLS_JAR"
        )

    with tempfile.TemporaryDirectory() as tmpdir:
        tmp_path = Path(tmpdir)
        for module in modules:
            (tmp_path / f"{module['name']}.tla").write_text(
                module["text"], encoding="utf-8"
            )

        config_name = f"{composition_name}.cfg"
        (tmp_path / config_name).write_text(
            tlc_config.strip() + "\n", encoding="utf-8"
        )
        command = [
            "java",
            "-cp",
            str(jar_path),
            "tlc2.TLC",
            "-cleanup",
            "-workers",
            "1",
            "-config",
            config_name,
            composition_name,
        ]
        try:
            result = subprocess.run(
                command,
                capture_output=True,
                text=True,
                cwd=tmpdir,
                timeout=timeout_seconds,
            )
        except OSError as exc:
            raise RuntimeError(f"could not start TLC: {exc}") from exc
        except subprocess.TimeoutExpired as exc:
            def decoded(value):
                return value.decode("utf-8", errors="replace") if isinstance(value, bytes) else (value or "")
            return SemanticResult(
                ok=False,
                errors=[f"TLC verification incomplete: timed out after {timeout_seconds:g} seconds; the model has not been verified."],
                warnings=warnings,
                incomplete=True,
                diagnostics=f"{decoded(exc.stdout)}\n{decoded(exc.stderr)}",
            )

    output = f"{result.stdout}\n{result.stderr}"
    if result.returncode != 0:
        return SemanticResult(
            ok=False,
            errors=[f"TLC rejected {composition_name}: {_tlc_error(output)}"],
            warnings=warnings,
            diagnostics=output,
        )

    if "Model checking completed. No error has been found." not in result.stdout:
        return SemanticResult(
            ok=False,
            errors=["TLC verification incomplete: no successful model-check completion was reported."],
            warnings=warnings,
            incomplete=True,
            diagnostics=output,
        )
    warnings.append("TLC model checking completed for the supplied model and configuration; correspondence to the requirements still needs review.")
    return SemanticResult(ok=True, warnings=warnings)
