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
from dataclasses import dataclass, field
from datetime import datetime, timezone

import dspy

from .signatures import RequirementToTLA, TLAToRequirement
from .tla_validator import validate_tla

from .semantic import validate as semantic_validate_tla

from .models import ModelConfig, build_llm

MAX_TLA_ATTEMPTS = 10

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


def build_programs(config: ModelConfig, warm: bool = True) -> dict:
    """Configure the LM and build the two predictor programs.

    Warm-compiles the programs by running a tiny prompt so the ChainOfThought
    module is initialised before the first real generation (helps local
    endpoints, which sometimes need a call to validate the connection).
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



def generate_tla(programs: dict, requirements: str) -> str:
    req2tla = programs["req2tla"]
    error_log: list[str] = []

    for attempt in range(1, 1 + MAX_TLA_ATTEMPTS):
        prompt = requirements
        reference_block = build_examples_block()
        if attempt == 1:
            print(f"\n[1] Trying to generate TLA+ from requirements...")
        else:
            print(
                f"[{attempt}/{MAX_TLA_ATTEMPTS}] Re-attempt: last attempt was "
                f"rejected by the TLA+ syntax validator, asking the model to "
                f"retry with the reason shown below..."
            )
        if reference_block:
            prompt = (
                f"reference modules:\n{reference_block}\n\n"
                f"requirements:\n{prompt}"
            )
            if attempt == 1:
                print("[1] A few-shot reference block of canonical TLA+ examples was prepended to the prompt.")
        if error_log:
            m = error_log[-1]
            cut_from = m.find("--- begins:")
            for i, ch in enumerate(m):
                if ch == "'":
                    cut_from = min(cut_from if cut_from != -1 else len(m), i)
            marker = m[:cut_from].strip() if cut_from != -1 else m.strip()
            print(f"  parser: {marker}")
        if error_log:
            prompt = (
                f"reference modules:\n{reference_block}\n\n"
                f"{requirements}\n\n"
                "The previous TLA+ was rejected by the syntax validator for the "
                "reason(s) written below. Do not repeat the same mistake: emit "
                "well-formed TLA+ using the canonical banner "
                "----- MODULE <Name> ----- with a ==== trailer line. Be "
                "careful that every construct is legal TLA+ the parser "
                "accepts:\n"
                + "\n".join(error_log)
            )

        try:
            with dspy.context(lm=programs["lm"]):
                result = req2tla(requirements=prompt)
            tla_plus = str(result.tla_plus)
        except Exception as exc:
            print(f"  model call error: {str(exc).splitlines()[0]}")
            error_log.append(f"generation error: {exc}")
            continue

        try:
            validate_tla(tla_plus)
            sem = semantic_validate_tla(tla_plus)
            if sem.warnings:
                print(f"  [semantic] warnings: {', '.join(sem.warnings)}")
            if not sem.ok:
                detail = "; ".join(sem.errors)
                preview = " ".join(str(tla_plus).split())
                if len(preview) > 200:
                    preview = preview[:200] + "..."
                error_log.append(f"semantic rejected ({detail}) --- begins: {preview}")
                continue
            print(f"  attempt {attempt}: TLA+ accepted by syntax and semantic validators OK.")
            return tla_plus
        except Exception as exc:
            detail = str(exc).splitlines()[0]
            preview = " ".join(str(tla_plus).split())
            if len(preview) > 200:
                preview = preview[:200] + "..."
            error_log.append(f"rejected ({detail}) --- begins: {preview}")

    raise ValueError(
        f"failed to produce a well-formed TLA+ module after {MAX_TLA_ATTEMPTS} "
        f"attempt(s). The parser rejected every candidate:\n"
        + "\n".join(error_log)
    )


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


DEFAULT_STATE_DIR = ".rtl2tla_sessions"


@dataclass
class Round:
    index: int
    requirements: str
    tla_plus: str
    summary: str
    timestamp: str
    semantic_warnings: list[str] = field(default_factory=list)


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

    def archive_round(self, requirements: str, tla_plus: str, summary: str, timestamp: str) -> Round:
        rnd = Round(
            index=len(self.rounds) + 1,
            requirements=requirements,
            tla_plus=tla_plus,
            summary=summary,
            timestamp=timestamp,
        )
        self.rounds.append(rnd)
        return rnd

    @property
    def project_name(self) -> str:
        return "_".join(p for p in self.original_requirements.strip().split()[:6] if p) or "session"

    def state_path(self) -> str:
        return os.path.join(self.state_dir, self.project_name + ".json")

    def save(self) -> str:
        try:
            os.makedirs(self.state_dir, exist_ok=True)
            payload = {
                "original_requirements": self.original_requirements,
                "clarifications": self.clarifications,
                "rounds": [
                    {"index": r.index, "requirements": r.requirements,
                     "tla_plus": r.tla_plus, "summary": r.summary, "timestamp": r.timestamp}
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
            if not os.path.isdir(state_dir):
                return None
            candidates = [f for f in os.listdir(state_dir)
                          if f.endswith(".json") and os.path.isfile(os.path.join(state_dir, f))]
            if not candidates:
                return None
            latest = max(candidates, key=lambda f: os.path.getmtime(os.path.join(state_dir, f)))
            path = os.path.join(state_dir, latest)
            with open(path, "r", encoding="utf-8") as fh:
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
            ))
        return cls(original_requirements=payload.get("original_requirements", ""),
                   clarifications=payload.get("clarifications", []),
                   rounds=rounds, state_dir=state_dir)


def build_session(fresh_input: str, state_dir: str = DEFAULT_STATE_DIR) -> Session:
    existing = Session.load(state_dir)
    if existing is not None and existing.original_requirements and not fresh_input:
        return existing
    fresh = Session(original_requirements=fresh_input.strip(), state_dir=state_dir)
    saved = fresh.save()
    if saved:
        print(f"  [state] new session started, saving to {saved}")
    return fresh


def roundtrip(programs: dict, requirements: str) -> tuple[str, str]:
    tla_plus = generate_tla(programs, requirements)
    with dspy.context(lm=programs["lm"]):
        summary = str(programs["tla2req"](spec=tla_plus).summary)
    return tla_plus, summary
