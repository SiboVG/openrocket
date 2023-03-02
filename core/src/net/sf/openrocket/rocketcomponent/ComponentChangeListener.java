package net.sf.openrocket.rocketcomponent;

import java.util.EventListener;

public interface ComponentChangeListener extends EventListener {

	void componentChanged(ComponentChangeEvent e);
	
}
