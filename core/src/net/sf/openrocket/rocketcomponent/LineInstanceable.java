package net.sf.openrocket.rocketcomponent;

import net.sf.openrocket.rocketcomponent.position.AxialPositionable;

public interface LineInstanceable extends AxialPositionable, Instanceable {

	double getInstanceSeparation();
	
	void setInstanceSeparation(final double radius);
	
}
