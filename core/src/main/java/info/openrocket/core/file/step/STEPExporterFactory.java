package info.openrocket.core.file.step;

import de.javagl.obj.FloatTuple;
import info.openrocket.core.file.wavefrontobj.DefaultObj;
import info.openrocket.core.file.wavefrontobj.ObjUtils;
import info.openrocket.core.file.wavefrontobj.export.RocketComponentMeshExporter;
import info.openrocket.core.logging.Warning;
import info.openrocket.core.logging.WarningSet;
import info.openrocket.core.rocketcomponent.ComponentAssembly;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.MotorMount;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.util.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Builds rocket-component geometry and writes it as STEP AP214 files.
 *
 * <p>Axisymmetric components use continuous advanced B-reps.  Components that
 * cannot be represented as a surface of revolution are delegated to the same
 * mesh exporters used by the Wavefront OBJ workflow and become faceted B-reps.</p>
 */
public class STEPExporterFactory {
	private final List<RocketComponent> components;
	private final FlightConfiguration configuration;
	private final STEPExportOptions options;
	private final File file;
	private final WarningSet warnings;

	/**
	 * Creates a component STEP export operation.
	 *
	 * @param components selected components
	 * @param configuration active flight configuration
	 * @param file requested combined-file path or separate-file base path
	 * @param options STEP export options
	 * @param warnings destination for geometry warnings
	 */
	public STEPExporterFactory(List<RocketComponent> components, FlightConfiguration configuration, File file,
			STEPExportOptions options, WarningSet warnings) {
		this.components = components;
		this.configuration = configuration;
		this.file = file;
		this.options = options;
		this.warnings = warnings;
	}

	/**
	 * Generates and writes all requested STEP files.
	 *
	 * @return aggregate topology summary across all files
	 * @throws IOException if an output file cannot be written
	 */
	public STEPWriter.Result doExport() throws IOException {
		List<ExportedGeometry> exportedGeometries = buildGeometry();
		if (exportedGeometries.isEmpty()) {
			throw new IllegalArgumentException("The selected components generated no STEP geometry");
		}

		int solidCount = 0;
		int surfaceModelCount = 0;
		int skippedFaceCount = 0;
		for (ExportedGeometry exportedGeometry : exportedGeometries) {
			prepareGeometry(exportedGeometry);
			try (OutputStream output = new FileOutputStream(exportedGeometry.file(), false)) {
				STEPWriter.Result result = STEPWriter.write(exportedGeometry.obj(), exportedGeometry.revolvedSolids(),
						output, exportedGeometry.productName(), exportedGeometry.file().getName());
				solidCount += result.solidCount();
				surfaceModelCount += result.surfaceModelCount();
				skippedFaceCount += result.skippedFaceCount();
			}
		}

		if (surfaceModelCount > 0) {
			warnings.add(Warning.STEP_SURFACE_MODEL);
		}
		return new STEPWriter.Result(solidCount, surfaceModelCount, skippedFaceCount);
	}

