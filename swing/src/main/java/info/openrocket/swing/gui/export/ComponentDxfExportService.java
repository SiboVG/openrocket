package info.openrocket.swing.gui.export;

import info.openrocket.core.file.dxf.export.DXFBuilder;
import info.openrocket.core.file.dxf.export.DXFExportOptions;
import info.openrocket.core.file.dxf.export.RingDxfExporter;
import info.openrocket.core.rocketcomponent.Bulkhead;
import info.openrocket.core.rocketcomponent.CenteringRing;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.InnerTube;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.position.AxialMethod;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class ComponentDxfExportService {
	private ComponentDxfExportService() {
	}

	public static void exportFinSet(FinSet finSet, File destination, DXFExportOptions options)
			throws IOException {
		DXFBuilder builder = new DXFBuilder();
		info.openrocket.core.file.dxf.export.FinDxfExporter.drawFinSet(finSet, builder, 0, 0, options);
		builder.writeToFile(destination);
	}

	public static void exportCenteringRing(CenteringRing ring, File destination, DXFExportOptions options)
			throws IOException {
		DXFBuilder builder = new DXFBuilder();
		List<InnerTube> mounts = findSupportingMotorMounts(ring);
		RingDxfExporter.drawCenteringRing(ring, builder, options,
				RingDxfExporter.holesFromMotorMounts(mounts));
		builder.writeToFile(destination);
	}

	public static void exportBulkhead(Bulkhead bulkhead, File destination, DXFExportOptions options)
			throws IOException {
		DXFBuilder builder = new DXFBuilder();
		RingDxfExporter.drawBulkhead(bulkhead, builder, options, java.util.Collections.emptyList());
		builder.writeToFile(destination);
	}

	public static List<InnerTube> findSupportingMotorMounts(CenteringRing ring) {
		List<InnerTube> mounts = new ArrayList<>();
		if (ring == null) {
			return mounts;
		}
		RocketComponent parent = ring.getParent();
		if (parent == null) {
			return mounts;
		}
		for (RocketComponent sibling : parent.getChildren()) {
			if (sibling == ring || !(sibling instanceof InnerTube)) {
				continue;
			}
			InnerTube tube = (InnerTube) sibling;
			if (overlaps(ring, tube)) {
				mounts.add(tube);
			}
		}
		return mounts;
	}

	private static boolean overlaps(CenteringRing ring, InnerTube tube) {
		double ringTop = ring.getAxialOffset(AxialMethod.ABSOLUTE);
		double ringBottom = ringTop + ring.getLength();
		double tubeTop = tube.getAxialOffset(AxialMethod.ABSOLUTE);
		double tubeBottom = tubeTop + tube.getLength();
		return ringTop <= tubeBottom && tubeTop <= ringBottom;
	}
}

