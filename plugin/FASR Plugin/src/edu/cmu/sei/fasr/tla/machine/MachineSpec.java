package edu.cmu.sei.fasr.tla.machine;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Scanner;
import java.util.Stack;
import java.util.stream.Collectors;

import edu.cmu.sei.fasr.tla.TLANode;
import edu.cmu.sei.fasr.tla.tlaTranslationUtil;
import edu.cmu.sei.fasr.tla.machine.InvariantSpec.InvariantExpression;

public class MachineSpec extends TLANode {

	private Collection<SubmachineSpec> submachines;
	private Collection<InvariantSpec> invariants;
	private Collection<VariableSpec> variables;

	public MachineSpec(String name) {
		super(name);
		submachines = new HashSet<>();
		invariants = new HashSet<>();
		variables = new HashSet<>();
	}

	public void addSubmachine(SubmachineSpec submachine) {
		submachines.add(submachine);
	}

	public void addVariable(VariableSpec v) {
		variables.add(v);
	}

	public void addInvariants(Map<String, String> invariants) {
		for (String invariantName : invariants.keySet()) {
			this.invariants.add(processInvariant(invariantName, invariants.get(invariantName)));
		}
	}
	
	public Collection<SubmachineSpec> getSubmachines() {
		return submachines;
	}
	
	public String getTLA() {
		String activityName;
		boolean selfTransition;
		HashSet<String> processedTransitions = new HashSet<>();
		HashSet<String> activityNames = new HashSet<>();
		StringBuilder tla = new StringBuilder();
		tla.append("----------------------------- MODULE ");
		tla.append(name);
		tla.append(" -----------------------------\n\n");
		tla.append("EXTENDS Integers\n\n");
		tla.append("VARIABLES ");
		tla.append(variables.stream().map(VariableSpec::getName).collect(Collectors.joining(", ")));
		tla.append("\n\nvars == <<");
		tla.append(variables.stream().map(VariableSpec::getName).collect(Collectors.joining(", ")));
		tla.append(">>\n\n");
		tla.append("Init == \n");
		for(VariableSpec v : variables) {
			tla.append("\t/\\ ");
			tla.append(v.getName());
			tla.append(" = ");
			if(isInteger(v.getInitialValue())){
				tla.append(v.getInitialValue());
			} else {
				tla.append("\"");
				tla.append(v.getInitialValue());
				tla.append("\"");
			}
			tla.append("\n");
		}
		
		for(SubmachineSpec sub : submachines) {
			for(TransitionSpec t : sub.getTransitions()) {
				if(t.getSource().getName().equals("INIT")) {
					continue;
				}
				selfTransition = t.getSource().getName().equals(t.getTarget().getName());
				HashSet<String> unchangedVarNames = new HashSet<>(variables.stream().map(VariableSpec::getName).collect(Collectors.toSet()));
				unchangedVarNames.removeAll(t.getModifiedVars());
				if(!selfTransition) {
					unchangedVarNames.remove(sub.getName() + "_state");
				}
				activityName = t.getName();
				if(processedTransitions.contains(activityName)) {
					// TODO: Merge specs?
					continue;
				} else {
					processedTransitions.add(activityName);
				}
				
				
				tla.append("\n");
				tla.append(activityName);
				activityNames.add(activityName);
				tla.append(" == \n");
				for(String guard : t.getGuards()) {
					tla.append("\t/\\ ");
					tla.append(guard);
					tla.append("\n");
				}
				if(!selfTransition) {
					tla.append("\t/\\ ");
					tla.append(sub.getName());
					tla.append("_state = \"");
					tla.append(t.getSource().getName());
					tla.append("\"\n");
				}
				for(String effect : t.getEffects()) {
					tla.append("\t/\\ ");
					tla.append(effect);
					tla.append("\n");
				}
				if(!selfTransition) {
					tla.append("\t/\\ ");
					tla.append(sub.getName());
					tla.append("_state' = \"");
					tla.append(t.getTarget().getName());
					tla.append("\"\n");
				}
				tla.append("\t/\\ UNCHANGED <<");
				tla.append(String.join(", ", unchangedVarNames));
				tla.append(">>\n");
			}
		}
		
		tla.append("\nNext ==\n\t\\/ ");
		tla.append(activityNames.stream().collect(Collectors.joining("\n\t\\/ ")));
		tla.append("\n\nSpec == Init /\\ [][Next]_vars\n\n");
		
		for(InvariantSpec inv : invariants) {
			tla.append(inv.getName());
			tla.append(" == ");
			Stack<InvariantExpression> expressions = new Stack<>();
			
			for(InvariantExpression ie : inv.getExpressions()) {
				expressions.push(ie);
			}
			while(!expressions.isEmpty()) {
				InvariantExpression ie = expressions.pop();
				tla.append("\n");
				for(int i = 0; i < ie.depth() + 1; i++) {
					tla.append("\t");
				}
				if(ie.depth() % 2 == 0) {
					tla.append("/\\");
				} else {
					tla.append("\\/");
				}
				if(ie.variableName() != null) {
					tla.append(" ");
					tla.append(ie.variableName());
					tla.append(ie.operator());
					tla.append(ie.value());
				}
				if(ie.depth() == 0) {
					tla.append(" => ");
				}
				for(InvariantExpression child : ie.children()) {
					expressions.push(child);
				}
			}
			tla.append("\n\n");
		}
		tla.append("=============================================================================");
		return tla.toString();
	}
	
