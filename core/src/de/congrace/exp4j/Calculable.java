package de.congrace.exp4j;

/**
 * This is the basic result class of the exp4j {@link ExpressionBuilder}
 * 
 * @author ruckus
 * 
 */
public interface Calculable {
	/**
	 * calculate the result of the expression
	 * 
	 * @return the result of the calculation
	 */
	Variable calculate();

	/**
	 * return the expression in reverse polish postfix notation
	 * 
	 * @return the expression used to construct this {@link Calculable}
	 */
	String getExpression();

	/**
	 * set a variable value for the calculation
	 * 
	 * @param value
	 *            the value of the variable
	 */
	void setVariable(Variable var);
}
