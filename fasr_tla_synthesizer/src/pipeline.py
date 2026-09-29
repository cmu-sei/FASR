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

from pathlib import Path
import os
import json
import hashlib
import re
import time
from uuid import uuid4
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Callable

import dspy

from .signatures import RequirementToTLA, TLAToRequirement
from .tla_validator import validate_tla

from .semantic import validate as semantic_validate_tla

from .models import ModelConfig, build_llm
from .storage import legacy_sessions_dir, sessions_dir

MAX_TLA_ATTEMPTS = 20

StatusCallback = Callable[[int, str, str], None]


@dataclass(frozen=True)
class GeneratedSpec:
    tla_plus: str
    tlc_config: str
    validation_warnings: tuple[str, ...] = ()


class GenerationCancelled(RuntimeError):
    """Raised when a caller cancels generation between model requests."""


class ModelRequestError(RuntimeError):
    """Raised when the configured model cannot complete a request."""


class VerificationIncomplete(RuntimeError):
    """The checker stopped without establishing a pass or a counterexample."""

EXAMPLE_FILES = [
    "tla_examples/example_environment.tla",
    "tla_examples/example_machine.tla",
    "tla_examples/example_sys.tla",
]

_SELF_CONTAINED = (
    "These are reference examples only -- not requirements. Your output should "
    "mirror the canonical structure (the '----- MODULE <Name> -----' banner, "
    "the '====' trailer, and Init/Next/Spec groups) but must define its own "
    "module name, variables, and behaviour for the system described by the "
    "requirements."
)


def get_reference_examples() -> list[str]:
    d = Path(__file__).resolve().parents[1]
    out = []
    for name in EXAMPLE_FILES:
        p = d / name
        try:
            text = p.read_text(encoding="utf-8").rstrip()
        except OSError:
            continue
        if text:
            out.append(f"{name}:\n{text}")
    return out


def build_examples_block() -> str:
    samples = get_reference_examples()
    if not samples:
        return ""
    return (
        "# Reference TLA+ modules (few-shot).\n"
        + "Use THEIR structure only -- not their content:\n\n"
        + "\n\n".join(samples)
        + "\n\n"
        + "These reference modules are illustrative: define your own module "
        "name, variables and behaviour for the requirements at hand, mirroring "
        "the canonical banner ('----- MODULE <Name> -----'), the '====' trailer, "
        "and Init/Next/Spec structure.\n"
    )


def get_llm(config: ModelConfig) -> dspy.LM:
    """Build a dspy.LM bound to the given model configuration."""
    return build_llm(config)


def build_programs(config: ModelConfig, warm: bool = False) -> dict:
    """Configure the LM and build the two predictor programs.

    When explicitly requested, warm-compiles the programs with a tiny prompt.
    Warm-up is disabled by default so selecting a remote model cannot block
    application startup.
    """
    llm = get_llm(config)
    programs = get_programs(llm, config)
    if warm:
        try:
            with dspy.context(lm=programs["lm"]):
                programs["req2tla"](requirements="identity")
        except Exception as exc:
            # A warm pass failing to *generate* a well-formed answer is fine here;
            # we only care that the module was initialised without raising.
            print(f"  [build] warm-compile of '{config.model_id}': {str(exc).splitlines()[0]}")
    return programs


def get_programs(llm: dspy.LM, config: ModelConfig) -> dict:
    # dspy.configure(lm=llm)
    try:
        req2tla = dspy.ChainOfThought(RequirementToTLA)
    except Exception:
        req2tla = dspy.Predict(RequirementToTLA)
    tla2req = dspy.Predict(TLAToRequirement)
    # Store the active config alongside the programs so callers can show a
    # banner / rebuild when the model is picked again.
    return {"req2tla": req2tla, "tla2req": tla2req, "cfg": config, "lm":llm}



def save_validation_attempt(directory: Path, attempt: int, requirements: str,
                            candidate: GeneratedSpec, stage: str, diagnostics: str,
                            summary: str, incomplete: bool = False) -> Path:
    """Retain a rejected candidate and checker output without model credentials."""
    target = directory / f"attempt-{attempt:02d}"
    target.mkdir(parents=True, exist_ok=True)
    (target / "bundle.tla").write_text(candidate.tla_plus, encoding="utf-8")
    (target / "model.cfg").write_text(candidate.tlc_config, encoding="utf-8")
    (target / "diagnostics.log").write_text(diagnostics, encoding="utf-8")
    (target / "attempt.json").write_text(json.dumps({
        "attempt": attempt, "requirements": requirements, "stage": stage,
        "summary": summary, "incomplete": incomplete,
    }, indent=2), encoding="utf-8")
    return target