	public String getCFG() {
		StringBuilder cfg = new StringBuilder();
		cfg.append("SPECIFICATION Spec\n");
		cfg.append("CHECK_DEADLOCK FALSE\n");
		for(InvariantSpec i : invariants){
			cfg.append("INVARIANT ");
			cfg.append(i.getName());
			cfg.append("\n");
		}
		return cfg.toString();
	}

	private InvariantSpec processInvariant(String invariantName, String invariantText) {
		InvariantSpec invariant = new InvariantSpec(invariantName);
		buildInvariantExpressions(invariant, invariantText);
		return invariant;
	}

	private void buildInvariantExpressions(InvariantSpec invariant, String invariantText) {
		String[] invariantParts = invariantText.split(" ", 3);
		String[] expressionParts = getStateExpression(invariantParts[0]);
		InvariantExpression exp = invariant.addTopLevelExp(expressionParts[0], expressionParts[1], expressionParts[2]);
		// invariantParts[1].equals("IMPLIES"); // Must be true, implication is only top
		// level operator supported
		buildInvariantExpressions(invariant, invariantParts[2], exp);
	}

	private void buildInvariantExpressions(InvariantSpec invariant, String invariantText, InvariantExpression parent) {
		// Strip beginning and ending parentheses
		if(invariantText.charAt(0) == '(') {
			// Inner
			int expStart = 1;
			int expEnd;
			String trimmedInvariantText;
			while(expStart > 0 && expStart < invariantText.length()) {
				trimmedInvariantText = invariantText.substring(expStart, invariantText.length() - 1).trim();
				expStart = 1;
				expEnd = findMatchingParensIndex(trimmedInvariantText);
				String expStr = trimmedInvariantText;
				if(expEnd > expStart) {
					expStr = trimmedInvariantText.substring(expStart, expEnd);
				}
				InvariantExpression newExp = invariant.addBlankExp(parent);
				buildInvariantExpressions(invariant, expStr, newExp);
				expStart = trimmedInvariantText.indexOf('(', expEnd);
			}
		} else {
			if(invariantText.contains("AND")) {
				for(String stateExpression : invariantText.split("AND")) {
					String[] stExpPieces = getExpression(stateExpression);
					invariant.addExp(stExpPieces[0], stExpPieces[1], stExpPieces[2], parent);
				}
			} else if(invariantText.contains("OR")) {
				for(String stateExpression : invariantText.split("OR")) {
					String[] stExpPieces = getExpression(stateExpression);
					invariant.addExp(stExpPieces[0], stExpPieces[1], stExpPieces[2], parent);
				}
			} else {
				String[] stExpPieces = getExpression(invariantText);
				invariant.addExp(stExpPieces[0], stExpPieces[1], stExpPieces[2], parent);
			}
		}
	}

	private int findMatchingParensIndex(String invariantText) {
		int parensCount = 0;
		for (int i = 0; i < invariantText.length(); i++) {
			if (invariantText.charAt(i) == '(') {
				parensCount++;
			} else if (invariantText.charAt(i) == ')') {
				parensCount--;
			}
			if (parensCount == 0) {
				return i;
			}
		}
		return -1;
	}
	
	private String[] getExpression(String expression) {
		if(expression.contains(".")) {
			return getStateExpression(expression);
		} else {
			String[] expressionParts = tlaTranslationUtil.splitExpression(expression);
			for(int i = 0; i < expressionParts.length; i++) {
				expressionParts[i] = expressionParts[i].trim();
			}
			return expressionParts;
		}
	}

	private String[] getStateExpression(String dotStateReference) {
		String[] result = new String[3];
		String[] referenceParts = dotStateReference.split("\\.", 2);
		result[0] = referenceParts[0].trim() + "_state";
		result[1] = "==";
		result[2] = "\"" + referenceParts[1].trim() + "\"";
		return result;
	}
	
	private static boolean isInteger(String s) {
	    Scanner sc = new Scanner(s.trim());
	    boolean result = true;
	    if(!sc.hasNextInt()) {
	    	result = false;
	    }
	    // we know it starts with a valid int, now make sure
	    // there's nothing left!
	    if(result) {
		    sc.nextInt();
	    	result = !sc.hasNext();
	    }
	    sc.close();
	    return result;
	}
}
