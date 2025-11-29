package info.openrocket.core.file.dxf.export;

import java.util.ArrayList;
import java.util.List;

final class DxfTestUtil {
	private DxfTestUtil() {
	}

	static List<List<String>> extractEntities(List<String> lines) {
		int entitiesStart = -1;
		for (int i = 0; i + 3 < lines.size(); i++) {
			if ("0".equals(lines.get(i)) &&
					"SECTION".equals(lines.get(i + 1)) &&
					"2".equals(lines.get(i + 2)) &&
					"ENTITIES".equals(lines.get(i + 3))) {
				entitiesStart = i + 4;
				break;
			}
		}
		if (entitiesStart < 0) {
			return List.of();
		}

		List<List<String>> entities = new ArrayList<>();
		int i = entitiesStart;
		while (i + 1 < lines.size()) {
			String code = lines.get(i);
			String value = lines.get(i + 1);
			if ("0".equals(code) && "ENDSEC".equals(value)) {
				break;
			}
			if (!"0".equals(code)) {
				i += 2;
				continue;
			}

			int start = i;
			i += 2; // skip 0 + entity type
			while (i + 1 < lines.size()) {
				if ("0".equals(lines.get(i))) {
					break;
				}
				i += 2;
			}
			entities.add(lines.subList(start, i));
		}
		return entities;
	}

	static long countEntities(List<String> lines, String entityType) {
		long count = 0;
		for (List<String> entity : extractEntities(lines)) {
			if (entity.size() > 1 && entityType.equals(entity.get(1))) {
				count++;
			}
		}
		return count;
	}

	static List<String> firstEntity(List<String> lines, String entityType) {
		for (List<String> entity : extractEntities(lines)) {
			if (entity.size() > 1 && entityType.equals(entity.get(1))) {
				return entity;
			}
		}
		return List.of();
	}

	static List<double[]> extractLwPolylineVertices(List<String> entityLines) {
		if (entityLines.size() < 2 || !"LWPOLYLINE".equals(entityLines.get(1))) {
			return List.of();
		}

		List<double[]> points = new ArrayList<>();
		Double pendingX = null;
		for (int i = 0; i + 1 < entityLines.size(); i += 2) {
			String code = entityLines.get(i);
			String value = entityLines.get(i + 1);
			if ("10".equals(code)) {
				pendingX = Double.parseDouble(value);
				continue;
			}
			if ("20".equals(code) && pendingX != null) {
				double y = Double.parseDouble(value);
				points.add(new double[] { pendingX, y });
				pendingX = null;
			}
		}
		return points;
	}
}