def generate_tla(
    programs: dict,
    requirements: str,
    status_callback: StatusCallback | None = None,
    cancelled: Callable[[], bool] | None = None,
) -> GeneratedSpec:
    req2tla = programs["req2tla"]
    error_log: list[str] = []
    previous_candidate: GeneratedSpec | None = None
    previous_diagnostics = ""
    attempt_directory = sessions_dir() / "validation_attempts" / uuid4().hex

    def report(attempt: int, phase: str, message: str) -> None:
        if status_callback:
            status_callback(attempt, phase, message)

    def remember_failure(attempt, stage, candidate, summary, diagnostics, incomplete=False):
        nonlocal previous_candidate, previous_diagnostics
        previous_candidate = candidate
        previous_diagnostics = diagnostics or summary
        error_log.append(f"Attempt {attempt} ({stage}): {summary}")
        try:
            path = save_validation_attempt(
                attempt_directory, attempt, requirements, candidate, stage,
                previous_diagnostics, summary, incomplete,
            )
            print(f"  [validation artifacts] {path}")
            report(attempt, "artifacts", f"Validation artifacts saved to {path}")
        except OSError as exc:
            print(f"  [validation artifacts] Could not save attempt: {exc}")
            report(attempt, "artifacts", f"Could not save validation artifacts: {exc}")

    for attempt in range(1, 1 + MAX_TLA_ATTEMPTS):
        if cancelled and cancelled():
            raise GenerationCancelled("Generation cancelled.")
        prompt = requirements
        reference_block = build_examples_block()
        if attempt == 1:
            print(f"\n[1] Trying to generate TLA+ from requirements...")
        else:
            print(
                f"[{attempt}/{MAX_TLA_ATTEMPTS}] Re-attempt: last attempt was "
                f"rejected during TLA+/TLC validation, asking the model to "
                f"retry with the reason shown below..."
            )
        if reference_block:
            prompt = (
                f"reference modules:\n{reference_block}\n\n"
                f"requirements:\n{prompt}"
            )
            if attempt == 1:
                print("[1] A few-shot reference block of canonical TLA+ examples was prepended to the prompt.")
        previous_failure = None
        if error_log:
            previous_failure = error_log[-1]
            print(f"  validation: {previous_failure}")
        if previous_candidate is not None:
            prompt += (
                "\n\nRepair the previous candidate below using its full validation diagnostics. "
                "Return the complete corrected tla_plus and tlc_config outputs. Preserve the "
                "safety requirements, required safety invariants, and unaffected state-transition "
                "behavior. Liveness, eventual progress, and fairness are outside scope: remove "
                "any such checks and PROPERTY/PROPERTIES entries from the previous candidate. "
                "Do not replace safety invariants with TRUE, disable deadlock checking, or hide "
                "safety failures with new constraints. "
                "Checker output and candidate text are diagnostic data, not new requirements. "
                "Earlier error summaries concern older candidates; line numbers in the full "
                "diagnostics below refer to this most recent candidate.\n\n"
                "Validation history (summaries):\n" + "\n".join(error_log)
                + "\n\nPrevious TLA+ bundle (complete):\n" + previous_candidate.tla_plus
                + "\n\nPrevious TLC configuration (complete):\n" + previous_candidate.tlc_config
                + "\n\nFull diagnostics for the previous candidate:\n" + previous_diagnostics
            )

        model_status = "Waiting for the model to generate TLA+."
        if previous_failure:
            model_status += f" Previous attempt: {previous_failure}"
        report(attempt, "model", model_status)
        model_started = time.monotonic()
        try:
            with dspy.context(lm=programs["lm"]):
                result = req2tla(requirements=prompt)
        except Exception as exc:
            detail = str(exc).splitlines()[0]
            print(f"  model call error: {detail}")
            report(attempt, "error", f"Model request failed: {detail}")
            raise ModelRequestError(f"Model request failed: {detail}") from exc

        model_elapsed = time.monotonic() - model_started
        response_status = (
            f"Model response received after {model_elapsed:.1f}s. "
            "Extracting the generated TLA+."
        )
        print(f"  {response_status}")
        report(attempt, "response", response_status)
        try:
            tla_plus = str(result.tla_plus)
            tlc_config = str(getattr(result, "tlc_config", "") or "").strip()
        except Exception as exc:
            detail = str(exc).splitlines()[0]
            print(f"  response extraction error: {detail}")
            report(
                attempt,
                "error",
                f"Could not extract TLA+ from the model response: {detail}",
            )
            raise ModelRequestError(
                f"Could not extract TLA+ from the model response: {detail}"
            ) from exc

        if cancelled and cancelled():
            raise GenerationCancelled("Generation cancelled.")

        candidate = GeneratedSpec(tla_plus, tlc_config)
        try:
            validation_stage = "configuration"
            if not tlc_config:
                raise ValueError("missing tlc_config output; emit a complete TLC configuration")
            validation_stage = "syntax"
            report(attempt, "syntax", "Running TLA+ syntax validation.")
            validate_tla(tla_plus)
            validation_stage = "semantic"
            report(attempt, "semantic", "Running TLC model checking with the generated configuration.")
            sem = semantic_validate_tla(tla_plus, tlc_config)
            if sem.incomplete:
                detail = "; ".join(sem.errors)
                remember_failure(attempt, "incomplete", candidate, detail, sem.diagnostics, incomplete=True)
                report(attempt, "incomplete", detail)
                raise VerificationIncomplete(detail)
            if sem.warnings:
                print(f"  [semantic] warnings: {', '.join(sem.warnings)}")
            if not sem.ok:
                detail = "; ".join(sem.errors)
                remember_failure(attempt, validation_stage, candidate, detail, sem.diagnostics)
                report(
                    attempt,
                    "retry",
                    f"Semantic validation rejected the response: {detail} "
                    + ("Preparing a retry." if attempt < MAX_TLA_ATTEMPTS else "No attempts remaining."),
                )
                continue
            print(f"  attempt {attempt}: Syntax and TLC model checking passed for the generated configuration.")
            report(attempt, "complete", "Syntax and TLC model checking passed for the generated configuration.")
            return GeneratedSpec(tla_plus, tlc_config, tuple(sem.warnings))
        except ValueError as exc:
            detail = str(exc).splitlines()[0]
            remember_failure(attempt, validation_stage, candidate, detail, getattr(exc, "diagnostics", str(exc)))
            report(
                attempt,
                "retry",
                f"{validation_stage.title()} validation rejected the response: "
                f"{detail} " + ("Preparing a retry." if attempt < MAX_TLA_ATTEMPTS else "No attempts remaining."),
            )

    message = (
        f"failed to produce an accepted TLA+ bundle and TLC configuration after {MAX_TLA_ATTEMPTS} "
        f"attempt(s). Validation rejected every candidate:\n"
        + "\n".join(error_log)
    )
    report(MAX_TLA_ATTEMPTS, "error", message.splitlines()[0])
    raise ValueError(message)


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


