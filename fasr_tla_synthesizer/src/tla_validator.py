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

import os
import re
import subprocess
import tempfile
from pathlib import Path


def validate_tla(tla_plus: str) -> None:
    text = str(tla_plus).strip()
    modules = split_tla_bundle(text)

    # Resolve jar path
    module_dir = Path(__file__).parent
    repo_root = module_dir.parent
    default_jar = repo_root / "tla2tools.jar"
    jar_path = os.getenv("TLA2TOOLS_JAR") or str(default_jar)
    jar_path = str(Path(jar_path).resolve())

    with tempfile.TemporaryDirectory() as tmpdir:
        tmp_path = Path(tmpdir)
        filenames = []
        for mod in modules:
            name = mod["name"]
            file_path = tmp_path / f"{name}.tla"
            file_path.write_text(mod["text"], encoding="utf-8")
            filenames.append(f"{name}.tla")

        cmd = ["java", "-cp", jar_path, "tla2sany.SANY", "-s", "-error-codes"] + filenames
        result = subprocess.run(cmd, capture_output=True, text=True, cwd=tmpdir)

        combined = result.stdout + "\n" + result.stderr
        if result.returncode == 0:
            return

        # Build a concise error message
        msg = None
        m = re.search(r"at line (\d+), column (\d+)", combined)
        if m:
            line, col = m.groups()
            mod_match = re.search(r"In module (\S+)", combined)
            prefix = f"module {mod_match.group(1)}: " if mod_match else ""
            msg = f"{prefix}parse failed at line {line}, column {col}"
        else:
            for line in combined.splitlines():
                if "Error" in line or "error" in line:
                    msg = line.strip()
                    break
        if not msg:
            msg = combined[:500]
        raise ValueError(msg)


def split_tla_bundle(tla_plus: str) -> list[dict]:
    _BANNER_RE = re.compile(
        r"(?P<lead>[-:]{3,}\s+MODULE\s)(?P<name>[A-Za-z_]\w*)",
        re.MULTILINE,
    )
    positions = list(_BANNER_RE.finditer(tla_plus))
    out = []
    for i, pos in enumerate(positions):
        name = pos.group("name")
        start = pos.start()
        end = positions[i+1].start() if i+1 < len(positions) else len(tla_plus)
        text = tla_plus[start:end].strip()
        out.append({"name": name, "text": text})
    if not out:
        return [{"name": "<bundle>", "text": tla_plus.strip()}]
    return out
