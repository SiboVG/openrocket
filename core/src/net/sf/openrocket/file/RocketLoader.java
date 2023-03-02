package net.sf.openrocket.file;

import java.io.InputStream;

import net.sf.openrocket.aerodynamics.WarningSet;

public interface RocketLoader {
	
	void load(DocumentLoadingContext context, InputStream source) throws RocketLoadException;
	
	WarningSet getWarnings();
	
}