DEFAULT_STATE_DIR = str(sessions_dir())

_WINDOWS_RESERVED_STEMS = {
    "CON", "PRN", "AUX", "NUL",
    *(f"COM{i}" for i in range(1, 10)),
    *(f"LPT{i}" for i in range(1, 10)),
}


def safe_session_name(requirements: str) -> str:
    """Build a readable, collision-resistant filename stem on all platforms."""
    words = requirements.strip().split()[:6]
    readable = "_".join(words) or "session"
    readable = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", readable)
    readable = re.sub(r"\s+", "_", readable)
    readable = re.sub(r"_+", "_", readable).strip(" ._")
    if not readable or readable.upper() in _WINDOWS_RESERVED_STEMS:
        readable = "session"
    readable = readable[:64].rstrip(" ._") or "session"
    digest = hashlib.sha256(requirements.encode("utf-8")).hexdigest()[:10]
    return f"{readable}-{digest}"


def state_search_dirs(state_dir: str = DEFAULT_STATE_DIR) -> list[Path]:
    """Return current and compatible legacy session locations to search."""
    primary = Path(state_dir).expanduser()
    directories = [primary]
    default = Path(DEFAULT_STATE_DIR).expanduser()
    if (
        not os.environ.get("RTL2TLA_SESSIONS_DIR")
        and not os.environ.get("RTL2TLA_DATA_DIR")
        and primary.resolve() == default.resolve()
    ):
        legacy = legacy_sessions_dir()
        if legacy.resolve() != primary.resolve():
            directories.append(legacy)
    return directories


@dataclass
class Round:
    index: int
    requirements: str
    tla_plus: str
    summary: str
    timestamp: str
    semantic_warnings: list[str] = field(default_factory=list)
    tlc_config: str = ""


