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
# create and sync the environment
uv venv
source .venv/bin/activate
uv sync
```

The TLA+ Toolbox jar `tla2tools.jar` is not committed. Download it from the
TLA+ Toolbox releases and place it at the repo root, or set `TLA2TOOLS_JAR`.

---

## Running

CLI:
```bash
# make sure the venv is activated
python app.py
```

GUI (Gradio):
```bash
python app_gui.py
```

---

## Using it

1. On first run, you'll need to add a model.
2. Type your requirements, one per line.
3. Press **Enter on an empty line** to finish.
4. Read the TLA+ and the natural-language summary.
5. Type clarifications to refine, or use commands to change model / exit.

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
