package edu.cmu.sei.fasr.tla;

public abstract class TLANode {
	protected String name;
	
	public TLANode(String name) {
		this.name = name;
	}
	
	public String getName() {
		return name;
	}
	
}