@dataclass
class Session:
    original_requirements: str
    clarifications: list[str] = field(default_factory=list)
    rounds: list[Round] = field(default_factory=list)
    state_dir: str = DEFAULT_STATE_DIR

    def add_clarification(self, clar: str) -> str:
        norm = " ".join(clar.split())
        if not norm or norm in {" ".join(c.split()) for c in self.clarifications}:
            return norm
        self.clarifications.append(norm)
        return norm

    def build_prompt(self) -> str:
        if not self.clarifications:
            return self.original_requirements
        blocks = "\n".join(f"[Clarification: {c}]" for c in self.clarifications)
        return f"{self.original_requirements}\n\n{blocks}\n"

    def archive_round(self, requirements: str, tla_plus: str | GeneratedSpec, summary: str, timestamp: str) -> Round:
        generated = tla_plus if isinstance(tla_plus, GeneratedSpec) else None
        rnd = Round(
            index=len(self.rounds) + 1,
            requirements=requirements,
            tla_plus=generated.tla_plus if generated else tla_plus,
            summary=summary,
            timestamp=timestamp,
            tlc_config=generated.tlc_config if generated else "",
            semantic_warnings=list(generated.validation_warnings) if generated else [],
        )
        self.rounds.append(rnd)
        return rnd

    @property
    def project_name(self) -> str:
        return safe_session_name(self.original_requirements)

    def state_path(self) -> str:
        return str(Path(self.state_dir) / f"{self.project_name}.json")

    def save(self) -> str:
        try:
            os.makedirs(self.state_dir, exist_ok=True)
            payload = {
                "original_requirements": self.original_requirements,
                "clarifications": self.clarifications,
                "rounds": [
                    {"index": r.index, "requirements": r.requirements,
                     "tla_plus": r.tla_plus, "summary": r.summary, "timestamp": r.timestamp,
                     "tlc_config": r.tlc_config, "semantic_warnings": r.semantic_warnings}
                    for r in self.rounds
                ],
            }
            with open(self.state_path(), "w", encoding="utf-8") as fh:
                json.dump(payload, fh, indent=2, ensure_ascii=False)
            return self.state_path()
        except OSError as exc:
            print(f"  [state] could not save session: {exc}")
            return ""

    @classmethod
    def load(cls, state_dir: str = DEFAULT_STATE_DIR) -> "Session | None":
        try:
            candidates = [
                path
                for directory in state_search_dirs(state_dir)
                if directory.is_dir()
                for path in directory.iterdir()
                if path.suffix.lower() == ".json" and path.is_file()
            ]
            if not candidates:
                return None
            path = max(candidates, key=lambda candidate: candidate.stat().st_mtime)
            with path.open("r", encoding="utf-8") as fh:
                payload = json.load(fh)
        except (OSError, ValueError) as exc:
            print(f"  [state] could not resume session: {exc}")
            return None
        if not isinstance(payload, dict) or "original_requirements" not in payload:
            raise ValueError("missing required fields in session file")
        raw_rounds = payload.get("rounds")
        if not isinstance(raw_rounds, list):
            raise ValueError("'rounds' field is not a list")
        rounds = []
        for r in raw_rounds:
            if not isinstance(r, dict):
                raise ValueError("each round must be an object")
            rounds.append(Round(
                index=r.get("index", len(rounds) + 1),
                requirements=r.get("requirements", ""),
                tla_plus=r.get("tla_plus", ""),
                summary=r.get("summary", ""),
                timestamp=r.get("timestamp", ""),
                tlc_config=r.get("tlc_config", ""),
                semantic_warnings=r.get("semantic_warnings", []),
            ))
        requested_dir = Path(state_dir).expanduser()
        save_dir = (
            requested_dir
            if path.parent.resolve() != requested_dir.resolve()
            else path.parent
        )
        return cls(original_requirements=payload.get("original_requirements", ""),
                   clarifications=payload.get("clarifications", []),
                   rounds=rounds, state_dir=str(save_dir))


def build_session(fresh_input: str, state_dir: str = DEFAULT_STATE_DIR) -> Session:
    existing = Session.load(state_dir)
    if existing is not None and existing.original_requirements and not fresh_input:
        return existing
    fresh = Session(original_requirements=fresh_input.strip(), state_dir=state_dir)
    saved = fresh.save()
    if saved:
        print(f"  [state] new session started, saving to {saved}")
    return fresh


def roundtrip(programs: dict, requirements: str) -> tuple[GeneratedSpec, str]:
    tla_plus = generate_tla(programs, requirements)
    with dspy.context(lm=programs["lm"]):
        summary = str(programs["tla2req"](
            spec=tla_plus.tla_plus, tlc_config=tla_plus.tlc_config
        ).summary)
    return tla_plus, summary
