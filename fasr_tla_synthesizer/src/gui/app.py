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

from concurrent.futures import ThreadPoolExecutor
import threading
import time
import uuid

import gradio as gr
from gradio.themes import Soft

import dspy

from ..pipeline import (
    build_programs,
    build_session,
    generate_tla,
    now_iso,
    Session,
    Round,
    MAX_TLA_ATTEMPTS,
    GenerationCancelled,
    VerificationIncomplete,
)
from ..models import (
    load_all,
    load_user_models,
    get_config_or,
    add_user_model,
    remove_user_model,
    mask_secret,
)
from ..tla_validator import split_tla_bundle, validate_tla
from .state import list_sessions, delete_session, load_session_by_path


_CANCEL_EVENTS: dict[str, threading.Event] = {}
_CANCEL_EVENTS_LOCK = threading.Lock()


def _new_cancel_event() -> tuple[str, threading.Event]:
    generation_id = uuid.uuid4().hex
    event = threading.Event()
    with _CANCEL_EVENTS_LOCK:
        _CANCEL_EVENTS[generation_id] = event
    return generation_id, event


def _get_cancel_event(generation_id: str) -> threading.Event:
    with _CANCEL_EVENTS_LOCK:
        return _CANCEL_EVENTS.setdefault(generation_id, threading.Event())


def _cancel_generation(generation_id: str | None) -> None:
    if not generation_id:
        return
    with _CANCEL_EVENTS_LOCK:
        event = _CANCEL_EVENTS.get(generation_id)
    if event:
        event.set()


def _forget_generation(generation_id: str | None) -> None:
    if not generation_id:
        return
    with _CANCEL_EVENTS_LOCK:
        _CANCEL_EVENTS.pop(generation_id, None)


def _elapsed_label(seconds: float) -> str:
    minutes, seconds = divmod(int(seconds), 60)
    return f"{minutes:02d}:{seconds:02d}"


def _capture_generate(programs, requirements, cancel_event, started):
    log_lines = []
    status = {
        "attempt": 1,
        "phase": "starting",
        "message": "Preparing the model request.",
        "phase_started": time.monotonic(),
    }
    status_lock = threading.Lock()
    log_lines.append(
        f"[00:00] Attempt 1/{MAX_TLA_ATTEMPTS}: Preparing the model request."
    )

    def update_status(attempt, phase, message):
        now = time.monotonic()
        with status_lock:
            if attempt != status["attempt"] or phase != status["phase"]:
                status["phase_started"] = now
            status.update(attempt=attempt, phase=phase, message=message)
            log_lines.append(
                f"[{_elapsed_label(now - started)}] "
                f"Attempt {attempt}/{MAX_TLA_ATTEMPTS}: {message}"
            )

    def log_snapshot():
        with status_lock:
            return "\n".join(log_lines)

    def status_snapshot():
        with status_lock:
            current = dict(status)
        now = time.monotonic()
        return (
            f"Attempt {current['attempt']}/{MAX_TLA_ATTEMPTS} — "
            f"{current['phase']}: {current['message']} "
            f"({_elapsed_label(now - current['phase_started'])} this step; "
            f"{_elapsed_label(now - started)} total elapsed)"
        )

    def run_generation():
        import builtins
        orig_print = builtins.print

        def log_print(*args, **kwargs):
            line = " ".join(str(a) for a in args)
            with status_lock:
                log_lines.append(line)
            orig_print(line, **kwargs)

        builtins.print = log_print
        try:
            tla_plus = generate_tla(
                programs,
                requirements,
                status_callback=update_status,
                cancelled=cancel_event.is_set,
            )
            if cancel_event.is_set():
                raise GenerationCancelled("Generation cancelled.")
            with status_lock:
                attempt = status["attempt"]
            update_status(
                attempt,
                "summary",
                "Requesting the natural-language summary from the model.",
            )
            summary_started = time.monotonic()
            try:
                with dspy.context(lm=programs["lm"]):
                    summary = str(programs["tla2req"](
                        spec=tla_plus.tla_plus, tlc_config=tla_plus.tlc_config
                    ).summary)
            except Exception as exc:
                detail = str(exc).splitlines()[0]
                update_status(
                    attempt,
                    "error",
                    f"Summary request failed: {detail}",
                )
                raise
            summary_elapsed = time.monotonic() - summary_started
            update_status(
                attempt,
                "summary_response",
                f"Summary response received after {summary_elapsed:.1f}s. "
                "Preparing the completed result.",
            )
            return tla_plus, summary
        finally:
            builtins.print = orig_print

    executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="rtl2tla-model")
    future = executor.submit(run_generation)
    try:
        while not future.done():
            yield None, None, log_snapshot(), status_snapshot()
            time.sleep(1)
        # Publish the last status transition before surfacing either the result
        # or an exception raised by the worker.
        yield None, None, log_snapshot(), status_snapshot()
        tla_plus, summary = future.result()
    finally:
        cancel_event.set()
        executor.shutdown(wait=False, cancel_futures=True)
    yield tla_plus, summary, log_snapshot(), status_snapshot()


