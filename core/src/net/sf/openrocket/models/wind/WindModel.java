package net.sf.openrocket.models.wind;

import net.sf.openrocket.util.Coordinate;
import net.sf.openrocket.util.Monitorable;

public interface WindModel extends Monitorable {

	Coordinate getWindVelocity(double time, double altitude);
	
}
