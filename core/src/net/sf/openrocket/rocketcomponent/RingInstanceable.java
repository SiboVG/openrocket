package net.sf.openrocket.rocketcomponent;

import net.sf.openrocket.rocketcomponent.position.AnglePositionable;
import net.sf.openrocket.rocketcomponent.position.AngleMethod;
import net.sf.openrocket.rocketcomponent.position.RadiusMethod;
import net.sf.openrocket.rocketcomponent.position.RadiusPositionable;

public interface RingInstanceable extends Instanceable, AnglePositionable, RadiusPositionable {

	@Override
    double getAngleOffset();
	@Override
    void setAngleOffset(final double angle);
	@Override
    AngleMethod getAngleMethod();
	@Override
    void setAngleMethod(final AngleMethod method );
	
	double getInstanceAngleIncrement();
	
	double[] getInstanceAngles();
	
	
	@Override
    double getRadiusOffset();
	@Override
    void setRadiusOffset(final double radius);
	@Override
    RadiusMethod getRadiusMethod();
	@Override
    void setRadiusMethod(final RadiusMethod method );
	
}
