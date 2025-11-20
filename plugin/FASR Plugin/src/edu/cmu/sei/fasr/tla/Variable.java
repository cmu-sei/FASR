package edu.cmu.sei.fasr.tla;

public class Variable extends Node{

	private String initialValue;

	public Variable(String name, String initialValue) {
		super(name);
		this.initialValue = initialValue;
	}

	public String getInitialValue() {
		// TODO Auto-generated method stub
		return initialValue;
	}

}
