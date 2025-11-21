package edu.cmu.sei.fasr.tla.machine;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import edu.cmu.sei.fasr.tla.TLANode;

public class SubmachineSpec extends TLANode{
	private Map<String, StateSpec> states;
	private Collection<TransitionSpec> transitions;
	
	public SubmachineSpec(String name) {
		super(name);
		states = new HashMap<>();
		states.put("INIT", new StateSpec("INIT"));
		transitions = new HashSet<>();
	}

	public void addState(StateSpec stateSpec) {
		states.put(stateSpec.getName(), stateSpec);
	}
	
	public StateSpec getState(String stateName) {
		return states.get(stateName);
	}

	public void addTransition(TransitionSpec transitionSpec) {
		transitions.add(transitionSpec);
	}
	
	public Collection<TransitionSpec> getTransitions() {
		return transitions;
	}
}
