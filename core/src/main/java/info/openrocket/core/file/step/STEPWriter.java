package info.openrocket.core.file.step;

import de.javagl.obj.FloatTuple;
import de.javagl.obj.ObjFace;
import de.javagl.obj.ObjGroup;
import info.openrocket.core.file.wavefrontobj.DefaultObj;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Writes hybrid analytic and polygonal geometry as ISO 10303-21 STEP files
 * using the AP214 schema.
 *
 * <p>Axisymmetric component geometry becomes an advanced B-rep made from
 * cylindrical or revolved surfaces.  Remaining closed, consistently oriented
 * polygon shells become {@code FACETED_BREP} solids.  Open, non-manifold, or
 * zero-volume shells become {@code SHELL_BASED_SURFACE_MODEL} items instead.</p>
 */
public final class STEPWriter {
	private static final int REFERENCES_PER_LINE = 12;

	private STEPWriter() {
		// Utility class.
	}

	/**
	 * Summary of the topology written to a STEP file.
	 *
	 * @param solidCount number of closed analytic and faceted B-rep solids
	 * @param surfaceModelCount number of open or non-solid shell models
	 * @param skippedFaceCount number of degenerate input faces that were omitted
	 */
	public record Result(int solidCount, int surfaceModelCount, int skippedFaceCount) {
	}

	/**
	 * Writes a STEP file using the current timestamp.
	 *
	 * @param obj source polygon mesh
	 * @param output destination stream, which remains owned by the caller
	 * @param productName human-readable STEP product name
	 * @param fileName file name recorded in the STEP header
	 * @return topology summary for warnings and diagnostics
	 * @throws IOException if the stream cannot be written
	 */
	public static Result write(DefaultObj obj, OutputStream output, String productName, String fileName)
			throws IOException {
		return write(obj, List.of(), output, productName, fileName, OffsetDateTime.now());
	}

	/**
	 * Timestamp-injectable writer used by deterministic tests.
	 */
	static Result write(DefaultObj obj, OutputStream output, String productName, String fileName,
			OffsetDateTime timestamp) throws IOException {
		return write(obj, List.of(), output, productName, fileName, timestamp);
	}

	/**
	 * Writes a hybrid STEP model containing advanced and faceted B-reps.
	 */
	static Result write(DefaultObj obj, List<STEPRevolvedSolid> revolvedSolids, OutputStream output,
			String productName, String fileName) throws IOException {
		return write(obj, revolvedSolids, output, productName, fileName, OffsetDateTime.now());
	}

