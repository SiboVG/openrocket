package net.sf.openrocket.gui.watcher;

public interface Watchable {
	
	WatchEvent monitor();
	
	void handleEvent(WatchEvent evt);
	
}
