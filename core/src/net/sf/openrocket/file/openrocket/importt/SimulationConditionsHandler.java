package net.sf.openrocket.file.openrocket.importt;

import java.util.HashMap;

import net.sf.openrocket.aerodynamics.WarningSet;
import net.sf.openrocket.file.DocumentLoadingContext;
import net.sf.openrocket.file.simplesax.AbstractElementHandler;
import net.sf.openrocket.file.simplesax.ElementHandler;
import net.sf.openrocket.file.simplesax.PlainTextHandler;
import net.sf.openrocket.rocketcomponent.FlightConfigurationId;
import net.sf.openrocket.rocketcomponent.Rocket;
import net.sf.openrocket.simulation.SimulationOptions;
import net.sf.openrocket.util.GeodeticComputationStrategy;

class SimulationConditionsHandler extends AbstractElementHandler {
	private final DocumentLoadingContext context;
	public FlightConfigurationId idToSet = FlightConfigurationId.ERROR_FCID;
	private final SimulationOptions options;
	private AtmosphereHandler atmosphereHandler;
	
	public SimulationConditionsHandler(Rocket rocket, DocumentLoadingContext context) {
		this.context = context;
		options = new SimulationOptions();
		// Set up default loading settings (which may differ from the new defaults)
		options.setGeodeticComputation(GeodeticComputationStrategy.FLAT);
	}
	
	public SimulationOptions getConditions() {
		return options;
	}
	
	@Override
	public ElementHandler openElement(String element, HashMap<String, String> attributes,
			WarningSet warnings) {
		if (element.equals("atmosphere")) {
			atmosphereHandler = new AtmosphereHandler(attributes.get("model"), context);
			return atmosphereHandler;
		}
		return PlainTextHandler.INSTANCE;
	}
	
	@Override
	public void closeElement(String element, HashMap<String, String> attributes,
			String content, WarningSet warnings) {
		
		double d = Double.NaN;
		try {
			d = Double.parseDouble(content);
		} catch (NumberFormatException ignore) {
		}


		switch (element) {
			case "configid":
				this.idToSet = new FlightConfigurationId(content);
				break;
			case "launchrodlength":
				if (Double.isNaN(d)) {
					warnings.add("Illegal launch rod length defined, ignoring.");
				} else {
					options.setLaunchRodLength(d);
				}
				break;
			case "launchrodangle":
				if (Double.isNaN(d)) {
					warnings.add("Illegal launch rod angle defined, ignoring.");
				} else {
					options.setLaunchRodAngle(d * Math.PI / 180);
				}
				break;
			case "launchroddirection":
				if (Double.isNaN(d)) {
					warnings.add("Illegal launch rod direction defined, ignoring.");
				} else {
					options.setLaunchRodDirection(d * 2.0 * Math.PI / 360);
				}
				break;
			case "windaverage":
				if (Double.isNaN(d)) {
					warnings.add("Illegal average windspeed defined, ignoring.");
				} else {
					options.setWindSpeedAverage(d);
				}
				break;
			case "windturbulence":
				if (Double.isNaN(d)) {
					warnings.add("Illegal wind turbulence intensity defined, ignoring.");
				} else {
					options.setWindTurbulenceIntensity(d);
				}
				break;
			case "launchaltitude":
				if (Double.isNaN(d)) {
					warnings.add("Illegal launch altitude defined, ignoring.");
				} else {
					options.setLaunchAltitude(d);
				}
				break;
			case "launchlatitude":
				if (Double.isNaN(d)) {
					warnings.add("Illegal launch latitude defined, ignoring.");
				} else {
					options.setLaunchLatitude(d);
				}
				break;
			case "launchlongitude":
				if (Double.isNaN(d)) {
					warnings.add("Illegal launch longitude.");
				} else {
					options.setLaunchLongitude(d);
				}
				break;
			case "geodeticmethod":
				GeodeticComputationStrategy gcs =
						(GeodeticComputationStrategy) DocumentConfig.findEnum(content, GeodeticComputationStrategy.class);
				if (gcs != null) {
					options.setGeodeticComputation(gcs);
				} else {
					warnings.add("Unknown geodetic computation method '" + content + "'");
				}
				break;
			case "atmosphere":
				atmosphereHandler.storeSettings(options, warnings);
				break;
			case "timestep":
				if (Double.isNaN(d) || d <= 0) {
					warnings.add("Illegal time step defined, ignoring.");
				} else {
					options.setTimeStep(d);
				}
				break;
		}
	}
}