def _format_round(session, rnd_idx):
    rnd = session.rounds[rnd_idx]
    modules = split_tla_bundle(rnd.tla_plus)
    out = []
    out.append(f"--- Round {rnd.index} ---")
    out.append(f"Timestamp: {rnd.timestamp}")
    if len(modules) == 1 and modules[0]["name"] == "<bundle>":
        out.append(rnd.tla_plus)
    else:
        for i, mod in enumerate(modules, 1):
            out.append(f"\n--- Module {i}/{len(modules)}: {mod['name']} ---\n{mod['text']}")
    try:
        validate_tla(rnd.tla_plus)
        out.append("\n[TLA+ syntax] OK --- well-formed TLA+ bundle.")
    except Exception as e:
        out.append(f"\n[TLA+ syntax] NOT well-formed ({str(e).splitlines()[0]})")
    if rnd.tlc_config:
        out.append(f"\n--- TLC configuration ---\n{rnd.tlc_config}")
    out.extend(f"[Validation] {warning}" for warning in rnd.semantic_warnings)
    out.append("\n--- Natural-language summary ---\n")
    out.append(rnd.summary)
    return "\n".join(out)


def launch():
    models = load_all()
    model_choices = {c.name: c.name for c in models}
    default_cfg = models[0] if models else None
    programs = build_programs(default_cfg, warm=False) if default_cfg else None
    has_model = programs is not None
    model_status = (
        f"**Current**: {programs['cfg'].name}\n`{programs['cfg'].model_id}`"
        if programs
        else "**No model configured** Add a model below to get started!"
    )
    # Initial empty session
    init_session = Session(original_requirements="", clarifications=[])

    with gr.Blocks(title="RTL2TLA") as demo:
        session_state = gr.State(init_session)
        programs_state = gr.State(programs)
        logs_state = gr.State("")
        active_path_state = gr.State(None)
        pending_delete_state = gr.State(None)
        editing_model_state = gr.State(None)
        generation_id_state = gr.State(None)
        generation_started_state = gr.State(None)

        with gr.Row():
            with gr.Column(scale=1, visible=True) as sidebar:
                gr.Markdown("### Sessions")
                session_dropdown = gr.Dropdown(label="Saved sessions", choices=[], interactive=True)
                refresh_btn = gr.Button("Refresh")
                load_btn = gr.Button("Load")
                del_btn = gr.Button("Delete", variant="stop")
                confirm_row = gr.Row(visible=False)
                confirm_label = gr.Textbox(label="Confirm delete", visible=False)
                confirm_yes = gr.Button("Yes", variant="stop", visible=False)
                confirm_no = gr.Button("No", visible=False)
                with gr.Accordion("New Session", open=False):
                    new_req = gr.Textbox(label="Initial requirements", lines=4)
                    new_session_btn = gr.Button("Create")

            with gr.Column(scale=3):
                gr.Markdown("# RTL2TLA")
                # Model selector
                model_dropdown = gr.Dropdown(
                    label="Model",
                    choices=list(model_choices.keys()),
                    value=default_cfg.name if default_cfg else None,
                    interactive=True
                )
                model_info = gr.Markdown(model_status)
                with gr.Row():
                    use_model_btn = gr.Button("Use Model", interactive=has_model)
                    add_model_btn = gr.Button("Add Model")
                    edit_model_btn = gr.Button("Edit Model", interactive=has_model)
                with gr.Accordion("Add / Edit Model", open=not has_model) as model_acc:
                    new_model_name = gr.Textbox(label="Name")
                    new_model_id = gr.Textbox(label="model_id")
                    new_model_base = gr.Textbox(label="base_url (optional)")
                    new_model_api = gr.Textbox(
                        label="api_key (optional)",
                        placeholder="local, env:VAR, or leave blank while editing",
                    )
                    new_model_temp = gr.Number(label="temperature", value=0.0)
                    new_model_tokens = gr.Number(label="max_tokens", value=50000)
                    new_model_timeout = gr.Number(label="request timeout (seconds)", value=120)
                    new_model_retries = gr.Number(label="request retries", value=1)
                    save_model_btn = gr.Button("Save Model")
                req_box = gr.Textbox(label="Requirements", lines=8, value="")
                clar_box = gr.Textbox(label="Add clarification", lines=2, placeholder="Type clarification and press Enter")
                with gr.Row():
                    generate_btn = gr.Button("Generate", variant="primary", interactive=has_model)
                    cancel_btn = gr.Button("Cancel", variant="stop", interactive=False)
                    clear_logs_btn = gr.Button("Clear log")
                generation_status = gr.Textbox(
                    label="Generation status", value="Ready", interactive=False, lines=2
                )
                log_box = gr.Textbox(label="Log", lines=10)
                module_accordions = []
                module_codes = []
                with gr.Tabs():
                    with gr.TabItem("TLA+ and TLC configuration"):
                        for i in range(5):
                            with gr.Accordion(label=f"Module {i+1}", open=False) as acc:
                                code = gr.Code(language="markdown", label="")
                                module_accordions.append(acc)
                                module_codes.append(code)
                    with gr.TabItem("Summary"):
                        summary_box = gr.Textbox(label="Summary")
                    with gr.TabItem("History"):
                        history_box = gr.Textbox(label="Round history", lines=20)

        # Helpers
        def refresh_sessions():
            sess = list_sessions()
            choices = {s["project_name"]: s["path"] for s in sess}
            return gr.update(choices=list(choices.keys()), value=None)

        refresh_btn.click(refresh_sessions, outputs=[session_dropdown])

        # Model handlers
        model_form_outputs = [
            model_acc,
            new_model_name,
            new_model_id,
            new_model_base,
            new_model_api,
            new_model_temp,
            new_model_tokens,
            new_model_timeout,
            new_model_retries,
            editing_model_state,
        ]

        def start_add_model():
            return (
                gr.update(label="Add Model", open=True),
                "",
                "",
                "",
                gr.update(value="", placeholder="local or env:VAR"),
                0.0,
                50000,
                120,
                1,
                None,
            )

        add_model_btn.click(start_add_model, outputs=model_form_outputs)

        def start_edit_model(name):
            if not name:
                raise gr.Error("Select a model before editing it.")
            cfg = get_config_or(name)
            if cfg.api_key == "local":
                key_description = "local"
            elif cfg.api_key.startswith("env:"):
                key_description = mask_secret(cfg.api_key)
            else:
                key_description = "the existing key"
            return (
                gr.update(label=f"Edit Model: {cfg.name}", open=True),
                cfg.name,
                cfg.model_id,
                cfg.base_url,
                gr.update(
                    value="",
                    placeholder=f"Leave blank to keep {key_description}",
                ),
                cfg.temperature,
                cfg.max_tokens,
                cfg.timeout,
                cfg.num_retries,
                cfg.name,
            )

        edit_model_btn.click(
            start_edit_model,
            inputs=[model_dropdown],
            outputs=model_form_outputs,
        )

        def use_model(name):
            if not name:
                return None, "**No model selected!** Add or select a model first."
            cfg = get_config_or(name)
            progs = build_programs(cfg, warm=False)
            info = f"**Current:** {progs['cfg'].name}\n`{progs['cfg'].model_id}`"
            return progs, info

        use_model_btn.click(use_model, inputs=[model_dropdown], outputs=[programs_state, model_info])

        def add_model(
            original_name,
            name,
            model_id,
            base_url,
            api_key,
            temp,
            tokens,
            timeout,
            retries,
        ):
            save_outputs = tuple(gr.update() for _ in range(8))
            name = (name or "").strip()
            model_id = (model_id or "").strip()
            if not name or not model_id:
                return ("Please provide name and model_id", *save_outputs[1:])

            stored_models = load_user_models()
            existing = stored_models.get(original_name) if original_name else None
            if original_name and existing is None:
                return (f"Model '{original_name}' no longer exists.", *save_outputs[1:])
            if name in stored_models and name != original_name:
                return (f"Model '{name}' already exists. Edit it instead.", *save_outputs[1:])

            try:
                timeout_value = float(timeout) if timeout is not None else 120.0
                retries_value = int(retries) if retries is not None else 1
                if timeout_value <= 0:
                    raise ValueError("request timeout must be greater than zero")
                if retries_value < 0:
                    raise ValueError("request retries cannot be negative")
            except (TypeError, ValueError) as exc:
                return (f"Invalid request settings: {exc}", *save_outputs[1:])

            cfg = dict(existing or {})
            cfg.update({
                "model_id": model_id,
                "base_url": (base_url or "").strip(),
                "temperature": float(temp) if temp is not None else 0.0,
                "max_tokens": int(tokens) if tokens is not None else 50000,
                "timeout": timeout_value,
                "num_retries": retries_value,
            })
            if api_key and api_key.strip():
                cfg["api_key"] = api_key.strip()
            elif existing is None:
                cfg["api_key"] = "local"

            path = add_user_model(name, cfg)
            if path:
                if original_name and original_name != name:
                    remove_user_model(original_name)
                models = load_all()
                choices = [c.name for c in models]
                selected = get_config_or(name)
                progs = build_programs(selected, warm=False)
                info = f"**Current:** {progs['cfg'].name}\n`{progs['cfg'].model_id}`"
                return (
                    info,
                    gr.update(choices=choices, value=name),
                    gr.update(open=False),
                    progs,
                    gr.update(interactive=True),
                    gr.update(interactive=True),
                    gr.update(interactive=True),
                    None,
                )
            return ("Failed to save model!", *save_outputs[1:])

        save_model_btn.click(
            add_model,
            inputs=[
                editing_model_state,
                new_model_name,
                new_model_id,
                new_model_base,
                new_model_api,
                new_model_temp,
                new_model_tokens,
                new_model_timeout,
                new_model_retries,
            ],
            outputs=[
                model_info,
                model_dropdown,
                model_acc,
                programs_state,
                use_model_btn,
                edit_model_btn,
                generate_btn,
                editing_model_state,
            ],
        )

        def load_session_fn(choice):
            if not choice:
                return (*[gr.update()] * 16,)
            sess_list = list_sessions()
            path = next((s["path"] for s in sess_list if s["project_name"] == choice), None)
            if not path:
                # return empty updates for 5 accordions + 5 codes + req, clar, hist, summary, sess, path
                empty = [gr.update() for _ in range(10)]
                return tuple(empty + [gr.update(), gr.update(), gr.update(), gr.update(), gr.update(), gr.update()])
            sess = load_session_by_path(path)
            if not sess:
                empty = [gr.update() for _ in range(10)]
                return tuple(empty + [gr.update(), gr.update(), gr.update(), gr.update(), gr.update(), gr.update()])
            req_text = sess.original_requirements
            clar_text = ""
            hist = ""
            latest_summary = ""
            latest_tla = ""
            if sess.rounds:
                hist = "\n\n".join(_format_round(sess, i) for i in range(len(sess.rounds)))
                latest = sess.rounds[-1]
                latest_tla = latest.tla_plus
                latest_summary = latest.summary
            from ..tla_validator import split_tla_bundle
            mods = split_tla_bundle(latest_tla) if latest_tla else []
            if sess.rounds and sess.rounds[-1].tlc_config and mods:
                mods.append({"name": f"{mods[-1]['name']}.cfg", "text": sess.rounds[-1].tlc_config})
            updates = []
            if not mods or (len(mods) == 1 and mods[0]["name"] == "<bundle>"):
                acc_updates = []
                code_updates = []
                for i in range(5):
                    if i == 0:
                        acc_updates.append(gr.update(label="Error", open=True, visible=True))
                        code_updates.append(gr.update(value="Error loading modules!"))
                    else:
                        acc_updates.append(gr.update(open=False, visible=False))
                        code_updates.append(gr.update(value="", visible=False))
                updates = acc_updates + code_updates
            else:
                acc_updates = []
                code_updates = []
                for i in range(5):
                    if i < len(mods):
                        acc_updates.append(gr.update(label=mods[i]["name"], open=(i==0), visible=True))
                        code_updates.append(gr.update(value=mods[i]["text"], visible=True))
                    else:
                        acc_updates.append(gr.update(open=False, visible=False))
                        code_updates.append(gr.update(value="", visible=False))
                updates = acc_updates + code_updates
            return tuple(updates + [
                gr.update(value=req_text),
                gr.update(value=clar_text),
                gr.update(value=hist),
                gr.update(value=latest_summary),
                sess,
                gr.update(value=path),
            ])

        session_dropdown.change(load_session_fn, inputs=[session_dropdown], outputs=[*module_accordions, *module_codes, req_box, clar_box, history_box, summary_box, session_state, active_path_state])
        load_btn.click(load_session_fn, inputs=[session_dropdown], outputs=[*module_accordions, *module_codes, req_box, clar_box, history_box, summary_box, session_state, active_path_state])

        def request_delete(choice):
            if not choice:
                return gr.update(visible=False), gr.update(visible=False), gr.update(visible=False), gr.update(visible=False), gr.update()
            sess_list = list_sessions()
            path = next((s["path"] for s in sess_list if s["project_name"] == choice), None)
            if not path:
                return gr.update(visible=False), gr.update(visible=False), gr.update(visible=False), gr.update(visible=False), gr.update()
            return (
                gr.update(visible=True),
                gr.update(visible=True, value=f"Delete session '{choice}'?"),
                gr.update(visible=True),
                gr.update(visible=True),
                gr.State(path),
            )

        del_btn.click(request_delete, inputs=[session_dropdown], outputs=[confirm_row, confirm_label, confirm_yes, confirm_no, pending_delete_state])

        def cancel_delete():
            return gr.update(visible=False), gr.update(visible=False), gr.update(visible=False), gr.update(visible=False), gr.State(None)

        confirm_no.click(cancel_delete, outputs=[confirm_row, confirm_label, confirm_yes, confirm_no, pending_delete_state])

        def confirm_delete_action(pending_path, active_path):
            if not pending_path:
                return gr.update(), gr.update(), gr.update(), gr.update(), gr.update(), gr.update()
            ok = delete_session(pending_path)
            # Refresh dropdown choices
            sess = list_sessions()
            choices = {s["project_name"]: s["path"] for s in sess}
            new_active = None
            if active_path and active_path == pending_path:
                # clear UI
                new_req = ""
                new_hist = ""
                new_session = Session(original_requirements="", clarifications=[])
                new_active = None
            else:
                new_req = gr.update()
                new_hist = gr.update()
                new_session = gr.update()
            return (
                gr.update(choices=list(choices.keys()), value=None),
                gr.update(visible=False),
                gr.update(visible=False),
                gr.update(visible=False),
                gr.update(visible=False),
                gr.State(new_session),
            )

        # Simplified: we will handle deletion in a single function
        def do_delete(pending_path, active_path):
            if not pending_path:
                return refresh_sessions()[0], gr.update(), gr.update(), gr.update(), gr.State(None)
            delete_session(pending_path)
            # reset UI if deleted session was active
            if active_path == pending_path:
                return (
                    refresh_sessions()[0],
                    gr.update(value=""),
                    gr.update(value=""),
                    gr.State(Session(original_requirements="", clarifications=[])),
                    gr.State(None),
                )
            else:
                return refresh_sessions()[0], gr.update(), gr.update(), gr.update(), gr.State(None)

        confirm_yes.click(do_delete, inputs=[pending_delete_state, active_path_state], outputs=[session_dropdown, req_box, history_box, session_state, pending_delete_state])

        def create_new_session(text):
            sess = Session(original_requirements=text.strip())
            sess.save()
            # Refresh list
            sess_list = list_sessions()
            choices = {s["project_name"]: s["path"] for s in sess_list}
            return gr.update(value=text.strip()), gr.update(value=text.strip()), gr.update(choices=list(choices.keys())), sess

        new_session_btn.click(create_new_session, inputs=[new_req], outputs=[req_box, new_req, session_dropdown, session_state])

        def begin_generation():
            generation_id, _ = _new_cancel_event()
            return (
                generation_id,
                gr.update(interactive=False),
                gr.update(interactive=True),
                "Preparing generation…",
                time.monotonic(),
            )

        def finish_generation():
            return (
                gr.update(interactive=True), gr.update(interactive=False),
            )

        def cancel_generation(generation_id, current_log):
            _cancel_generation(generation_id)
            message = (
                "Cancellation requested. The current network request may take "
                "until its timeout to stop."
            )
            current_log = (current_log or "").rstrip()
            updated_log = f"{current_log}\n{message}" if current_log else message
            return (
                gr.update(interactive=True),
                gr.update(interactive=False),
                updated_log,
                message,
            )

        def generate_fn(
            requirements,
            clarifications,
            session_obj,
            programs,
            generation_id,
            generation_started,
        ):
            last_log = None

            def log_only_update(log, status_text):
                nonlocal last_log
                log_update = gr.update(value=log) if log != last_log else gr.skip()
                last_log = log
                untouched = (
                    len(module_accordions) + len(module_codes) + 2
                )
                return tuple(
                    [gr.skip()] * untouched
                    + [log_update, gr.skip(), gr.update(value=status_text)]
                )

            def terminal_status(message):
                elapsed = _elapsed_label(time.monotonic() - generation_started)
                return f"{message} ({elapsed} total elapsed)"

            def append_step(log, message):
                elapsed = _elapsed_label(time.monotonic() - generation_started)
                line = f"[{elapsed}] {message}"
                return f"{log.rstrip()}\n{line}" if log else line

            log = ""
            try:
                if not programs:
                    raise gr.Error("Add or select a model before generating TLA+.")
                sess = session_obj
                sess.original_requirements = requirements.strip()
                for line in clarifications.splitlines():
                    line = line.strip()
                    if line:
                        sess.add_clarification(line)
                prompt = sess.build_prompt()
                cancel_event = _get_cancel_event(generation_id)
                tla_plus = None
                summary = None
                for captured_tla, captured_summary, log, status_text in _capture_generate(
                    programs, prompt, cancel_event, generation_started
                ):
                    yield log_only_update(log, status_text)
                    if captured_tla is not None:
                        tla_plus = captured_tla
                        summary = captured_summary
                if tla_plus is None or summary is None:
                    raise RuntimeError("Generation finished without a completed result.")

                log = append_step(log, "Saving the completed session.")
                yield log_only_update(log, terminal_status("Saving the completed session."))
                rnd = sess.archive_round(prompt, tla_plus, summary, now_iso())
                sess.save()
                log = append_step(log, "Session saved. Generation complete.")
                hist = "\n\n".join(_format_round(sess, i) for i in range(len(sess.rounds)))
                # Split into modules for per-module accordions
                from ..tla_validator import split_tla_bundle
                mods = split_tla_bundle(rnd.tla_plus)
                if rnd.tlc_config and mods:
                    mods.append({"name": f"{mods[-1]['name']}.cfg", "text": rnd.tlc_config})
                updates = []
                if not mods or (len(mods) == 1 and mods[0]["name"] == "<bundle>"):
                    acc_updates = []
                    code_updates = []
                    for i in range(5):
                        if i == 0:
                            acc_updates.append(gr.update(label="Error", open=True))
                            code_updates.append(gr.update(value="Error loading modules!"))
                        else:
                            acc_updates.append(gr.update(open=False, visible=False))
                            code_updates.append(gr.update(value="", visible=False))
                    updates = acc_updates + code_updates
                else:
                    acc_updates = []
                    code_updates = []
                    for i in range(5):
                        if i < len(mods):
                            acc_updates.append(gr.update(label=mods[i]["name"], open=(i==0), visible=True))
                            code_updates.append(gr.update(value=mods[i]["text"], visible=True))
                        else:
                            acc_updates.append(gr.update(open=False, visible=False))
                            code_updates.append(gr.update(value="", visible=False))
                    updates = acc_updates + code_updates
                # Append summary, history, log, session, and persistent status.
                yield tuple(updates + [
                    gr.update(value=summary),
                    gr.update(value=hist),
                    gr.update(value=log),
                    sess,
                    gr.update(value=terminal_status("Generation complete.")),
                ])
            except GenerationCancelled as exc:
                yield log_only_update(log, terminal_status("Generation cancelled."))
                raise gr.Error(str(exc)) from exc
            except VerificationIncomplete as exc:
                yield log_only_update(log, terminal_status("Verification incomplete. The result was not accepted."))
                raise gr.Error(str(exc)) from exc
            except Exception:
                yield log_only_update(log, terminal_status("Generation failed. See error details."))
                raise
            finally:
                _forget_generation(generation_id)

        begin_event = generate_btn.click(
            begin_generation,
            outputs=[
                generation_id_state, generate_btn, cancel_btn, generation_status,
                generation_started_state,
            ],
            queue=False,
            show_progress="hidden",
        )
        generation_event = begin_event.then(
            generate_fn,
            inputs=[
                req_box,
                clar_box,
                session_state,
                programs_state,
                generation_id_state,
                generation_started_state,
            ],
            outputs=[
                *module_accordions,
                *module_codes,
                summary_box,
                history_box,
                log_box,
                session_state,
                generation_status,
            ],
            show_progress="minimal",
            show_progress_on=[log_box],
        )
        generation_event.then(
            finish_generation,
            outputs=[generate_btn, cancel_btn],
            queue=False,
            show_progress="hidden",
        )
        cancel_btn.click(
            cancel_generation,
            inputs=[generation_id_state, log_box],
            outputs=[
                generate_btn, cancel_btn, log_box, generation_status,
            ],
            cancels=[generation_event],
            queue=False,
            show_progress="hidden",
        )

        clear_logs_btn.click(lambda: "", outputs=[log_box])

        demo.load(refresh_sessions, outputs=[session_dropdown])

    demo.launch(theme=Soft())


if __name__ == "__main__":
    launch()
