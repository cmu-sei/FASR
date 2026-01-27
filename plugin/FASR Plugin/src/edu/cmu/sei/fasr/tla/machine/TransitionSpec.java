package edu.cmu.sei.fasr.tla.machine;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import edu.cmu.sei.fasr.tla.TLANode;
import edu.cmu.sei.fasr.tla.tlaTranslationUtil;

public class TransitionSpec extends TLANode {
	private StateSpec source;
	private StateSpec target;
	private List<String> guards;
	private List<String> effects;
	private Set<String> modifiedVars;

	public TransitionSpec(StateSpec source, StateSpec target, SubmachineSpec submachine, MachineSpec machineSpec, int transitionCount, String transitionName) {
		super("ANON_ACT_" + String.valueOf(transitionCount));
		if (!transitionName.isBlank()) {
			this.name = transitionName;
		}
		this.source = source;
		this.target = target;
		effects = new LinkedList<>();
		guards = new LinkedList<>();
		modifiedVars = new HashSet<>();
		
		if(source.getName().equals("INIT")) {
			VariableSpec stateVar = new VariableSpec(submachine.getName() + "_state", target.getName());
			machineSpec.addVariable(stateVar);
		}
		//TODO: Put state test and change into guard and effects to eliminate special case in TLA rendering
	}

	public void setTriggerName(String triggerName) {
		this.name = triggerName;
	}

	public void addEffects(String[] assignments, MachineSpec containingMachine) {
		processAssignments(Arrays.asList(assignments), containingMachine);
	}

	public void addConditions(String[] conditions) {
		processConditions(Arrays.asList(conditions));
	}
	
	public StateSpec getSource() {
		return source;
	}

	public StateSpec getTarget() {
		return target;
	}
	
	public Set<String> getModifiedVars() {
		return modifiedVars;
	}
	
	public List<String> getGuards() {
		return guards;
	}
	
	public List<String> getEffects() {
		return effects;
	}
	
	private void processAssignments(List<String> assignments, MachineSpec containingMachine) {
		for (String assignment : assignments) {
			StringBuilder tlaAssignment = new StringBuilder();
			String[] assig_pieces = assignment.split("=");
			String modifiedVar = assig_pieces[0].trim();
			String lhs = modifiedVar + "'";
			modifiedVars.add(modifiedVar);
			tlaAssignment.append(lhs);
			tlaAssignment.append(" = ");
			String rhs = assig_pieces[1].trim();
			if(this.source.getName().equals("INIT")) {
				VariableSpec v = new VariableSpec(modifiedVar, rhs);
				containingMachine.addVariable(v);
			}
			String[] rhs_pieces = tlaTranslationUtil.splitExpression(rhs);
			for(String piece : rhs_pieces) {
				if (piece.equalsIgnoreCase("true") || piece.equalsIgnoreCase("false")) {
					tlaAssignment.append(piece.toUpperCase());
				} else if (piece.matches("<|^|>|%|\\+|-|/|==|<=|>=")) {
					tlaAssignment.append(tlaTranslationUtil.getTLAOperatorFromCEAOpeator(piece.trim()));
				} else {
					tlaAssignment.append(piece);
				}
			}
			effects.add(tlaAssignment.toString());
		}
	}

	private void processConditions(List<String> conditions) {
		for (String condition : conditions) {
			StringBuilder tlaCondition = new StringBuilder();
			String[] condit_pieces = condition.split("[=<>!]");
			String[] op_pieces = condition.split("[^=<>!]");
			String lhs = condit_pieces[0].trim();
			String rhs = condit_pieces[condit_pieces.length - 1].trim();
			if (rhs.equalsIgnoreCase("true") || rhs.equalsIgnoreCase("false")) {
				rhs = rhs.toUpperCase();
			}
			String op = tlaTranslationUtil.getTLAOperatorFromCEAOpeator(String.join("", op_pieces).trim());
			tlaCondition.append(lhs);
			tlaCondition.append(op);
			tlaCondition.append(rhs);
			guards.add(tlaCondition.toString());
		}
	}

}
