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
import json
from datetime import datetime
from pathlib import Path

from ..pipeline import Session, DEFAULT_STATE_DIR


def list_sessions(state_dir: str = DEFAULT_STATE_DIR):
    if not os.path.isdir(state_dir):
        return []
    sessions = []
    for fname in os.listdir(state_dir):
        if not fname.endswith(".json"):
            continue
        path = os.path.join(state_dir, fname)
        if not os.path.isfile(path):
            continue
        try:
            mtime = os.path.getmtime(path)
            mtime_str = datetime.fromtimestamp(mtime).isoformat()
            with open(path, "r", encoding="utf-8") as fh:
                payload = json.load(fh)
            orig = payload.get("original_requirements", "")
            rounds = payload.get("rounds", [])
            num_rounds = len(rounds) if isinstance(rounds, list) else 0
            snippet = orig[:80].replace("\n", " ")
            project_name = fname[:-5]
            sessions.append({
                "path": path,
                "project_name": project_name,
                "snippet": snippet,
                "num_rounds": num_rounds,
                "mtime": mtime_str,
                "mtime_ts": mtime,
            })
        except Exception:
            continue
    sessions.sort(key=lambda x: x["mtime_ts"], reverse=True)
    return sessions


def delete_session(path: str) -> bool:
    try:
        if os.path.isfile(path):
            os.remove(path)
            return True
    except Exception:
        return False
    return False


def load_session_by_path(path: str) -> Session | None:
    try:
        with open(path, "r", encoding="utf-8") as fh:
            payload = json.load(fh)
        state_dir = os.path.dirname(path)
        sess = Session(
            original_requirements=payload.get("original_requirements", ""),
            clarifications=payload.get("clarifications", []),
            state_dir=state_dir,
        )
        # Rehydrate rounds via Session.load logic
        raw_rounds = payload.get("rounds", [])
        from ..pipeline import Round
        for r in raw_rounds:
            if not isinstance(r, dict):
                continue
            sess.rounds.append(Round(
                index=r.get("index", 0),
                requirements=r.get("requirements", ""),
                tla_plus=r.get("tla_plus", ""),
                summary=r.get("summary", ""),
                timestamp=r.get("timestamp", ""),
            ))
        return sess
    except Exception:
        return None
