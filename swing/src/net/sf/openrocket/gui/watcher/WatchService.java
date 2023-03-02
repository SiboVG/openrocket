package net.sf.openrocket.gui.watcher;

public interface WatchService {
	
	WatchKey register(Watchable w);
	
}