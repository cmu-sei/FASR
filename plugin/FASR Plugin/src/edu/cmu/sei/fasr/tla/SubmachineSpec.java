package edu.cmu.sei.fasr.tla;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class SubmachineSpec extends Node{
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
