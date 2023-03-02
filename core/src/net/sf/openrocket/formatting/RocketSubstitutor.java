package net.sf.openrocket.formatting;

import java.util.Map;

import net.sf.openrocket.plugin.Plugin;
import net.sf.openrocket.rocketcomponent.FlightConfigurationId;
import net.sf.openrocket.rocketcomponent.Rocket;

/**
 * A class that allows substitution to occur in a text string.
 */
@Plugin
public interface RocketSubstitutor {
	
	boolean containsSubstitution(String str);
	
	String substitute(String str, Rocket rocket, FlightConfigurationId configId);
	
	Map<String, String> getDescriptions();
	
}
