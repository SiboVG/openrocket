package net.sf.openrocket.util;


/**
 * An interface defining an object firing ChangeEvents.
 * 
 * @author Sampo Niskanen <sampo.niskanen@iki.fi>
 */
public interface ChangeSource {
	
	void addChangeListener(StateChangeListener listener);
	
	void removeChangeListener(StateChangeListener listener);
	
}
