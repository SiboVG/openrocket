package info.openrocket.swing.gui.figure3d.flight;

import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.swing.gui.figure3d.animation.FlightPoseProvider;
import info.openrocket.swing.gui.figure3d.animation.PoseProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
	private final Map<UUID, Double> groundHitByDeploymentId;
	private final Map<AxialStage, List<BurnInterval>> burnIntervalsByStage;
	private final List<BurnInterval> burnIntervals;

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
		this.groundHitByDeploymentId = Map.copyOf(collectDeploymentGroundHits(data));
		this.burnIntervalsByStage = Collections.unmodifiableMap(collectBurnIntervalsByStage(rocket, allEvents, endTime));
		this.burnIntervals = List.copyOf(mergeIntervals(
				burnIntervalsByStage.values().stream().flatMap(List::stream).toList()));
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

	public double getGroundHitTime(FlightEvent deployment, double fallback) {
		if (deployment == null) {
			return fallback;
		}
		return groundHitByDeploymentId.getOrDefault(deployment.getID(), fallback);
	}

	public Map<AxialStage, List<BurnInterval>> getBurnIntervalsByStage() {
		return burnIntervalsByStage;
	}

	public List<BurnInterval> getBurnIntervals() {
		return burnIntervals;
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

	private static Map<UUID, Double> collectDeploymentGroundHits(FlightData data) {
		Map<UUID, Double> result = new LinkedHashMap<>();
		for (FlightDataBranch branch : data.getBranches()) {
			List<FlightEvent> events = new ArrayList<>(branch.getEvents());
			events.sort(FlightEvent::compareTo);
			for (FlightEvent event : events) {
				if (event.getType() != FlightEvent.Type.RECOVERY_DEVICE_DEPLOYMENT) {
					continue;
				}
				for (FlightEvent later : events) {
					if (later.getType() == FlightEvent.Type.GROUND_HIT && later.getTime() >= event.getTime()) {
						result.putIfAbsent(event.getID(), later.getTime());
						break;
					}
				}
			}
		}
		return result;
	}

	private static Map<AxialStage, List<BurnInterval>> collectBurnIntervalsByStage(Rocket rocket,
			List<FlightEvent> events, double replayEndTime) {
		List<AxialStage> stages = new ArrayList<>(rocket.getStageList());
		stages.sort(Comparator.comparingInt(AxialStage::getStageNumber));

		Map<AxialStage, BurnState> statesByStage = new HashMap<>();
		for (AxialStage stage : stages) {
			statesByStage.put(stage, new BurnState());
		}

		for (FlightEvent event : events) {
			if (event.getType() != FlightEvent.Type.IGNITION && event.getType() != FlightEvent.Type.BURNOUT) {
				continue;
			}
			AxialStage stage = stageFor(event.getSource());
			if (stage == null) {
				continue;
			}
			BurnState state = statesByStage.computeIfAbsent(stage, ignored -> new BurnState());
			if (event.getType() == FlightEvent.Type.IGNITION) {
				state.ignite(event.getTime());
			} else {
				state.burnout(event.getTime());
			}
		}

		Map<AxialStage, List<BurnInterval>> result = new LinkedHashMap<>();
		for (AxialStage stage : stages) {
			BurnState state = statesByStage.get(stage);
			if (state == null) {
				result.put(stage, List.of());
				continue;
			}
			state.closeOpenBurn(replayEndTime);
			result.put(stage, List.copyOf(mergeIntervals(state.intervals)));
		}
		return result;
	}

	private static AxialStage stageFor(RocketComponent source) {
		if (source == null) {
			return null;
		}
		if (source instanceof AxialStage stage) {
			return stage;
		}
		try {
			return source.getStage();
		} catch (IllegalStateException e) {
			return null;
		}
	}

	private static List<BurnInterval> mergeIntervals(List<BurnInterval> intervals) {
		if (intervals.isEmpty()) {
			return List.of();
		}
		List<BurnInterval> sorted = new ArrayList<>(intervals);
		sorted.sort(Comparator.comparingDouble(BurnInterval::start));
		List<BurnInterval> merged = new ArrayList<>();
		for (BurnInterval interval : sorted) {
			if (interval.end() < interval.start()) {
				continue;
			}
			if (merged.isEmpty()) {
				merged.add(interval);
				continue;
			}
			BurnInterval last = merged.get(merged.size() - 1);
			if (interval.start() <= last.end()) {
				merged.set(merged.size() - 1,
						new BurnInterval(last.start(), Math.max(last.end(), interval.end())));
			} else {
				merged.add(interval);
			}
		}
		return merged;
	}

	public record BurnInterval(double start, double end) {
		public boolean contains(double time) {
			return time >= start && time <= end;
		}
	}

	private static final class BurnState {
		private final List<BurnInterval> intervals = new ArrayList<>();
		private int activeBurns;
		private double activeStart;

		private void ignite(double time) {
			if (activeBurns == 0) {
				activeStart = time;
			}
			activeBurns++;
		}

		private void burnout(double time) {
			if (activeBurns <= 0) {
				return;
			}
			activeBurns--;
			if (activeBurns == 0) {
				intervals.add(new BurnInterval(activeStart, time));
			}
		}

		private void closeOpenBurn(double replayEndTime) {
			if (activeBurns > 0) {
				intervals.add(new BurnInterval(activeStart, replayEndTime));
				activeBurns = 0;
			}
		}
	}

	private record BranchProvider(int stageNumber, PoseProvider provider) {
	}
}
