# RTL2TLA

Turn **natural-language system requirements into a TLA+ specification**, then
read it back in plain language so you can catch anything the model
misunderstood — and tell it to fix it in one iteration.

```
your requirements ──→  TLA+ spec  ──→  natural-language summary
                         (review)               ↑
    you describe what to clarify ──────────────┘
```

## How it works

1. You enter your requirements (one per line).
2. It asks an LLM to write a **TLA+** spec from them.
3. It reads that TLA+ spec back and summarizes what it wrote in plain English.
4. It shows you both blocks — the raw TLA+ and the English summary.

If the summary looks wrong or omits something you meant, describe the
clarification; the program re-runs and shows you the **new** TLA+ and summary.
Repeat until it's right.

---

## Requirements

- **Python 3.12**
- **Java 17+** – required for TLA+ syntax validation via SANY
  - tla2tools.jar - used for syntax validation

### TLA+ syntax validation

Syntax validation is performed by the official TLA+ Toolbox SANY parser via
`tla2tools.jar`. The Python `tla` package is no longer used for validation.

Download `tla2tools.jar` from the TLA+ Toolbox releases and place it at the
repo root, or point `TLA2TOOLS_JAR` to your copy. The validator writes each
module from the generated bundle to a temporary file named `<ModuleName>.tla`
and invokes:

```
java -cp tla2tools.jar tla2sany.SANY -s -error-codes <files>
```

`-s` keeps validation syntax-only to match the previous `tla.parse` behaviour.
The jar path defaults to `tla2tools.jar` in the repo root and can be overridden
with the `TLA2TOOLS_JAR` environment variable.

---

## Installation

This project uses **uv** for fast dependency management.

```bash
# Create and sync the environment
uv venv
uv sync
```

Activate it on Linux or macOS:

```bash
source .venv/bin/activate
```

Activate it in Windows PowerShell:

```powershell
.\.venv\Scripts\Activate.ps1
```

Or in Windows Command Prompt:

```bat
.venv\Scripts\activate.bat
```

The TLA+ Toolbox jar `tla2tools.jar` is not committed. Download it from the
TLA+ Toolbox releases and place it at the repo root, or set `TLA2TOOLS_JAR`.

---

## Running

### Generated TLC configuration

Generation returns both the three-module TLA+ bundle and a separate `tlc_config`
output. The configuration selects `Spec`, enables `TypeOK` and `Safety` as
invariants, and supplies required constant assignments. The LLM is instructed to
focus only on safety invariants, omitting liveness, eventual-progress, and fairness
requirements even when present in the input. It must not emit `PROPERTY` or
`PROPERTIES` entries or convert liveness requirements into invariants. The behavior
specification still uses `Spec == Init /\ [][Next]_vars`.
The summary model receives both outputs. The CLI and GUI display the configuration,
and sessions save it with each round; older sessions without a configuration still load.

The validator writes the generated configuration as `<CompositionModule>.cfg` and
passes it to TLC explicitly. Missing configurations or required invariant entries
are rejected and retried. Deadlock checking is enabled.

Validation runs **TLC model checking**, with deadlock checking and the generated
configuration's checks enabled. Generated configurations are intended to check
safety invariants only; a successful result does not establish liveness or eventual
progress. The validator executes the supplied configuration as written. Acceptance requires
TLC to report successful completion. A counterexample rejects the candidate and
triggers a generation retry. The default 30-second timeout is reported as
**verification incomplete**, stops generation, and never counts as a pass or
automatically asks the model to weaken the specification. Infinite or large state
spaces may require a reviewed finite abstraction or additional checking time.
A completed check establishes only the configured properties of the supplied
model; correspondence to the original requirements still needs review.

Retries receive the complete previous TLA+ bundle, its TLC configuration, and
the full SANY/TLC diagnostics, including counterexample states. Earlier attempts
are included only as short error summaries so their line numbers are not confused
with the current candidate. The UI continues to show concise error messages.
Rejected and incomplete attempts are saved under
`<sessions directory>/validation_attempts/<run id>/attempt-<number>/` as
`bundle.tla`, `model.cfg`, `diagnostics.log`, and `attempt.json`. The log reports
that location. Timeout artifacts are retained, but timeouts never trigger an LLM retry.

CLI:
```bash
# make sure the venv is activated
python app.py
```

GUI (Gradio):
```bash
python app_gui.py
```

### Application data

Models and sessions are stored in a per-user, writable application data
directory. On Windows this defaults to `%LOCALAPPDATA%\RTL2TLA`; on Linux it
uses `$XDG_DATA_HOME/rtl2tla` or `~/.local/share/rtl2tla`, and on macOS it uses
`~/Library/Application Support/RTL2TLA`.

Set `RTL2TLA_DATA_DIR` to override the complete application data directory,
`RTL2TLA_MODELS_STORE` to override only the model JSON file, or
`RTL2TLA_SESSIONS_DIR` to override only the session directory. Existing model
and session data in the repository-local legacy locations remains readable.

---

## Using it

1. On first run, add a model using the setup form in the GUI or the startup
   prompt in the CLI.
2. Type your requirements, one per line.
3. Press **Enter on an empty line** to finish.
4. Read the TLA+ and the natural-language summary.
5. Type clarifications to refine, or use commands to change model / exit.

The GUI reports the active attempt, current phase, and elapsed time while a
model request is running. The log updates live when a response arrives and as
the application extracts, validates, summarizes, and saves the result. Use
**Cancel** to stop queued retries; an in-flight network request may take until
its configured timeout to return.

Use **Edit Model** to change the selected model. Leaving the API-key field
blank while editing preserves the existing value. Model requests default to a
120-second timeout and one transport retry; both settings are configurable in
the model form.

---

## Commands

| Input | Effect |
|-------|--------|
| *(blank line)* | Finish entering the initial requirements |
| anything else | Clarify & re-run with the updated spec |
| `/exit` / `quit` | Leave the program |
| `/model <name|#>` | Switch active LLM model |
| `/list models` | Show available models |
| `/show model` | Show current model config (api_key masked) |
| `/add model` | Interactively register a new model |


---

## Tips

- **Clarifications append** to what you already said, so the model always sees
  the full history.
- Describe only what changed — the model keeps the previous spec as its
  starting point.

---

## Files

| File | Purpose |
|------|---------|
| `app.py` | CLI entry point shim
| `app_gui.py` | Gradio GUI entry point
| `src/app.py` | CLI implementation
| `src/gui/app.py` | Gradio GUI implementation
| `src/tla_validator.py` | TLA+ syntax validation via SANY (`tla2tools.jar`); `split_tla_bundle` unchanged |
| `src/models.py` | Model catalog and secret handling
| `agents.md` | Architecture & developer notes |
| `tla2tools.jar` | TLA+ Toolbox SANY parser used for validation *(download separately, gitignored)* |

---