	private List<ExportedGeometry> buildGeometry() {
		Set<RocketComponent> componentsToExport = new HashSet<>(components);
		if (options.isExportChildren()) {
			for (RocketComponent component : components) {
				componentsToExport.addAll(component.getAllChildren());
			}
		}

		Set<RocketComponent> sortedComponents = sortComponents(componentsToExport);
		List<ExportedGeometry> exportedGeometries = new ArrayList<>();
		DefaultObj combinedObj = new DefaultObj();
		DefaultObj combinedBounds = new DefaultObj();
		List<STEPRevolvedSolid> combinedRevolvedSolids = new ArrayList<>();
		int componentIndex = 1;

		for (RocketComponent component : sortedComponents) {
			if (component instanceof ComponentAssembly || !configuration.isComponentActive(component)) {
				continue;
			}

			DefaultObj obj = options.isExportAsSeparateFiles() ? new DefaultObj() : combinedObj;
			DefaultObj bounds = options.isExportAsSeparateFiles() ? new DefaultObj() : combinedBounds;
			List<STEPRevolvedSolid> revolvedSolids = options.isExportAsSeparateFiles()
					? new ArrayList<>()
					: combinedRevolvedSolids;
			String groupName = sanitizeGroupName(componentIndex + "_" + component.getName());
			Optional<List<STEPRevolvedSolid>> analyticGeometry = STEPRevolvedSolidFactory.supports(component)
					? STEPRevolvedSolidFactory.create(component, configuration, options.getTransformer(), groupName,
							options.getLevelOfDetail(), options.isExportAllInstances())
					: Optional.empty();
			if (analyticGeometry.isPresent()) {
				for (STEPRevolvedSolid solid : analyticGeometry.get()) {
					revolvedSolids.add(solid);
					solid.addBoundsTo(bounds);
				}
			} else {
				RocketComponentMeshExporter.addComponent(obj, configuration, options.getTransformer(), component,
						groupName, options.getLevelOfDetail(), options.isExportAllInstances(), warnings);
			}
			if (component instanceof MotorMount && options.isExportMotors()) {
				RocketComponentMeshExporter.addMotor(obj, configuration, options.getTransformer(), component, groupName,
						options.getLevelOfDetail(), options.isExportAllInstances(), warnings);
			}

			if (options.isExportAsSeparateFiles() && (obj.getNumFaces() > 0 || !revolvedSolids.isEmpty())) {
				String path = FileUtils.removeExtension(file.getAbsolutePath()) + "_" + groupName + ".step";
				exportedGeometries.add(new ExportedGeometry(obj, revolvedSolids, bounds, new File(path),
						component.getName()));
			}
			componentIndex++;
		}

		if (!options.isExportAsSeparateFiles()
				&& (combinedObj.getNumFaces() > 0 || !combinedRevolvedSolids.isEmpty())) {
			exportedGeometries.add(new ExportedGeometry(combinedObj, combinedRevolvedSolids, combinedBounds, file,
					configuration.getRocket().getName()));
		}
		return exportedGeometries;
	}

	private void prepareGeometry(ExportedGeometry geometry) {
		if (options.isRemoveOffset()) {
			// Include faceted vertices in the bounds already contributed by analytic solids.
			ObjUtils.copyAllVertices(geometry.obj(), geometry.bounds());
			geometry.bounds().recalculateAllVertexBounds();
			FloatTuple offset = ObjUtils.getVertexOffset(geometry.bounds(), options.getTransformer());
			if (geometry.obj().getNumVertices() > 0) {
				ObjUtils.translateVertices(geometry.obj(), 0, geometry.obj().getNumVertices() - 1,
						-offset.getX(), -offset.getY(), -offset.getZ());
			}
			for (int i = 0; i < geometry.revolvedSolids().size(); i++) {
				STEPRevolvedSolid solid = geometry.revolvedSolids().get(i);
				geometry.revolvedSolids().set(i,
						solid.translated(-offset.getX(), -offset.getY(), -offset.getZ()));
			}
		}
	}

	private Set<RocketComponent> sortComponents(Set<RocketComponent> selectedComponents) {
		Set<RocketComponent> sortedComponents = new LinkedHashSet<>();
		addSelectedChildren(configuration.getRocket(), selectedComponents, sortedComponents);
		return sortedComponents;
	}

	private void addSelectedChildren(RocketComponent parent, Set<RocketComponent> selectedComponents,
			Set<RocketComponent> sortedComponents) {
		for (RocketComponent child : parent.getChildren()) {
			if (selectedComponents.contains(child)) {
				sortedComponents.add(child);
			}
			addSelectedChildren(child, selectedComponents, sortedComponents);
		}
	}

	private static String sanitizeGroupName(String groupName) {
		Character illegalCharacter = FileUtils.getIllegalFilenameChar(groupName);
		while (illegalCharacter != null) {
			groupName = groupName.replace(illegalCharacter, '_');
			illegalCharacter = FileUtils.getIllegalFilenameChar(groupName);
		}
		return groupName;
	}

	private record ExportedGeometry(DefaultObj obj, List<STEPRevolvedSolid> revolvedSolids,
			DefaultObj bounds, File file, String productName) {
	}
}
