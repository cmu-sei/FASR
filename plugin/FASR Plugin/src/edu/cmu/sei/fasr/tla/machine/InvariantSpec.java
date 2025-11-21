package edu.cmu.sei.fasr.tla.machine;

import java.util.LinkedList;
import java.util.List;

import edu.cmu.sei.fasr.tla.TLANode;
import edu.cmu.sei.fasr.tla.tlaTranslationUtil;

public class InvariantSpec extends TLANode { 

	public record InvariantExpression(String variableName, String operator, String value, List<InvariantExpression> children,
			int depth) {
	}
	
	private List<InvariantExpression> expressions;

	public InvariantSpec(String name) {
		super(name);
		 expressions = new LinkedList<>();
	}

	public InvariantExpression addExp(String var, String op, String val, InvariantExpression parent) {
		InvariantExpression inv = new InvariantExpression(var, tlaTranslationUtil.getTLAOperatorFromCEAOpeator(op), val, new LinkedList<>(), parent.depth + 1);
		parent.children.add(inv);
		return inv;
	}

	public InvariantExpression addTopLevelExp(String var, String op, String val) {
		InvariantExpression exp = new InvariantExpression(var, tlaTranslationUtil.getTLAOperatorFromCEAOpeator(op), val, new LinkedList<>(), 0);
		expressions.add(exp);
		return exp;
	}

	public InvariantExpression addBlankExp(InvariantExpression parent) {
		return addExp(null, "==", null, parent);
	}

	public List<InvariantExpression> getExpressions(){
		return expressions;
	}
}