	/**
	 * Timestamp-injectable hybrid writer used by deterministic tests.
	 */
	static Result write(DefaultObj obj, List<STEPRevolvedSolid> revolvedSolids, OutputStream output,
			String productName, String fileName, OffsetDateTime timestamp) throws IOException {
		List<NamedFaceSet> faceSets = collectFaceSets(obj, productName);
		if (revolvedSolids.isEmpty()
				&& faceSets.stream().allMatch(faceSet -> faceSet.faces().isEmpty())) {
			throw new IllegalArgumentException("The model contains no exportable geometry");
		}

		EntityTable entities = new EntityTable();
		int applicationContext = entities.add("APPLICATION_CONTEXT('automotive design')");
		entities.add("APPLICATION_PROTOCOL_DEFINITION('international standard','automotive_design',2003,#"
				+ applicationContext + ")");
		int productContext = entities.add("PRODUCT_CONTEXT('',#" + applicationContext + ",'mechanical')");
		int product = entities.add("PRODUCT(" + stepString(productName) + "," + stepString(productName)
				+ ",'',(#" + productContext + "))");
		int formation = entities.add("PRODUCT_DEFINITION_FORMATION('1','',#" + product + ")");
		int definitionContext = entities.add("PRODUCT_DEFINITION_CONTEXT('part definition',#"
				+ applicationContext + ",'design')");
		int definition = entities.add("PRODUCT_DEFINITION('design','',#" + formation + ",#"
				+ definitionContext + ")");
		int definitionShape = entities.add("PRODUCT_DEFINITION_SHAPE('',$,#" + definition + ")");

		int lengthUnit = entities.add("(\nLENGTH_UNIT()\nNAMED_UNIT(*)\nSI_UNIT(.MILLI.,.METRE.)\n)");
		int planeAngleUnit = entities.add("(\nNAMED_UNIT(*)\nPLANE_ANGLE_UNIT()\nSI_UNIT($,.RADIAN.)\n)");
		int solidAngleUnit = entities.add("(\nNAMED_UNIT(*)\nSI_UNIT($,.STERADIAN.)\nSOLID_ANGLE_UNIT()\n)");
		int uncertainty = entities.add("UNCERTAINTY_MEASURE_WITH_UNIT(LENGTH_MEASURE(1.E-5),#"
				+ lengthUnit + ",'DISTANCE_ACCURACY_VALUE','Maximum model-space distance uncertainty')");
		int representationContext = entities.add("(\nGEOMETRIC_REPRESENTATION_CONTEXT(3)\n"
				+ "GLOBAL_UNCERTAINTY_ASSIGNED_CONTEXT((#" + uncertainty + "))\n"
				+ "GLOBAL_UNIT_ASSIGNED_CONTEXT((#" + lengthUnit + ",#" + planeAngleUnit + ",#"
				+ solidAngleUnit + "))\nREPRESENTATION_CONTEXT('','3D')\n)");

		int origin = entities.add("CARTESIAN_POINT('',(0.,0.,0.))");
		int axis = entities.add("DIRECTION('',(0.,0.,1.))");
		int referenceDirection = entities.add("DIRECTION('',(1.,0.,0.))");
		int placement = entities.add("AXIS2_PLACEMENT_3D('global coordinates',#" + origin + ",#"
				+ axis + ",#" + referenceDirection + ")");

		Map<VertexKey, Integer> pointEntities = new LinkedHashMap<>();
		List<Integer> representationItems = new ArrayList<>();
		int solidCount = revolvedSolids.size();
		int surfaceModelCount = 0;
		int skippedFaceCount = 0;

		if (!revolvedSolids.isEmpty()) {
			STEPAdvancedBrepWriter advancedBrepWriter = new STEPAdvancedBrepWriter(entities);
			for (STEPRevolvedSolid revolvedSolid : revolvedSolids) {
				representationItems.add(advancedBrepWriter.write(revolvedSolid));
			}
		}

		for (NamedFaceSet faceSet : faceSets) {
			skippedFaceCount += faceSet.skippedFaceCount();
			List<Shell> shells = splitIntoShells(faceSet);
			for (int shellIndex = 0; shellIndex < shells.size(); shellIndex++) {
				Shell shell = shells.get(shellIndex);
				String shellName = shells.size() == 1
						? faceSet.name()
						: faceSet.name() + " " + (shellIndex + 1);
				List<Integer> faceEntities = writeFaces(entities, shell.faces(), pointEntities);

				if (shell.closedSolid()) {
					int closedShell = entities.add("CLOSED_SHELL(" + stepString(shellName) + ",("
							+ references(faceEntities) + "))");
					int facetedBrep = entities.add("FACETED_BREP(" + stepString(shellName) + ",#"
							+ closedShell + ")");
					representationItems.add(facetedBrep);
					solidCount++;
				} else {
					int openShell = entities.add("OPEN_SHELL(" + stepString(shellName) + ",("
							+ references(faceEntities) + "))");
					int surfaceModel = entities.add("SHELL_BASED_SURFACE_MODEL(" + stepString(shellName)
							+ ",(#" + openShell + "))");
					representationItems.add(surfaceModel);
					surfaceModelCount++;
				}
			}
		}

		List<Integer> shapeItems = new ArrayList<>();
		shapeItems.add(placement);
		shapeItems.addAll(representationItems);
		String representationType = revolvedSolids.isEmpty() || surfaceModelCount > 0
				? "SHAPE_REPRESENTATION"
				: "ADVANCED_BREP_SHAPE_REPRESENTATION";
		int shapeRepresentation = entities.add(representationType + "('',("
				+ references(shapeItems) + "),#" + representationContext + ")");
		entities.add("SHAPE_DEFINITION_REPRESENTATION(#" + definitionShape + ",#"
				+ shapeRepresentation + ")");

		Writer writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.US_ASCII));
		writeHeader(writer, fileName, timestamp);
		entities.write(writer);
		writer.write("ENDSEC;\nEND-ISO-10303-21;\n");
		writer.flush();

		return new Result(solidCount, surfaceModelCount, skippedFaceCount);
	}

	private static void writeHeader(Writer writer, String fileName, OffsetDateTime timestamp) throws IOException {
		writer.write("ISO-10303-21;\nHEADER;\n");
		writer.write("FILE_DESCRIPTION(('OpenRocket component geometry'),'2;1');\n");
		writer.write("FILE_NAME(" + stepString(fileName) + ","
				+ stepString(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(timestamp))
				+ ",('OpenRocket'),('OpenRocket'),'OpenRocket STEP exporter','OpenRocket','');\n");
		writer.write("FILE_SCHEMA(('AUTOMOTIVE_DESIGN'));\nENDSEC;\nDATA;\n");
	}

	private static List<Integer> writeFaces(EntityTable entities, List<MeshFace> faces,
			Map<VertexKey, Integer> pointEntities) {
		List<Integer> faceEntities = new ArrayList<>(faces.size());
		for (MeshFace face : faces) {
			List<Integer> loopPoints = new ArrayList<>(face.vertices().size());
			for (Vertex vertex : face.vertices()) {
				Integer point = pointEntities.get(vertex.key());
				if (point == null) {
					point = entities.add("CARTESIAN_POINT('',(" + formatMillimetres(vertex.x()) + ","
							+ formatMillimetres(vertex.y()) + "," + formatMillimetres(vertex.z()) + "))");
					pointEntities.put(vertex.key(), point);
				}
				loopPoints.add(point);
			}

			int loop = entities.add("POLY_LOOP('',(" + references(loopPoints) + "))");
			int outerBound = entities.add("FACE_OUTER_BOUND('',#" + loop + ",.T.)");
			Vector3 normal = normalizedNewellVector(face.vertices());
			Vector3 referenceDirection = findReferenceDirection(face.vertices(), normal);
			int normalDirection = entities.add("DIRECTION('',(" + formatReal(normal.x()) + ","
					+ formatReal(normal.y()) + "," + formatReal(normal.z()) + "))");
			int inPlaneDirection = entities.add("DIRECTION('',(" + formatReal(referenceDirection.x()) + ","
					+ formatReal(referenceDirection.y()) + "," + formatReal(referenceDirection.z()) + "))");
			int planePlacement = entities.add("AXIS2_PLACEMENT_3D('',#" + loopPoints.get(0) + ",#"
					+ normalDirection + ",#" + inPlaneDirection + ")");
			int plane = entities.add("PLANE('',#" + planePlacement + ")");
			faceEntities.add(entities.add("FACE_SURFACE('',(#" + outerBound + "),#" + plane + ",.T.)"));
		}
		return faceEntities;
	}

	private static List<NamedFaceSet> collectFaceSets(DefaultObj obj, String fallbackName) {
		List<NamedFaceSet> faceSets = new ArrayList<>();
		Set<ObjFace> assignedFaces = Collections.newSetFromMap(new IdentityHashMap<>());

		for (ObjGroup group : obj.getGroups()) {
			List<ObjFace> groupFaces = new ArrayList<>();
			for (int i = 0; i < group.getNumFaces(); i++) {
				ObjFace face = group.getFace(i);
				if (assignedFaces.add(face)) {
					groupFaces.add(face);
				}
			}
			if (!groupFaces.isEmpty()) {
				faceSets.add(createFaceSet(obj, normalizedName(group.getName(), fallbackName), groupFaces));
			}
		}

		List<ObjFace> ungroupedFaces = new ArrayList<>();
		for (ObjFace face : obj.getFaces()) {
			if (assignedFaces.add(face)) {
				ungroupedFaces.add(face);
			}
		}
		if (!ungroupedFaces.isEmpty()) {
			faceSets.add(createFaceSet(obj, normalizedName(fallbackName, "OpenRocket component"), ungroupedFaces));
		}

		return faceSets;
	}

	private static NamedFaceSet createFaceSet(DefaultObj obj, String name, List<ObjFace> objFaces) {
		Map<VertexKey, Vertex> weldedVertices = new LinkedHashMap<>();
		List<MeshFace> faces = new ArrayList<>(objFaces.size());
		int skippedFaces = 0;

		for (ObjFace objFace : objFaces) {
			MeshFace face = createFace(obj, objFace, weldedVertices);
			if (face == null) {
				skippedFaces++;
			} else {
				faces.addAll(createPlanarFacets(face));
			}
		}
		return new NamedFaceSet(name, faces, skippedFaces);
	}

	/**
	 * Keeps planar polygon faces intact and splits warped polygons into triangles.
	 * Component mesh exporters use quads to approximate curved surfaces, while a
	 * STEP faceted B-rep requires every individual face to be planar.
	 */
	private static List<MeshFace> createPlanarFacets(MeshFace face) {
		if (face.vertices().size() == 3 || isPlanar(face.vertices())) {
			return List.of(face);
		}

		List<MeshFace> triangles = new ArrayList<>(face.vertices().size() - 2);
		Vertex first = face.vertices().get(0);
		for (int i = 1; i < face.vertices().size() - 1; i++) {
			List<Vertex> triangle = List.of(first, face.vertices().get(i), face.vertices().get(i + 1));
			if (newellVector(triangle).squaredMagnitude() > 0.0) {
				triangles.add(new MeshFace(triangle));
			}
		}
		return triangles;
	}

	private static boolean isPlanar(List<Vertex> vertices) {
		Vector3 normal = normalizedNewellVector(vertices);
		Vertex first = vertices.get(0);
		double maximumCoordinate = 0.0;
		for (Vertex vertex : vertices) {
			maximumCoordinate = Math.max(maximumCoordinate, Math.abs(vertex.x()));
			maximumCoordinate = Math.max(maximumCoordinate, Math.abs(vertex.y()));
			maximumCoordinate = Math.max(maximumCoordinate, Math.abs(vertex.z()));
		}
		double tolerance = Math.max(1.0E-9, maximumCoordinate * 1.0E-7);

		for (Vertex vertex : vertices) {
			Vector3 offset = new Vector3(vertex.x() - first.x(), vertex.y() - first.y(), vertex.z() - first.z());
			if (Math.abs(offset.dot(normal)) > tolerance) {
				return false;
			}
		}
		return true;
	}

	private static MeshFace createFace(DefaultObj obj, ObjFace objFace, Map<VertexKey, Vertex> weldedVertices) {
		List<Vertex> vertices = new ArrayList<>(objFace.getNumVertices());
		Set<VertexKey> seenVertices = new LinkedHashSet<>();
		for (int i = 0; i < objFace.getNumVertices(); i++) {
			FloatTuple tuple = obj.getVertex(objFace.getVertexIndex(i));
			if (!Float.isFinite(tuple.getX()) || !Float.isFinite(tuple.getY()) || !Float.isFinite(tuple.getZ())) {
				return null;
			}

			VertexKey key = VertexKey.of(tuple.getX(), tuple.getY(), tuple.getZ());
			if (seenVertices.add(key)) {
				Vertex vertex = weldedVertices.computeIfAbsent(key,
						unused -> new Vertex(key, normalizedZero(tuple.getX()), normalizedZero(tuple.getY()),
								normalizedZero(tuple.getZ())));
				vertices.add(vertex);
			}
		}

		if (vertices.size() < 3 || newellVector(vertices).squaredMagnitude() == 0.0) {
			return null;
		}
		return new MeshFace(List.copyOf(vertices));
	}

	private static List<Shell> splitIntoShells(NamedFaceSet faceSet) {
		if (faceSet.faces().isEmpty()) {
			return List.of();
		}

		UnionFind unionFind = new UnionFind(faceSet.faces().size());
		Map<EdgeKey, List<Integer>> edgeFaces = new LinkedHashMap<>();
		for (int faceIndex = 0; faceIndex < faceSet.faces().size(); faceIndex++) {
			int currentFaceIndex = faceIndex;
			MeshFace face = faceSet.faces().get(faceIndex);
			forEachEdge(face, (start, end) -> edgeFaces.computeIfAbsent(EdgeKey.of(start.key(), end.key()),
					unused -> new ArrayList<>()).add(currentFaceIndex));
		}

		for (List<Integer> sharingFaces : edgeFaces.values()) {
			int firstFace = sharingFaces.get(0);
			for (int i = 1; i < sharingFaces.size(); i++) {
				unionFind.union(firstFace, sharingFaces.get(i));
			}
		}

		Map<Integer, List<MeshFace>> shellFaces = new LinkedHashMap<>();
		for (int faceIndex = 0; faceIndex < faceSet.faces().size(); faceIndex++) {
			int root = unionFind.find(faceIndex);
			shellFaces.computeIfAbsent(root, unused -> new ArrayList<>()).add(faceSet.faces().get(faceIndex));
		}

		List<Shell> shells = new ArrayList<>(shellFaces.size());
		for (List<MeshFace> faces : shellFaces.values()) {
			shells.add(new Shell(List.copyOf(faces), isClosedSolid(faces)));
		}
		return shells;
	}

	private static boolean isClosedSolid(List<MeshFace> faces) {
		Map<EdgeKey, EdgeUse> edgeUses = new HashMap<>();
		for (MeshFace face : faces) {
			forEachEdge(face, (start, end) -> {
				EdgeKey key = EdgeKey.of(start.key(), end.key());
				EdgeUse use = edgeUses.computeIfAbsent(key, unused -> new EdgeUse());
				use.count++;
				use.orientationBalance += key.first().equals(start.key()) ? 1 : -1;
			});
		}

		boolean closedAndOriented = edgeUses.values().stream()
				.allMatch(use -> use.count == 2 && use.orientationBalance == 0);
		return closedAndOriented && hasEnclosedVolume(faces);
	}

	private static boolean hasEnclosedVolume(List<MeshFace> faces) {
		double signedVolumeTimesSix = 0.0;
		double minX = Double.POSITIVE_INFINITY;
		double minY = Double.POSITIVE_INFINITY;
		double minZ = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY;
		double maxY = Double.NEGATIVE_INFINITY;
		double maxZ = Double.NEGATIVE_INFINITY;

		for (MeshFace face : faces) {
			Vertex origin = face.vertices().get(0);
			for (Vertex vertex : face.vertices()) {
				double x = millimetres(vertex.x());
				double y = millimetres(vertex.y());
				double z = millimetres(vertex.z());
				minX = Math.min(minX, x);
				minY = Math.min(minY, y);
				minZ = Math.min(minZ, z);
				maxX = Math.max(maxX, x);
				maxY = Math.max(maxY, y);
				maxZ = Math.max(maxZ, z);
			}
			for (int i = 1; i < face.vertices().size() - 1; i++) {
				signedVolumeTimesSix += scalarTripleProduct(origin, face.vertices().get(i),
						face.vertices().get(i + 1));
			}
		}

		double maximumDimension = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
		double volumeToleranceTimesSix = Math.max(1.0E-12, maximumDimension * maximumDimension
				* maximumDimension * 1.0E-12) * 6.0;
		return Math.abs(signedVolumeTimesSix) > volumeToleranceTimesSix;
	}

	private static double scalarTripleProduct(Vertex first, Vertex second, Vertex third) {
		double firstX = millimetres(first.x());
		double firstY = millimetres(first.y());
		double firstZ = millimetres(first.z());
		double secondX = millimetres(second.x());
		double secondY = millimetres(second.y());
		double secondZ = millimetres(second.z());
		double thirdX = millimetres(third.x());
		double thirdY = millimetres(third.y());
		double thirdZ = millimetres(third.z());

		return firstX * (secondY * thirdZ - secondZ * thirdY)
				- firstY * (secondX * thirdZ - secondZ * thirdX)
				+ firstZ * (secondX * thirdY - secondY * thirdX);
	}

	private static Vector3 normalizedNewellVector(List<Vertex> vertices) {
		return newellVector(vertices).normalized();
	}

	private static Vector3 newellVector(List<Vertex> vertices) {
		double normalX = 0.0;
		double normalY = 0.0;
		double normalZ = 0.0;
		for (int i = 0; i < vertices.size(); i++) {
			Vertex current = vertices.get(i);
			Vertex next = vertices.get((i + 1) % vertices.size());
			normalX += (current.y() - next.y()) * (current.z() + next.z());
			normalY += (current.z() - next.z()) * (current.x() + next.x());
			normalZ += (current.x() - next.x()) * (current.y() + next.y());
		}
		return new Vector3(normalX, normalY, normalZ);
	}

	private static Vector3 findReferenceDirection(List<Vertex> vertices, Vector3 normal) {
		Vertex first = vertices.get(0);
		for (int i = 1; i < vertices.size(); i++) {
			Vertex candidate = vertices.get(i);
			Vector3 edge = new Vector3(candidate.x() - first.x(), candidate.y() - first.y(),
					candidate.z() - first.z());
			double normalComponent = edge.dot(normal);
			Vector3 projected = edge.subtract(normal.scale(normalComponent));
			if (projected.squaredMagnitude() > 1.0E-20) {
				return projected.normalized();
			}
		}
		throw new IllegalArgumentException("Cannot determine an in-plane direction for a degenerate face");
	}

	private static void forEachEdge(MeshFace face, EdgeConsumer consumer) {
		for (int i = 0; i < face.vertices().size(); i++) {
			consumer.accept(face.vertices().get(i), face.vertices().get((i + 1) % face.vertices().size()));
		}
	}

	static String references(List<Integer> entityReferences) {
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < entityReferences.size(); i++) {
			if (i > 0) {
				builder.append(',');
				if (i % REFERENCES_PER_LINE == 0) {
					builder.append('\n');
				}
			}
			builder.append('#').append(entityReferences.get(i));
		}
		return builder.toString();
	}

	private static String normalizedName(String name, String fallback) {
		return name == null || name.isBlank() ? fallback : name;
	}

	private static String formatMillimetres(float metres) {
		BigDecimal value = new BigDecimal(Float.toString(normalizedZero(metres))).movePointRight(3)
				.stripTrailingZeros();
		String text = value.toPlainString();
		return text.indexOf('.') >= 0 ? text : text + ".";
	}

	static String formatMillimetres(double metres) {
		return formatDecimal(metres * 1000.0);
	}

	static String formatReal(double value) {
		return formatDecimal(value);
	}

	private static String formatDecimal(double value) {
		double normalizedValue = Math.abs(value) < 1.0E-15 ? 0.0 : value;
		BigDecimal decimal = BigDecimal.valueOf(normalizedValue).setScale(12, RoundingMode.HALF_UP)
				.stripTrailingZeros();
		String text = decimal.toPlainString();
		return text.indexOf('.') >= 0 ? text : text + ".";
	}

	static String stepBoolean(boolean value) {
		return value ? ".T." : ".F.";
	}

	private static double millimetres(float metres) {
		return metres * 1000.0;
	}

	private static float normalizedZero(float value) {
		return value == 0.0f ? 0.0f : value;
	}

	/**
	 * Encodes a Java string using ISO 10303-21 string escapes.
	 */
	static String stepString(String value) {
		String safeValue = value == null ? "" : value;
		StringBuilder builder = new StringBuilder(safeValue.length() + 2);
		builder.append('\'');
		boolean unicodeBlock = false;
		for (int i = 0; i < safeValue.length(); i++) {
			char character = safeValue.charAt(i);
			boolean basicCharacter = character >= 0x20 && character <= 0x7e;
			if (basicCharacter) {
				if (unicodeBlock) {
					builder.append("\\X0\\");
					unicodeBlock = false;
				}
				if (character == '\'') {
					builder.append("''");
				} else if (character == '\\') {
					builder.append("\\\\");
				} else {
					builder.append(character);
				}
			} else if (Character.isISOControl(character)) {
				if (unicodeBlock) {
					builder.append("\\X0\\");
					unicodeBlock = false;
				}
				builder.append(' ');
			} else {
				if (!unicodeBlock) {
					builder.append("\\X2\\");
					unicodeBlock = true;
				}
				appendHexCharacter(builder, character);
			}
		}
		if (unicodeBlock) {
			builder.append("\\X0\\");
		}
		return builder.append('\'').toString();
	}

	private static void appendHexCharacter(StringBuilder builder, char character) {
		String hex = Integer.toHexString(character).toUpperCase(Locale.ROOT);
		builder.append("0".repeat(4 - hex.length())).append(hex);
	}

	private record VertexKey(int xBits, int yBits, int zBits) implements Comparable<VertexKey> {
		private static VertexKey of(float x, float y, float z) {
			return new VertexKey(Float.floatToIntBits(normalizedZero(x)), Float.floatToIntBits(normalizedZero(y)),
					Float.floatToIntBits(normalizedZero(z)));
		}

		@Override
		public int compareTo(VertexKey other) {
			int comparison = Integer.compare(xBits, other.xBits);
			if (comparison == 0) {
				comparison = Integer.compare(yBits, other.yBits);
			}
			if (comparison == 0) {
				comparison = Integer.compare(zBits, other.zBits);
			}
			return comparison;
		}
	}

	private record Vertex(VertexKey key, float x, float y, float z) {
	}

	private record Vector3(double x, double y, double z) {
		private double squaredMagnitude() {
			return x * x + y * y + z * z;
		}

		private Vector3 normalized() {
			double magnitude = Math.sqrt(squaredMagnitude());
			return new Vector3(x / magnitude, y / magnitude, z / magnitude);
		}

		private double dot(Vector3 other) {
			return x * other.x + y * other.y + z * other.z;
		}

		private Vector3 subtract(Vector3 other) {
			return new Vector3(x - other.x, y - other.y, z - other.z);
		}

		private Vector3 scale(double factor) {
			return new Vector3(x * factor, y * factor, z * factor);
		}
	}

	private record MeshFace(List<Vertex> vertices) {
	}

	private record NamedFaceSet(String name, List<MeshFace> faces, int skippedFaceCount) {
	}

	private record Shell(List<MeshFace> faces, boolean closedSolid) {
	}

	private record EdgeKey(VertexKey first, VertexKey second) {
		private static EdgeKey of(VertexKey first, VertexKey second) {
			return first.compareTo(second) <= 0 ? new EdgeKey(first, second) : new EdgeKey(second, first);
		}
	}

	private static final class EdgeUse {
		private int count;
		private int orientationBalance;
	}

	@FunctionalInterface
	private interface EdgeConsumer {
		void accept(Vertex start, Vertex end);
	}

	private static final class UnionFind {
		private final int[] parents;

		private UnionFind(int size) {
			this.parents = new int[size];
			for (int i = 0; i < size; i++) {
				parents[i] = i;
			}
		}

		private int find(int value) {
			if (parents[value] != value) {
				parents[value] = find(parents[value]);
			}
			return parents[value];
		}

		private void union(int first, int second) {
			int firstRoot = find(first);
			int secondRoot = find(second);
			if (firstRoot != secondRoot) {
				parents[secondRoot] = firstRoot;
			}
		}
	}

	static final class EntityTable {
		private final List<String> definitions = new ArrayList<>();

		int add(String definition) {
			definitions.add(definition);
			return definitions.size();
		}

		private void write(Writer writer) throws IOException {
			for (int i = 0; i < definitions.size(); i++) {
				writer.write("#" + (i + 1) + "=" + definitions.get(i) + ";\n");
			}
		}
	}
}
