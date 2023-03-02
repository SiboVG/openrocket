package net.sf.openrocket.rocketcomponent;

import net.sf.openrocket.util.ChangeSource;

public interface Clusterable extends ChangeSource, Instanceable {
	
	ClusterConfiguration getClusterConfiguration();
	
	void setClusterConfiguration(ClusterConfiguration cluster);
	
	double getClusterSeparation();
	
	
}
