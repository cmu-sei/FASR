import json
import io
import shutil
import subprocess
import tempfile
import threading
import time
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from src import pipeline, semantic
from src import tla_validator
from src.gui.state import load_session_by_path


CONFIG = "SPECIFICATION Spec\nINVARIANT TypeOK\nINVARIANT Safety\n"


def bundle(next_value="1 - e"):
    return f"""---- MODULE TestEnvironment ----
EXTENDS Integers
VARIABLE e
Init == e = 0
Next == e' = {next_value}
TypeOK == e \\in 0..1
====
---- MODULE TestMachine ----
VARIABLE m
Init == m = FALSE
====
---- MODULE TestSystem ----
EXTENDS Integers
VARIABLES e, m
vars == <<e, m>>
E == INSTANCE TestEnvironment WITH e <- e
M == INSTANCE TestMachine WITH m <- m
Init == E!Init /\\ M!Init
Next == E!Next /\\ UNCHANGED m
Spec == Init /\\ [][Next]_vars
TypeOK == E!TypeOK /\\ m \\in BOOLEAN
Safety == e >= 0
====
"""


class ConfigurationTests(unittest.TestCase):
    def test_full_tlc_diagnostics_survive_summary_truncation(self):
        trace = "Error: Deadlock reached.\n" + "state detail\n" * 100 + "State 99: final blocking state"
        with tempfile.NamedTemporaryFile() as jar, patch.object(semantic, "_jar_path", return_value=Path(jar.name)), patch.object(semantic.subprocess, "run", return_value=SimpleNamespace(returncode=1, stdout=trace, stderr="exception details")):
            result = semantic.validate(bundle(), CONFIG)
        self.assertEqual(result.diagnostics, trace + "\nexception details")

    def test_sany_error_preserves_full_output(self):
        output = "Error at line 12, column 4\n" + "source detail\n" * 100
        with patch.object(tla_validator.subprocess, "run", return_value=SimpleNamespace(returncode=1, stdout=output, stderr="parser detail")):
            with self.assertRaises(tla_validator.TLAValidationError) as caught:
                tla_validator.validate_tla(bundle())
        self.assertEqual(caught.exception.diagnostics, output + "\nparser detail")

    def test_required_entries_accept_multiline_and_comments(self):
        cfg = "SPECIFICATION Spec\nINVARIANTS\n TypeOK\n Safety \\* checked\nPROPERTY Progress\n"
        self.assertEqual(semantic.config_errors(cfg), [])

    def test_spec_only_no_longer_accepted(self):
        self.assertEqual(len(semantic.config_errors("SPECIFICATION Spec\n")), 2)

    def test_names_in_property_or_comments_do_not_enable_invariants(self):
        cfg = "SPECIFICATION Spec\nPROPERTY TypeOK\n\\* INVARIANT Safety\n"
        self.assertEqual(len(semantic.config_errors(cfg)), 2)

    def test_cannot_disable_deadlock_check(self):
        self.assertTrue(semantic.config_errors(CONFIG + "CHECK_DEADLOCK FALSE\n"))

    def test_passes_exact_generated_config_to_tlc(self):
        cfg = CONFIG + "CONSTANTS Limit = 3\nPROPERTY Progress\n"

        def run(command, **kwargs):
            config_name = command[command.index("-config") + 1]
            self.assertEqual(config_name, "TestSystem.cfg")
            self.assertEqual((Path(kwargs["cwd"]) / config_name).read_text(), cfg)
            self.assertNotIn("-deadlock", command)
            self.assertNotIn("-simulate", command)
            self.assertNotIn("-depth", command)
            return SimpleNamespace(returncode=0, stdout="Model checking completed. No error has been found.", stderr="")

        with tempfile.NamedTemporaryFile() as jar, patch.object(semantic, "_jar_path", return_value=Path(jar.name)), patch.object(semantic.subprocess, "run", side_effect=run):
            result = semantic.validate(bundle(), cfg)
        self.assertTrue(result.ok)
        self.assertIn("model checking completed", result.warnings[-1])

    def test_timeout_is_incomplete_not_passed(self):
        with tempfile.NamedTemporaryFile() as jar, patch.object(semantic, "_jar_path", return_value=Path(jar.name)), patch.object(semantic.subprocess, "run", side_effect=subprocess.TimeoutExpired("java", 30)):
            result = semantic.validate(bundle(), CONFIG)
        self.assertFalse(result.ok)
        self.assertTrue(result.incomplete)
        self.assertIn("timed out", result.errors[0])

    def test_zero_exit_without_completion_is_not_accepted(self):
        with tempfile.NamedTemporaryFile() as jar, patch.object(semantic, "_jar_path", return_value=Path(jar.name)), patch.object(semantic.subprocess, "run", return_value=SimpleNamespace(returncode=0, stdout="Stopped early", stderr="")):
            result = semantic.validate(bundle(), CONFIG)
        self.assertFalse(result.ok)
        self.assertTrue(result.incomplete)

    def test_missing_config_does_not_run_tlc(self):
        with patch.object(semantic.subprocess, "run") as run:
            result = semantic.validate(bundle(), "")
        self.assertFalse(result.ok)
        run.assert_not_called()


