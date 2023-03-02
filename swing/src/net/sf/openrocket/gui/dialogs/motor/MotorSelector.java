package net.sf.openrocket.gui.dialogs.motor;

import javax.swing.JComponent;

import net.sf.openrocket.motor.Motor;

public interface MotorSelector {
	
	/**
	 * Return the currently selected motor.
	 * 
	 * @return		the currently selected motor, or <code>null</code> if no motor is selected.
	 */
    Motor getSelectedMotor();
	
	/**
	 * Return the currently selected ejection charge delay.
	 * 
	 * @return		the currently selected ejection charge delay.
	 */
    double getSelectedDelay();
	
	/**
	 * Return the component that should have the default focus in this motor selector panel.
	 * 
	 * @return		the component that should have default focus, or <code>null</code> for none.
	 */
    JComponent getDefaultFocus();
	
	/**
	 * Notify that the provided motor has been selected.  This can be used to store preference
	 * data for later usage.
	 * 
	 * @param m		the motor that was selected.
	 */
    void selectedMotor(Motor m);
	
}
