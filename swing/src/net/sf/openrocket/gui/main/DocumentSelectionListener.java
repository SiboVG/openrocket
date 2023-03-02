package net.sf.openrocket.gui.main;

public interface DocumentSelectionListener {

	int COMPONENT_SELECTION_CHANGE = 1;
	int SIMULATION_SELECTION_CHANGE = 2;
	
	/**
	 * Called when the selection changes.
	 * 
	 * @param changeType	a bitmask of the type of change.
	 */
    void valueChanged(int changeType);
	
}
