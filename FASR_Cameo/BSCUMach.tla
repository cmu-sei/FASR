----------------------------- MODULE BSCUMach -----------------------------

EXTENDS Integers

VARIABLES mode, decelrate, fault, abarmed, power

vars == <<mode, decelrate, fault, abarmed, power>>

TurnBSCUOn == 
	/\
		\/
			/\ power = FALSE
	/\
		\/
			/\ power' = TRUE
	/\ UNCHANGED <<mode, decelrate, fault, abarmed>>

SelfCheck == 
	/\
		\/
			/\ power = TRUE
			/\ fault = "Unset"
	/\
		\/
			/\ fault' = "NoFault"
	/\
		\/
			/\ power = TRUE
			/\ fault = "Unset"
	/\
		\/
			/\ fault' = "Fault"
	/\ UNCHANGED <<mode, decelrate, abarmed, power>>

Wait == 
	/\ UNCHANGED <<mode, decelrate, fault, abarmed, power>>

SetMode == 
	/\
		\/
			/\ fault = "NoFault"
			/\ mode = "Unset"
	/\
		\/
			/\ mode' = "Manual"
	/\
		\/
			/\ fault = "NoFault"
			/\ mode = "Unset"
	/\
		\/
			/\ mode' = "Auto"
	/\ UNCHANGED <<decelrate, fault, abarmed, power>>

ArmAutoBrake == 
	/\
		\/
			/\ mode = "Auto"
			/\ abarmed = FALSE
	/\
		\/
			/\ abarmed' = TRUE
	/\ UNCHANGED <<mode, decelrate, fault, power>>

SetDecelRate == 
	/\
		\/
			/\ abarmed = TRUE
			/\ decelrate = 0
	/\
		\/
			/\ decelrate' = 9
	/\ UNCHANGED <<mode, fault, abarmed, power>>

Init == 
	/\ power = FALSE
	/\ fault = "Unset"
	/\ mode = "Unset"
	/\ abarmed = FALSE
	/\ decelrate = 0

Next ==
	\/ TurnBSCUOn
	\/ SelfCheck
	\/ Wait
	\/ SetMode
	\/ ArmAutoBrake
	\/ SetDecelRate

Spec == Init /\ [][Next]_vars

=============================================================================