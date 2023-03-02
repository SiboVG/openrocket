package net.sf.openrocket.preset.xml;

import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementRef;
import javax.xml.bind.annotation.XmlElementRefs;
import javax.xml.bind.annotation.XmlElementWrapper;
import javax.xml.bind.annotation.XmlRootElement;

import net.sf.openrocket.material.Material;
import net.sf.openrocket.preset.ComponentPreset;
import net.sf.openrocket.preset.InvalidComponentPresetException;

/**
 * The real 'root' element in an XML document.
 */
@XmlRootElement(name = "OpenRocketComponent")
@XmlAccessorType(XmlAccessType.FIELD)
public class OpenRocketComponentDTO {

    @XmlElement(name = "Version")
    private final String version = "0.1";

	@XmlElement(name = "Legacy", required = false)
	private String legacy;

    @XmlElementWrapper(name = "Materials")
    @XmlElement(name = "Material")
    List<MaterialDTO> materials = new ArrayList<>();

    @XmlElementWrapper(name = "Components")
    @XmlElementRefs({
            @XmlElementRef(name = "BodyTubes", type = BodyTubeDTO.class),
            @XmlElementRef(name = "TubeCouplers", type = TubeCouplerDTO.class),
            @XmlElementRef(name = "NoseCones", type = NoseConeDTO.class),
            @XmlElementRef(name = "Transitions", type = TransitionDTO.class),
            @XmlElementRef(name = "BulkHeads", type = BulkHeadDTO.class),
            @XmlElementRef(name = "CenteringRings", type = CenteringRingDTO.class),
            @XmlElementRef(name = "EngineBlocks", type = EngineBlockDTO.class),
            @XmlElementRef(name = "LaunchLugs", type = LaunchLugDTO.class),
            @XmlElementRef(name = "RailButtons", type = RailButtonDTO.class),
            @XmlElementRef(name = "Streamers", type = StreamerDTO.class),
            @XmlElementRef(name = "Parachutes", type = ParachuteDTO.class)})
    private List<BaseComponentDTO> components = new ArrayList<>();

    public OpenRocketComponentDTO() {
    }

    public OpenRocketComponentDTO(boolean isLegacy, final List<MaterialDTO> theMaterials, final List<BaseComponentDTO> theComponents) {
		setLegacy(isLegacy);
        materials = theMaterials;
        components = theComponents;
    }

	public Boolean getLegacy() {
		if (null == legacy) {
			return false;
		}
		return true;
	}

	public void setLegacy(Boolean isLegacy) {
		if (isLegacy) {
			legacy = "";
		} else {
			legacy = null;
		}
	}
		
    public List<MaterialDTO> getMaterials() {
        return materials;
    }

    public void addMaterial(final MaterialDTO theMaterial) {
        materials.add(theMaterial);
    }

    public void setMaterials(final List<MaterialDTO> theMaterials) {
        materials = theMaterials;
    }

    public List<BaseComponentDTO> getComponents() {
        return components;
    }

    public void addComponent(final BaseComponentDTO theComponent) {
        components.add(theComponent);
    }

    public void setComponents(final List<BaseComponentDTO> theComponents) {
        components = theComponents;
    }

    public List<ComponentPreset> asComponentPresets() throws InvalidComponentPresetException {
        List<ComponentPreset> result = new ArrayList<>(components.size());
        for (BaseComponentDTO component : components) {
            result.add(component.asComponentPreset(getLegacy(), materials));
        }
        return result;
    }
    
    public List<Material> asMaterialList() {
    	List<Material> result = new ArrayList<>(materials.size());
    	for( MaterialDTO material : materials ) {
    		result.add( material.asMaterial() );
    	}
    	return result;
    }
}
