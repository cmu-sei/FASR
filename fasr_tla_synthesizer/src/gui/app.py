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

import gradio as gr
from gradio.themes import Soft

from ..pipeline import (
    build_programs,
    build_session,
    generate_tla,
    now_iso,
    Session,
    Round,
)
from ..models import load_all, get_config_or, add_user_model, mask_secret
from ..tla_validator import split_tla_bundle, validate_tla
from .state import list_sessions, delete_session, load_session_by_path


def _capture_generate(programs, requirements, progress=gr.Progress()):
    log_lines = []
    import builtins
    orig_print = builtins.print
    counter = [0]

    def log_print(*args, **kwargs):
        line = " ".join(str(a) for a in args)
        log_lines.append(line)
        counter[0] += 1
        # advance progress a little on each print, cap at 0.9
        try:
            progress(min(0.9, 0.05 + counter[0] * 0.005), desc=line[:120])
        except Exception:
            pass
        orig_print(line, **kwargs)

    builtins.print = log_print
    try:
        progress(0.0, desc="Generating TLA+")
        tla_plus = generate_tla(programs, requirements)
        summary_obj = programs["tla2req"](spec=tla_plus)
        summary = str(summary_obj.summary)
    finally:
        builtins.print = orig_print
    progress(1.0, desc="Done")
    full_log = "\n".join(log_lines)
    return tla_plus, summary, full_log


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
    out.append("\n--- Natural-language summary ---\n")
    out.append(rnd.summary)
    return "\n".join(out)


def launch():
    models = load_all()
    default_cfg = get_config_or(None)
    model_choices = {c.name: c.name for c in models}
    programs = build_programs(default_cfg)
    # Initial empty session
    init_session = Session(original_requirements="", clarifications=[])

    with gr.Blocks(title="RTL2TLA") as demo:
        session_state = gr.State(init_session)
        programs_state = gr.State(programs)
        logs_state = gr.State("")
        active_path_state = gr.State(None)
        pending_delete_state = gr.State(None)

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
                model_dropdown = gr.Dropdown(label="Model", choices=list(model_choices.keys()), value=default_cfg.name, interactive=True)
                model_info = gr.Markdown(f"**Current:** {programs['cfg'].name}\n`{programs['cfg'].model_id}`")
                with gr.Row():
                    use_model_btn = gr.Button("Use Model")
                    add_model_btn = gr.Button("Add Model")
                with gr.Accordion("Add / Edit Model", open=False) as model_acc:
                    new_model_name = gr.Textbox(label="Name")
                    new_model_id = gr.Textbox(label="model_id")
                    new_model_base = gr.Textbox(label="base_url (optional)")
                    new_model_api = gr.Textbox(label="api_key (optional)", placeholder="local or env:VAR")
                    new_model_temp = gr.Number(label="temperature", value=0.0)
                    new_model_tokens = gr.Number(label="max_tokens", value=50000)
                    save_model_btn = gr.Button("Save Model")
                add_model_btn.click(lambda: gr.update(open=True), outputs=[model_acc])
                req_box = gr.Textbox(label="Requirements", lines=8, value="")
                clar_box = gr.Textbox(label="Add clarification", lines=2, placeholder="Type clarification and press Enter")
                with gr.Row():
                    generate_btn = gr.Button("Generate", variant="primary")
                    clear_logs_btn = gr.Button("Clear log")
                log_box = gr.Textbox(label="Log", lines=10)
                module_accordions = []
                module_codes = []
                with gr.Tabs():
                    with gr.TabItem("TLA+"):
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
        def use_model(name):
            cfg = get_config_or(name)
            progs = build_programs(cfg)
            info = f"**Current:** {progs['cfg'].name}\n`{progs['cfg'].model_id}`"
            return progs, gr.update(value=info)

        use_model_btn.click(use_model, inputs=[model_dropdown], outputs=[programs_state, model_info])

        def add_model(name, model_id, base_url, api_key, temp, tokens):
            if not name or not model_id:
                return "Please provide name and model_id", None, gr.update()
            cfg = {"model_id": model_id}
            if base_url:
                cfg["base_url"] = base_url
            if api_key:
                cfg["api_key"] = api_key
            else:
                cfg["api_key"] = "local"
            if temp is not None:
                cfg["temperature"] = float(temp)
            if tokens is not None:
                cfg["max_tokens"] = int(tokens)
            path = add_user_model(name, cfg)
            if path:
                # refresh model dropdown choices
                models = load_all()
                choices = [c.name for c in models]
                return f"Added model '{name}'.", gr.update(choices=choices, value=name), gr.update()
            else:
                return "Failed to add model.", None, gr.update()

        save_model_btn.click(add_model, inputs=[new_model_name, new_model_id, new_model_base, new_model_api, new_model_temp, new_model_tokens], outputs=[model_info, model_dropdown, model_acc])

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
                        code_updates.append(gr.update(value=mods[i]["text"]))
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

        def generate_fn(requirements, clarifications, session_obj, progress=gr.Progress()):
            progress(0, desc="Generating TLA+")
            sess = session_obj
            sess.original_requirements = requirements.strip()
            for line in clarifications.splitlines():
                line = line.strip()
                if line:
                    sess.add_clarification(line)
            prompt = sess.build_prompt()
            tla_plus, summary, log = _capture_generate(programs_state.value, prompt, progress)
            rnd = sess.archive_round(prompt, tla_plus, summary, now_iso())
            sess.save()
            hist = "\n\n".join(_format_round(sess, i) for i in range(len(sess.rounds)))
            # Split into modules for per-module accordions
            from ..tla_validator import split_tla_bundle
            mods = split_tla_bundle(tla_plus)
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
                        code_updates.append(gr.update(value=mods[i]["text"]))
                    else:
                        acc_updates.append(gr.update(open=False, visible=False))
                        code_updates.append(gr.update(value="", visible=False))
                updates = acc_updates + code_updates
            # Append summary, history, log, session
            return tuple(updates + [
                gr.update(value=summary),
                gr.update(value=hist),
                gr.update(value=log),
                sess,
            ])

        generate_btn.click(generate_fn, inputs=[req_box, clar_box, session_state], outputs=[*[a for acc in module_accordions for a in [acc]] + [*module_codes], summary_box, history_box, log_box, session_state])

        clear_logs_btn.click(lambda: "", outputs=[log_box])

        demo.load(refresh_sessions, outputs=[session_dropdown])

    demo.launch(theme=Soft())


if __name__ == "__main__":
    launch()
