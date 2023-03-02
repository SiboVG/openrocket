package net.sf.openrocket.startup;

public interface ExceptionHandler {

	void handleErrorCondition(String message);
	void handleErrorCondition(String message, Throwable exception);
	void handleErrorCondition(final Throwable exception);

	
	void uncaughtException(final Thread thread, final Throwable throwable);
	
}
