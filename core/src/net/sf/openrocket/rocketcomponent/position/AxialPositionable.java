package net.sf.openrocket.rocketcomponent.position;


public interface AxialPositionable {

	double getAxialOffset();
	
	void setAxialOffset(final double newAxialOffset);
	
	AxialMethod getAxialMethod( );
	
	void setAxialMethod( AxialMethod newMethod );
}
