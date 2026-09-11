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

import sys

from .pipeline import (
    build_programs,
    build_session,
    generate_tla,
    now_iso,
    Session,
)
from .models import load_all, get_config_or, add_user_model, mask_secret
from .tla_validator import validate_tla, split_tla_bundle

import dspy


def _print_round(rnd) -> None:
    print("\n--- Round", rnd.index, "---")
    modules = split_tla_bundle(rnd.tla_plus)
    if len(modules) == 1 and modules[0]["name"] == "<bundle>":
        print(rnd.tla_plus)
    else:
        for i, mod in enumerate(modules, 1):
            print(f"\n--- Module {i}/{len(modules)}: {mod['name']} ---")
            print(mod["text"])
    try:
        validate_tla(rnd.tla_plus)
        print("\n[TLA+ syntax] OK --- well-formed TLA+ bundle.")
    except Exception as e:
        print("\n[TLA+ syntax] NOT well-formed", end="")
        print(f" ({str(e).splitlines()[0]})")
    print("\n--- Natural-language summary ---")
    print(rnd.summary)

def _prompt_add_model(*, allow_exit: bool = False) -> bool | None:
    model_name_prompt = "Model Name (or /exit): " if allow_exit else "Model Name:"
    model_name = input(model_name_prompt).strip()
    if allow_exit and model_name.lower() in {"exit", "/exit", "quit"}:
        return False
    if not model_name:
        print("Model Name required!")
        return None
    model_id = input("Model ID: ").strip()
    if not model_id:
        print("Model ID required!")
        return None
    base_url = input("Base URL (optional): ").strip()
    api_key = input("API Key (optional, default=local): ").strip() or "local"
    temp = input("Temperature (optional, default=0.0): ").strip()
    max_tokens = input("Max Tokens (optional, default=50000)").strip()
    config = {
        "model_id": model_id,
        "base_url": base_url,
        "api_key": api_key
    }
    try:
        if temp:
            config["temperature"] = float(temp)
        if max_tokens:
            config["max_tokens"] = int(max_tokens)
    except ValueError:
        print("Temperature must be a number and max tokens must be an integer")
        return None
    path = add_user_model(model_name, config)
    if path:
        print(f"Added model '{model_name}' to '{path}'")
        return True
    print("Failed to add model!")
    return None

def main() -> None:
    print("=" * 70)
    print(" RTL2TLA: system requirements -> TLA+ -> natural-language summary")
    print(" Use /exit to quit")
    print("=" * 70)

    # Model selection
    models = load_all()
    if not models:
        print("\nNo models are configured. Add one now to continue...")
        while not models:
            added = _prompt_add_model(allow_exit=True)
            if added is False:
                print("No model was added. Exiting...")
                return
            if added:
                models = load_all()
    default_cfg = get_config_or(None)
    print("\nAvailable models:")
    for i, cfg in enumerate(models, 1):
        marker = "*" if cfg.name == default_cfg.name else " "
        print(f" [{marker}] {i}. {cfg.name}  --  {cfg.model_id}")
    sel = input("\nSelect model [number or name] (Enter for default): ").strip()
    if sel:
        cfg = None
        # try number
        try:
            idx = int(sel) - 1
            if 0 <= idx < len(models):
                cfg = models[idx]
        except ValueError:
            pass
        # try name
        if not cfg:
            try:
                cfg = get_config_or(sel)
            except KeyError:
                print(f"Unknown model '{sel}'. Using default.")
                cfg = default_cfg
    else:
        cfg = default_cfg

    programs = build_programs(cfg)
    print(f"\nUsing model: {programs['cfg'].name} ({programs['cfg'].model_id})")

    print("\nPlease enter your requirements (one or more lines):")
    print("(Press Enter on an empty line to finish the input block. Leave empty to resume existing session.)")

    lines = []
    while True:
        try:
            line = input()
        except EOFError:
            line = ""
        if line == "":
            break
        lines.append(line)

    requirements = "\n".join(lines).strip()
    session = build_session(requirements)

    if session.rounds and not requirements:
        last = session.rounds[-1]
        print("\n[Resumed session]")
        print(f"  original requirements: {session.original_requirements[:80]}...")
        print(f"  clarifications ({len(session.clarifications)}): {session.clarifications}")
        _print_round(last)
    else:
        prompt = session.build_prompt()
        tla_plus = generate_tla(programs, prompt)
        with dspy.context(lm=programs["lm"]):
            summary = str(programs["tla2req"](spec=tla_plus).summary)
        rnd = session.archive_round(prompt, tla_plus, summary, now_iso())
        session.save()
        _print_round(rnd)

    print("\n--- Now you can disambiguate: ---\n"
          "Commands: /exit, /model <name|#>, /list models, /add model, /show model\n"
          "Type clarifications to refine.")

    while True:
        clar = input("> ")
        if not clar:
            continue
        cmd = clar.strip().lower()
        if cmd in {"/exit", "exit", "quit"}:
            break
        if cmd.startswith("/model "):
            arg = clar[7:].strip()
            models = load_all()
            cfg = None
            try:
                idx = int(arg) - 1
                if 0 <= idx < len(models):
                    cfg = models[idx]
            except ValueError:
                pass
            if not cfg:
                try:
                    cfg = get_config_or(arg)
                except KeyError:
                    print(f"Unknown model '{arg}'. Available:")
                    for c in models:
                        print(f"  - {c.name}")
                    continue
            programs = build_programs(cfg)
            print(f"Switched model to {programs['cfg'].name} ({programs['cfg'].model_id})")
            continue
        if cmd == "/list models":
            models = load_all()
            print("Available models:")
            for c in models:
                cur = "*" if c.name == programs['cfg'].name else " "
                print(f" [{cur}] {c.name}  --  {c.model_id}")
            continue
        if cmd == "/show model":
            c = programs['cfg']
            print(f"Current model: {c.name}")
            print(f"  model_id: {c.model_id}")
            print(f"  base_url: {c.base_url or '(default)'}")
            print(f"  api_key: {mask_secret(c.api_key)}")
            print(f"  temperature: {c.temperature}")
            print(f"  max_tokens: {c.max_tokens}")
            continue
        if cmd == "/add model":
            _prompt_add_model()
            continue
        # treat as clarification
        session.add_clarification(clar)
        prompt = session.build_prompt()
        tla_plus = generate_tla(programs, prompt)
        with dspy.context(lm=programs["lm"]):
            summary = str(programs["tla2req"](spec=tla_plus).summary)
        rnd = session.archive_round(prompt, tla_plus, summary, now_iso())
        session.save()
        _print_round(rnd)


if __name__ == "__main__":
    main()
