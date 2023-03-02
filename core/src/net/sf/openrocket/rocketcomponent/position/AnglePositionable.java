package net.sf.openrocket.rocketcomponent.position;

public interface AnglePositionable {

	/**
	 * @return angle to the first element, in radians
	 */
    double getAngleOffset();
	
	/**
	 * @param new offset angle, in radians
	 */
    void setAngleOffset(final double angle);
	
	AngleMethod getAngleMethod( );
	void setAngleMethod( final AngleMethod newMethod );
}
