package info.openrocket.core.file.step;

import info.openrocket.core.file.wavefrontobj.CoordTransform;
import info.openrocket.core.file.wavefrontobj.DefaultCoordTransform;
import info.openrocket.core.file.wavefrontobj.ObjUtils;
import info.openrocket.core.rocketcomponent.Rocket;

/**
 * Options controlling the conversion of rocket-component geometry to STEP.
 *
 * <p>STEP geometry is always written in millimetres.  The coordinate transform
 * controls the right-handed axis mapping before that unit conversion is applied.</p>
 */
public class STEPExportOptions {
	private boolean exportChildren;
	private boolean exportAllInstances;
	private boolean exportMotors;
	private boolean exportAsSeparateFiles;
	private boolean removeOffset;
	private ObjUtils.LevelOfDetail levelOfDetail;
	private CoordTransform transformer;

	/**
	 * Creates options with CAD-oriented defaults.
	 *
	 * @param rocket rocket whose length defines the default coordinate transform
	 */
	public STEPExportOptions(Rocket rocket) {
		this.exportChildren = false;
		this.exportAllInstances = true;
		this.exportMotors = false;
		this.exportAsSeparateFiles = false;
		this.removeOffset = true;
		this.levelOfDetail = ObjUtils.LevelOfDetail.NORMAL_QUALITY;
		this.transformer = new DefaultCoordTransform(rocket.getLength());
	}

	public boolean isExportChildren() {
		return exportChildren;
	}

	public void setExportChildren(boolean exportChildren) {
		this.exportChildren = exportChildren;
	}

	public boolean isExportAllInstances() {
		return exportAllInstances;
	}

	public void setExportAllInstances(boolean exportAllInstances) {
		this.exportAllInstances = exportAllInstances;
	}

	public boolean isExportMotors() {
		return exportMotors;
	}

	public void setExportMotors(boolean exportMotors) {
		this.exportMotors = exportMotors;
	}

	public boolean isExportAsSeparateFiles() {
		return exportAsSeparateFiles;
	}

	public void setExportAsSeparateFiles(boolean exportAsSeparateFiles) {
		this.exportAsSeparateFiles = exportAsSeparateFiles;
	}

	public boolean isRemoveOffset() {
		return removeOffset;
	}

	public void setRemoveOffset(boolean removeOffset) {
		this.removeOffset = removeOffset;
	}

	public ObjUtils.LevelOfDetail getLevelOfDetail() {
		return levelOfDetail;
	}

	public void setLevelOfDetail(ObjUtils.LevelOfDetail levelOfDetail) {
		this.levelOfDetail = levelOfDetail;
	}

	public CoordTransform getTransformer() {
		return transformer;
	}

	public void setTransformer(CoordTransform transformer) {
		this.transformer = transformer;
	}
}
