package net.sf.openrocket.file.openrocket.importt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import net.sf.openrocket.aerodynamics.WarningSet;
import net.sf.openrocket.document.OpenRocketDocument;
import net.sf.openrocket.document.Simulation;
import net.sf.openrocket.document.Simulation.Status;
import net.sf.openrocket.file.DocumentLoadingContext;
import net.sf.openrocket.file.simplesax.AbstractElementHandler;
import net.sf.openrocket.file.simplesax.ElementHandler;
import net.sf.openrocket.file.simplesax.PlainTextHandler;
import net.sf.openrocket.rocketcomponent.FlightConfigurationId;
import net.sf.openrocket.simulation.FlightData;
import net.sf.openrocket.simulation.SimulationOptions;
import net.sf.openrocket.simulation.extension.SimulationExtension;
import net.sf.openrocket.simulation.extension.SimulationExtensionProvider;
import net.sf.openrocket.simulation.extension.impl.JavaCode;
import net.sf.openrocket.startup.Application;
import net.sf.openrocket.util.StringUtil;

import com.google.inject.Key;

class SingleSimulationHandler extends AbstractElementHandler {
	
	private final DocumentLoadingContext context;
	
	private final OpenRocketDocument doc;
	
	private String name;
	
	private SimulationConditionsHandler conditionHandler;
	private ConfigHandler configHandler;
	private FlightDataHandler dataHandler;
	
	private final List<SimulationExtension> extensions = new ArrayList<>();
	
	public SingleSimulationHandler(OpenRocketDocument doc, DocumentLoadingContext context) {
		this.doc = doc;
		this.context = context;
	}
	
	public OpenRocketDocument getDocument() {
		return doc;
	}
	
	@Override
	public ElementHandler openElement(String element, HashMap<String, String> attributes,
			WarningSet warnings) {

		switch (element) {
			case "name":
			case "simulator":
			case "calculator":
			case "listener":
				return PlainTextHandler.INSTANCE;
			case "conditions":
				conditionHandler = new SimulationConditionsHandler(doc.getRocket(), context);
				return conditionHandler;
			case "extension":
				configHandler = new ConfigHandler();
				return configHandler;
			case "flightdata":
				dataHandler = new FlightDataHandler(this, context);
				return dataHandler;
			default:
				warnings.add("Unknown element '" + element + "', ignoring.");
				return null;
		}
	}
	
	@Override
	public void closeElement(String element, HashMap<String, String> attributes,
			String content, WarningSet warnings) {
		
		if (element.equals("name")) {
			name = content;
		} else if (element.equals("simulator")) {
			if (!content.trim().equals("RK4Simulator")) {
				warnings.add("Unknown simulator '" + content.trim() + "' specified, ignoring.");
			}
		} else if (element.equals("calculator")) {
			if (!content.trim().equals("BarrowmanCalculator")) {
				warnings.add("Unknown calculator '" + content.trim() + "' specified, ignoring.");
			}
		} else if (element.equals("listener") && content.trim().length() > 0) {
			extensions.add(compatibilityExtension(content.trim()));
		} else if (element.equals("extension") && !StringUtil.isEmpty(attributes.get("extensionid"))) {
			String id = attributes.get("extensionid");
			SimulationExtension extension = null;
			Set<SimulationExtensionProvider> extensionProviders = Application.getInjector().getInstance(new Key<>() {
            });
			for (SimulationExtensionProvider p : extensionProviders) {
				if (p.getIds().contains(id)) {
					extension = p.getInstance(id);
				}
			}
			if (extension != null) {
				extension.setConfig(configHandler.getConfig());
				extensions.add(extension);
			} else {
				warnings.add("Simulation extension with id '" + id + "' not found.");
			}
		}
		
	}
	
	@Override
	public void endHandler(String element, HashMap<String, String> attributes,
			String content, WarningSet warnings) {
		
		SimulationOptions options;
		FlightConfigurationId idToSet= FlightConfigurationId.ERROR_FCID;
		if (conditionHandler != null) {
			options = conditionHandler.getConditions();
			idToSet = conditionHandler.idToSet;
		} else {
			warnings.add("Simulation conditions not defined, using defaults.");
			options = new SimulationOptions();
		}
		
		if (name == null) 
			name = "Simulation";
		
		// If the simulation was saved with flight data (which may just be a summary)
		// mark it as loaded from the file else as not simulated.  We're ignoring the
		// simulation status attribute, since (1) it really isn't relevant now, and (2)
		// sim summaries are getting marked as not simulated when they're saved
		FlightData data;
		if (dataHandler == null)
			data = null;
		else
			data = dataHandler.getFlightData();

		Simulation.Status status;
		if (data != null) {
			status = Status.LOADED;
		} else {
			status = Status.NOT_SIMULATED;
		}
		
		Simulation simulation = new Simulation(doc, doc.getRocket(), status, name,
				options, extensions, data);
		simulation.setFlightConfigurationId( idToSet );
		
		doc.addSimulation(simulation);
	}
	
	
	private SimulationExtension compatibilityExtension(String className) {
		JavaCode extension = Application.getInjector().getInstance(JavaCode.class);
		extension.setClassName(className);
		return extension;
	}
	
}
