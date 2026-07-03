package info.openrocket.swing.gui.figure3d.flight;

import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.swing.gui.figure3d.animation.FlightPoseProvider;
import info.openrocket.swing.gui.figure3d.animation.PoseProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * UI-independent adapter from simulation output to replay providers.
 */
public final class FlightReplayData {
	private final Map<AxialStage, PoseProvider> providersByStage;
	private final PoseProvider primaryProvider;
	private final double startTime;
	private final double endTime;
	private final List<FlightEvent> allEvents;

	public FlightReplayData(FlightData data, Rocket rocket) {
		if (data == null) {
			throw new IllegalArgumentException("flight data is null");
		}
		if (rocket == null) {
			throw new IllegalArgumentException("rocket is null");
		}
		if (data.getBranchCount() == 0) {
			throw new IllegalArgumentException("flight data has no branches");
		}

		List<BranchProvider> branchProviders = createBranchProviders(data);
		this.primaryProvider = branchProviders.get(0).provider();
		this.providersByStage = Map.copyOf(mapProvidersToStages(rocket, branchProviders));
		this.startTime = branchProviders.stream()
				.mapToDouble(branch -> branch.provider().getStartTime())
				.min()
				.orElse(primaryProvider.getStartTime());
		this.endTime = branchProviders.stream()
				.mapToDouble(branch -> branch.provider().getEndTime())
				.max()
				.orElse(primaryProvider.getEndTime());
		this.allEvents = List.copyOf(collectEvents(data));
	}

	public Map<AxialStage, PoseProvider> getProvidersByStage() {
		return providersByStage;
	}

	public PoseProvider getPrimaryProvider() {
		return primaryProvider;
	}

	public double getStartTime() {
		return startTime;
	}

	public double getEndTime() {
		return endTime;
	}

	public List<FlightEvent> getAllEvents() {
		return allEvents;
	}

	private static List<BranchProvider> createBranchProviders(FlightData data) {
		List<BranchProvider> providers = new ArrayList<>(data.getBranchCount());
		for (FlightDataBranch branch : data.getBranches()) {
			providers.add(new BranchProvider(data.getStageNr(branch), FlightPoseProvider.fromFlightDataBranch(branch)));
		}
		providers.sort(Comparator.comparingInt(BranchProvider::stageNumber));
		return providers;
	}

	private static Map<AxialStage, PoseProvider> mapProvidersToStages(Rocket rocket,
			List<BranchProvider> branchProviders) {
		List<AxialStage> stages = new ArrayList<>(rocket.getStageList());
		stages.sort(Comparator.comparingInt(AxialStage::getStageNumber));

		Map<AxialStage, PoseProvider> result = new LinkedHashMap<>();
		for (AxialStage stage : stages) {
			result.put(stage, findProviderForStage(stage.getStageNumber(), branchProviders));
		}
		return result;
	}

	private static PoseProvider findProviderForStage(int stageNumber, List<BranchProvider> branchProviders) {
		BranchProvider selected = branchProviders.get(0);
		for (BranchProvider branch : branchProviders) {
			if (branch.stageNumber() <= stageNumber && branch.stageNumber() >= selected.stageNumber()) {
				selected = branch;
			}
		}
		return selected.provider();
	}

	private static List<FlightEvent> collectEvents(FlightData data) {
		Map<UUID, FlightEvent> byId = new LinkedHashMap<>();
		for (FlightDataBranch branch : data.getBranches()) {
			for (FlightEvent event : branch.getEvents()) {
				byId.putIfAbsent(event.getID(), event);
			}
		}

		List<FlightEvent> events = new ArrayList<>(byId.values());
		events.sort(FlightEvent::compareTo);
		return events;
	}

	private record BranchProvider(int stageNumber, PoseProvider provider) {
	}
}
