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

import dspy

class RequirementToTLA(dspy.Signature):
    """Generate a well-formed TLA+ module that faithfully encodes the given natural-language requirements."""

    requirements = dspy.InputField(
        desc="Natural-language description of the system's behaviour."
    )
    reasoning = dspy.OutputField(
        desc=(
            "Step-by-step reasoning about the system: what global variables "
            "each module controls, how the modules interact, what invariant or safety "
            "property(s) must hold, and which module will serve as the composition module "
            "that INSTANCEs all others. Prefer a three-module structure of Environment (inputs), "
            "Machine (reacts to Environment), System (composes Environment and Machine), "
            "but allow additional modules if the faithful encoding requires them. Use this "
            "to decide the Init (initial-value) and Next (state-transition) behaviour of each "
            "module before emitting the TLA+ bundle."
        )
    )
    tla_plus = dspy.OutputField(
        desc=(
            "A TLA+ bundle of modules emitted in canonical modular form. "
            "The bundle must contain a final composition module that INSTANCEs every prior module "
            "via '<Alias> == INSTANCE <ModuleName> WITH' clauses and composes their Init/Next/Spec/TypeOK/Safety definitions. "
            "Prefer three modules in the canonical form Environment, Machine, System, with System composing Environment and Machine, "
            "but emit additional modules only when required for a faithful encoding. "
            "Each module must have a banner '---- MODULE <Name> ----' and end with a '====' trailer line. "
            "The exact number of dashes in the banner is NOT significant: any run of three or more leading dashes and any trailing dashes parses fine. "
            "Operational constraint from our TLA+ parser: AVOID '\\and', '\\/or', '\\/==', '\\/!='. Use '/' for conjunction and disjunction. "
            "Write universal quantifier as '\\A v . expr' and existential as '\\/E v . expr' (slash backslash immediately touching bound variable, then space, dot, expression)."
        )
    )


class TLAToRequirement(dspy.Signature):
    """Summarise a TLA+ bundle in plain natural language so a human can review whether it matches the original requirements."""

    spec = dspy.InputField(desc="The TLA+ bundle (commonly Environment, Machine, System, with a composition module that INSTANCEs the others) to summarize.")
    summary = dspy.OutputField(
        desc="Plain-language summary of what the TLA+ bundle specifies, covering Environment, Machine composition, other modules if present, and how the composition module composes them.",
    )
