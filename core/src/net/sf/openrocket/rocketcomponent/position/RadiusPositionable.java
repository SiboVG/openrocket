package net.sf.openrocket.rocketcomponent.position;

public interface RadiusPositionable {

    double getBoundingRadius();

	double getRadiusOffset();
	void setRadiusOffset(final double radius);
	
	RadiusMethod getRadiusMethod();
	void setRadiusMethod( final RadiusMethod method );
	
	/**
	 * Equivalent to:
	 * `instance.setRadiusMethod(); instance.setRadiusOffset()`
	 * 
	 * @param radius
	 * @param method
	 */
    void setRadius( final RadiusMethod method, final double radius );
	
}