class PipelineTests(unittest.TestCase):
    def setUp(self):
        self.artifacts = tempfile.TemporaryDirectory()
        self.addCleanup(self.artifacts.cleanup)
        patcher = patch.object(pipeline, "sessions_dir", return_value=Path(self.artifacts.name))
        patcher.start()
        self.addCleanup(patcher.stop)

    def programs(self, results):
        return {"lm": None, "req2tla": Mock(side_effect=results),
                "tla2req": Mock(return_value=SimpleNamespace(summary="summary"))}

    def test_retry_missing_config_and_keep_accepted_pair(self):
        programs = self.programs([
            SimpleNamespace(tla_plus="old"),
            SimpleNamespace(tla_plus=bundle(), tlc_config=CONFIG),
        ])
        with patch.object(pipeline, "validate_tla"), patch.object(pipeline, "semantic_validate_tla", return_value=semantic.SemanticResult(True, warnings=["simulation only"])) as validate:
            result = pipeline.generate_tla(programs, "requirements")
        self.assertEqual(result.tlc_config, CONFIG.strip())
        self.assertEqual(result.validation_warnings, ("simulation only",))
        validate.assert_called_once_with(bundle(), CONFIG.strip())
        self.assertIn("missing tlc_config", programs["req2tla"].call_args.kwargs["requirements"])

    def test_cfg_validation_failure_retries_both_outputs(self):
        programs = self.programs([
            SimpleNamespace(tla_plus="first", tlc_config="SPECIFICATION Spec"),
            SimpleNamespace(tla_plus="second", tlc_config=CONFIG),
        ])
        with patch.object(pipeline, "validate_tla"), patch.object(pipeline, "semantic_validate_tla", side_effect=[semantic.SemanticResult(False, errors=["missing Safety"]), semantic.SemanticResult(True)]):
            result = pipeline.generate_tla(programs, "requirements")
        self.assertEqual(result.tla_plus, "second")
        self.assertIn("missing Safety", programs["req2tla"].call_args.kwargs["requirements"])

    def test_retry_uses_latest_candidate_config_and_complete_diagnostics(self):
        configs = [CONFIG + "\\* first config", CONFIG + "\\* second config", CONFIG]
        programs = self.programs([SimpleNamespace(tla_plus=f"candidate-{i}", tlc_config=cfg) for i, cfg in enumerate(configs)])
        trace = "Error: Deadlock\n" + "trace details\n" * 100 + "FINAL STATE"
        reports = [semantic.SemanticResult(False, errors=["deadlock"], diagnostics=trace),
                   semantic.SemanticResult(False, errors=["Safety failed"], diagnostics="second diagnostic"),
                   semantic.SemanticResult(True)]
        with patch.object(pipeline, "validate_tla"), patch.object(pipeline, "semantic_validate_tla", side_effect=reports):
            pipeline.generate_tla(programs, "original requirements")
        prompts = [call.kwargs["requirements"] for call in programs["req2tla"].call_args_list]
        self.assertIn("candidate-0", prompts[1])
        self.assertIn(configs[0], prompts[1])
        self.assertIn(trace, prompts[1])
        self.assertIn("candidate-1", prompts[2])
        self.assertIn(configs[1], prompts[2])
        self.assertIn("second diagnostic", prompts[2])
        self.assertNotIn("candidate-0", prompts[2])
        saved = list(Path(self.artifacts.name).rglob("attempt-01"))
        self.assertEqual(len(saved), 1)
        self.assertEqual((saved[0] / "bundle.tla").read_text(), "candidate-0")
        self.assertEqual((saved[0] / "model.cfg").read_text(), configs[0])
        self.assertEqual((saved[0] / "diagnostics.log").read_text(), trace)

    def test_syntax_retry_receives_source_and_full_parser_output(self):
        programs = self.programs([SimpleNamespace(tla_plus="bad-source", tlc_config=CONFIG), SimpleNamespace(tla_plus=bundle(), tlc_config=CONFIG)])
        diagnostics = "full parser output\n" * 100
        with patch.object(pipeline, "validate_tla", side_effect=[tla_validator.TLAValidationError("syntax failed", diagnostics), None]), patch.object(pipeline, "semantic_validate_tla", return_value=semantic.SemanticResult(True)):
            pipeline.generate_tla(programs, "requirements")
        retry = programs["req2tla"].call_args.kwargs["requirements"]
        self.assertIn("bad-source", retry)
        self.assertIn(CONFIG.strip(), retry)
        self.assertIn(diagnostics, retry)

    def test_incomplete_verification_stops_without_generation_retry(self):
        programs = self.programs([SimpleNamespace(tla_plus=bundle(), tlc_config=CONFIG)])
        statuses = []
        with patch.object(pipeline, "validate_tla"), patch.object(pipeline, "semantic_validate_tla", return_value=semantic.SemanticResult(False, errors=["verification incomplete"], incomplete=True)):
            with self.assertRaises(pipeline.VerificationIncomplete):
                pipeline.generate_tla(programs, "requirements", status_callback=lambda *args: statuses.append(args))
        self.assertEqual(programs["req2tla"].call_count, 1)
        self.assertEqual(statuses[-1][1], "incomplete")
        saved = list(Path(self.artifacts.name).rglob("attempt.json"))
        self.assertEqual(len(saved), 1)
        self.assertTrue(json.loads(saved[0].read_text())["incomplete"])

    def test_summary_receives_matching_config(self):
        generated = pipeline.GeneratedSpec(bundle(), CONFIG)
        programs = self.programs([])
        with patch.object(pipeline, "generate_tla", return_value=generated):
            result, summary = pipeline.roundtrip(programs, "requirements")
        self.assertEqual((result, summary), (generated, "summary"))
        programs["tla2req"].assert_called_once_with(spec=generated.tla_plus, tlc_config=CONFIG)

    def test_session_roundtrip_and_both_loaders_preserve_config(self):
        with tempfile.TemporaryDirectory() as directory:
            session = pipeline.Session("requirements", state_dir=directory)
            session.archive_round("requirements", pipeline.GeneratedSpec(bundle(), CONFIG, ("bounded",)), "summary", "now")
            path = session.save()
            for loaded in (pipeline.Session.load(directory), load_session_by_path(path)):
                self.assertEqual(loaded.rounds[0].tlc_config, CONFIG)
                self.assertEqual(loaded.rounds[0].semantic_warnings, ["bounded"])

    def test_old_sessions_load_without_config(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "legacy.json"
            path.write_text(json.dumps({"original_requirements": "old", "rounds": [{"tla_plus": "old"}]}))
            for loaded in (pipeline.Session.load(directory), load_session_by_path(str(path))):
                self.assertEqual(loaded.rounds[0].tlc_config, "")
                self.assertEqual(loaded.rounds[0].semantic_warnings, [])

    def test_cli_and_gui_history_display_configuration(self):
        from src import app as cli
        from src.gui import app as gui

        session = pipeline.Session("requirements")
        rnd = session.archive_round("requirements", pipeline.GeneratedSpec(bundle(), CONFIG, ("bounded",)), "summary", "now")
        output = io.StringIO()
        with patch.object(cli, "validate_tla"), redirect_stdout(output):
            cli._print_round(rnd)
        with patch.object(gui, "validate_tla"):
            history = gui._format_round(session, 0)
        for display in (output.getvalue(), history):
            self.assertIn(CONFIG, display)
            self.assertIn("bounded", display)

    def test_gui_worker_keeps_generated_pair_and_summarizes_both(self):
        from src.gui import app as gui

        generated = pipeline.GeneratedSpec(bundle(), CONFIG)
        programs = self.programs([])
        with patch.object(gui, "generate_tla", return_value=generated):
            updates = list(gui._capture_generate(programs, "requirements", threading.Event(), time.monotonic()))
        self.assertEqual(updates[-1][0], generated)
        self.assertEqual(updates[-1][1], "summary")
        programs["tla2req"].assert_called_once_with(spec=bundle(), tlc_config=CONFIG)


@unittest.skipUnless(shutil.which("java") and semantic._jar_path().is_file(), "Java and tla2tools.jar required")
class TLCIntegrationTests(unittest.TestCase):
    def test_configured_type_invariant_catches_bad_transition(self):
        result = semantic.validate(bundle("2"), CONFIG)
        self.assertFalse(result.ok)
        self.assertIn("TypeOK", " ".join(result.errors))

    def test_valid_finite_model_completes(self):
        result = semantic.validate(bundle(), CONFIG)
        self.assertTrue(result.ok, result.errors)
        self.assertFalse(result.incomplete)
        self.assertIn("model checking completed", " ".join(result.warnings))

    def test_deadlock_on_alternative_branch_is_rejected(self):
        text = bundle().replace("Next == e' = 1 - e", "Next == e = 0 /\\ e' \\in {0, 1}")
        result = semantic.validate(text, CONFIG)
        self.assertFalse(result.ok)
        self.assertIn("Deadlock", " ".join(result.errors))

    def test_violation_beyond_ten_steps_is_rejected(self):
        text = bundle("e + 1").replace("e \\in 0..1", "e \\in 0..11")
        result = semantic.validate(text, CONFIG)
        self.assertFalse(result.ok)
        self.assertIn("TypeOK", " ".join(result.errors))

    def test_temporal_property_is_checked(self):
        text = bundle().replace("Safety == e >= 0", "Safety == e >= 0\nEventuallyOne == <> (e = 1)")
        result = semantic.validate(text, CONFIG + "PROPERTY EventuallyOne\n")
        self.assertFalse(result.ok)
        self.assertIn("Temporal", " ".join(result.errors))


if __name__ == "__main__":
    unittest.main()
