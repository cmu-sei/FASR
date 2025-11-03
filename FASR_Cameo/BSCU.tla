----------------------------- MODULE BSCU -----------------------------

EXTENDS Integers

VARIABLES step

vars == <<step>>

Init ==
	step = 0

TurnBSCUOn ==
	/\ step = 0
	/\ step' = step + 1

SetDecelRate ==
	/\ step = 4
	/\ step' = step + 1

SelfCheck ==
	/\ step = 1
	/\ step' = step + 1

ArmAutoBrake ==
	/\ step = 3
	/\ step' = step + 1

SetMode ==
	/\ step = 2
	/\ step' = step + 1

Wait ==
	/\ step = 5
	/\ step' = step + 1

Next ==
	\/ TurnBSCUOn
	\/ SetDecelRate
	\/ SelfCheck
	\/ ArmAutoBrake
	\/ SetMode
	\/ Wait

Spec == Init /\ [][Next]_vars

=============================================================================