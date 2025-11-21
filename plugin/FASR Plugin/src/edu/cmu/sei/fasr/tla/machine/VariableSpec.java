package edu.cmu.sei.fasr.tla.machine;

import edu.cmu.sei.fasr.tla.TLANode;

public class VariableSpec extends TLANode{

	private String initialValue;

	public VariableSpec(String name, String initialValue) {
		super(name);
		this.initialValue = initialValue;
	}

	public String getInitialValue() {
		// TODO Auto-generated method stub
		return initialValue;
	}

}
