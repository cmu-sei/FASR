package edu.cmu.sei.fasr.tla;

public class tlaTranslationUtil {

	public static String getTLAOperatorFromCEAOpeator(String ceaOperator) {
		switch (ceaOperator) {
		case "==":
			return " = ";
		case "<":
			return " < ";
		case ">":
			return " > ";
		case "<=":
			return " \\leq ";
		case ">=":
			return " \\geq ";
		case "%":
			return " % ";
		case "+":
			return " + ";
		case "-":
			return " - ";
		case "/":
			return " \\div ";
		case "*":
			return " * ";
		case "^":
			return " ^ ";
		}
		throw new UnsupportedOperationException("Encounted unknown operator: " + ceaOperator);
	}
	
	public static String[] splitExpression(String expression) {
		String[] ret = expression.split("((?=^|%|\\+|-|/|==|<=|>=)|(?<=^|%|\\+|-|/|==|<=|>=))");
		if (ret.length > 1){
			return ret;
		} else {
			return expression.split("((?=<|^|>|%|\\+|-|/|==)|(?<=<|^|>|%|\\+|-|/|==))");
		}
	}
	
}
