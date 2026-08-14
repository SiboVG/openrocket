package info.openrocket.core.file.wavefrontobj.export;

import info.openrocket.core.file.wavefrontobj.CoordTransform;
import info.openrocket.core.file.wavefrontobj.DefaultObj;
import info.openrocket.core.file.wavefrontobj.ObjUtils;
import info.openrocket.core.file.wavefrontobj.export.components.BodyTubeExporter;
import info.openrocket.core.file.wavefrontobj.export.components.FinSetExporter;
import info.openrocket.core.file.wavefrontobj.export.components.LaunchLugExporter;
import info.openrocket.core.file.wavefrontobj.export.components.MassObjectExporter;
import info.openrocket.core.file.wavefrontobj.export.components.MotorExporter;
import info.openrocket.core.file.wavefrontobj.export.components.RailButtonExporter;
import info.openrocket.core.file.wavefrontobj.export.components.RingComponentExporter;
import info.openrocket.core.file.wavefrontobj.export.components.RocketComponentExporter;
import info.openrocket.core.file.wavefrontobj.export.components.TransitionExporter;
import info.openrocket.core.file.wavefrontobj.export.components.TubeFinSetExporter;
import info.openrocket.core.logging.WarningSet;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.LaunchLug;
import info.openrocket.core.rocketcomponent.MassObject;
import info.openrocket.core.rocketcomponent.RailButton;
import info.openrocket.core.rocketcomponent.RingComponent;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.Transition;
import info.openrocket.core.rocketcomponent.TubeFinSet;

import java.util.Map;

/**
 * Shared dispatcher for turning rocket components and motors into polygon meshes.
 *
 * <p>The mesh is represented by {@link DefaultObj}, but callers are not required
 * to serialize it as Wavefront OBJ.  Keeping this dispatch in one place allows
 * OBJ and CAD exporters to use the same component geometry implementations.</p>
 */
public final class RocketComponentMeshExporter {
	private static final Map<Class<? extends RocketComponent>, ExporterFactory<?>> EXPORTER_MAP = Map.of(
			BodyTube.class, (ExporterFactory<BodyTube>) BodyTubeExporter::new,
			Transition.class, (ExporterFactory<Transition>) TransitionExporter::new,
			LaunchLug.class, (ExporterFactory<LaunchLug>) LaunchLugExporter::new,
			TubeFinSet.class, (ExporterFactory<TubeFinSet>) TubeFinSetExporter::new,
			FinSet.class, (ExporterFactory<FinSet>) FinSetExporter::new,
			RingComponent.class, (ExporterFactory<RingComponent>) RingComponentExporter::new,
			MassObject.class, (ExporterFactory<MassObject>) MassObjectExporter::new,
			RailButton.class, (ExporterFactory<RailButton>) RailButtonExporter::new
	);

	private RocketComponentMeshExporter() {
		// Utility class.
	}

	/**
	 * Adds the geometry for one component to an existing mesh.
	 *
	 * @param obj destination mesh
	 * @param configuration active flight configuration
	 * @param transformer coordinate-system transform
	 * @param component component to convert
	 * @param groupName mesh group name
	 * @param levelOfDetail circumferential mesh resolution
	 * @param exportAllInstances whether every configured instance is included
	 * @param warnings warnings produced while generating degenerate geometry
	 */
	public static void addComponent(DefaultObj obj, FlightConfiguration configuration, CoordTransform transformer,
			RocketComponent component, String groupName, ObjUtils.LevelOfDetail levelOfDetail,
			boolean exportAllInstances, WarningSet warnings) {
		addTypedComponent(obj, configuration, transformer, component, groupName, levelOfDetail,
				exportAllInstances, warnings);
	}

	/**
	 * Adds the configured motor geometry for a motor-mount component.
	 *
	 * @param obj destination mesh
	 * @param configuration active flight configuration
	 * @param transformer coordinate-system transform
	 * @param component motor-mount component
	 * @param groupName base mesh group name
	 * @param levelOfDetail circumferential mesh resolution
	 * @param exportAllInstances whether every configured mount instance is included
	 * @param warnings warnings produced while generating geometry
	 */
	public static void addMotor(DefaultObj obj, FlightConfiguration configuration, CoordTransform transformer,
			RocketComponent component, String groupName, ObjUtils.LevelOfDetail levelOfDetail,
			boolean exportAllInstances, WarningSet warnings) {
		MotorExporter motorExporter = new MotorExporter(obj, configuration, transformer, component, groupName,
				levelOfDetail, exportAllInstances, warnings);
		motorExporter.addToObj();
	}

	@SuppressWarnings("unchecked")
	private static <T extends RocketComponent> void addTypedComponent(DefaultObj obj,
			FlightConfiguration configuration, CoordTransform transformer, T component, String groupName,
			ObjUtils.LevelOfDetail levelOfDetail, boolean exportAllInstances, WarningSet warnings) {
		ExporterFactory<T> factory = null;
		Class<?> currentClass = component.getClass();

		// Subclasses such as NoseCone use the nearest registered superclass exporter.
		while (RocketComponent.class.isAssignableFrom(currentClass) && factory == null) {
			factory = (ExporterFactory<T>) EXPORTER_MAP.get(currentClass);
			currentClass = currentClass.getSuperclass();
		}

		if (factory == null) {
			throw new IllegalArgumentException("Unsupported component type: " + component.getClass().getName());
		}

		RocketComponentExporter<T> exporter = factory.create(obj, configuration, transformer, component, groupName,
				levelOfDetail, exportAllInstances, warnings);
		exporter.addToObj();
	}

	@FunctionalInterface
	private interface ExporterFactory<T extends RocketComponent> {
		RocketComponentExporter<T> create(DefaultObj obj, FlightConfiguration configuration, CoordTransform transformer,
				T component, String groupName, ObjUtils.LevelOfDetail levelOfDetail, boolean exportAllInstances,
				WarningSet warnings);
	}
}
