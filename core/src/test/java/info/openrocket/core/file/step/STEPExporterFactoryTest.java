package info.openrocket.core.file.step;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.util.Modules;
import info.openrocket.core.ServicesForTesting;
import info.openrocket.core.database.ComponentPresetDao;
import info.openrocket.core.database.motor.MotorDatabase;
import info.openrocket.core.document.OpenRocketDocumentFactory;
import info.openrocket.core.file.openrocket.OpenRocketSaverTest;
import info.openrocket.core.l10n.DebugTranslator;
import info.openrocket.core.l10n.Translator;
import info.openrocket.core.logging.Warning;
import info.openrocket.core.logging.WarningSet;
import info.openrocket.core.plugin.PluginModule;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.Bulkhead;
import info.openrocket.core.rocketcomponent.CenteringRing;
import info.openrocket.core.rocketcomponent.EngineBlock;
import info.openrocket.core.rocketcomponent.InnerTube;
import info.openrocket.core.rocketcomponent.NoseCone;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.TrapezoidFinSet;
import info.openrocket.core.rocketcomponent.Transition;
import info.openrocket.core.rocketcomponent.TubeCoupler;
import info.openrocket.core.startup.Application;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class STEPExporterFactoryTest {
	@BeforeAll
	static void setUpApplication() {
		Module applicationModule = new ServicesForTesting();
		Module pluginModule = new PluginModule();
		Module databaseOverrides = new AbstractModule() {
			@Override
			protected void configure() {
				bind(ComponentPresetDao.class).toProvider(new OpenRocketSaverTest.EmptyComponentDbProvider());
				bind(MotorDatabase.class).toProvider(new OpenRocketSaverTest.MotorDbProvider());
				bind(Translator.class).toInstance(new DebugTranslator(null));
			}
		};
		Injector injector = Guice.createInjector(Modules.override(applicationModule).with(databaseOverrides), pluginModule);
		Application.setInjector(injector);
	}

	@Test
	void exportsRepresentativeRocketComponents(@TempDir Path temporaryDirectory) throws Exception {
		Rocket rocket = createRocket();
		Path output = temporaryDirectory.resolve("representative.step");
		STEPExportOptions options = new STEPExportOptions(rocket);
		options.setExportChildren(true);
		options.setRemoveOffset(false);
		WarningSet warnings = new WarningSet();

		STEPExporterFactory exporter = new STEPExporterFactory(List.of(rocket), rocket.getSelectedConfiguration(),
				output.toFile(), options, warnings);
		STEPWriter.Result result = exporter.doExport();
		String step = Files.readString(output, StandardCharsets.US_ASCII);

		assertTrue(result.solidCount() > 0, "Representative finite-thickness components should produce CAD solids");
		assertEquals(0, result.skippedFaceCount());
		assertTrue(Files.size(output) > 0);
		assertTrue(step.contains("PRODUCT('Rocket','Rocket'"));
		assertTrue(step.contains("MANIFOLD_SOLID_BREP('1_Nose Cone'"));
		assertFalse(step.contains("FACETED_BREP('1_Nose Cone'"));
		assertTrue(step.contains("SURFACE_OF_REVOLUTION("));
		assertTrue(step.contains("B_SPLINE_CURVE_WITH_KNOTS("));
		assertTrue(step.contains("MANIFOLD_SOLID_BREP('2_Body Tube'"));
		assertFalse(step.contains("FACETED_BREP('2_Body Tube'"));
		assertTrue(step.contains("CYLINDRICAL_SURFACE("));
		assertTrue(step.contains("CIRCLE("));
		assertTrue(step.contains("FACETED_BREP('3_Fin Set 1'"));
		assertTrue(step.contains("FACETED_BREP('3_Fin Set 2'"));
		assertTrue(step.contains("FACETED_BREP('3_Fin Set 3'"));
		assertTrue(step.contains("END-ISO-10303-21;"));
		assertTrue(warnings.isEmpty(), "Closed finite-thickness geometry should not need surface-model warnings");
	}

	@Test
	void exportsSelectedComponentsAsSeparateFiles(@TempDir Path temporaryDirectory) throws Exception {
		Rocket rocket = createRocket();
		RocketComponent bodyTube = rocket.getStage(0).getChild(1);
		Path requestedOutput = temporaryDirectory.resolve("component.step");
		STEPExportOptions options = new STEPExportOptions(rocket);
		options.setExportAsSeparateFiles(true);
		WarningSet warnings = new WarningSet();

		STEPExporterFactory exporter = new STEPExporterFactory(List.of(bodyTube), rocket.getSelectedConfiguration(),
				requestedOutput.toFile(), options, warnings);
		STEPWriter.Result result = exporter.doExport();

		Path actualOutput = temporaryDirectory.resolve("component_1_Body Tube.step");
		assertFalse(Files.exists(requestedOutput));
		assertTrue(Files.exists(actualOutput));
		assertEquals(1, result.solidCount());
		assertEquals(0, result.surfaceModelCount());
		String step = Files.readString(actualOutput, StandardCharsets.US_ASCII);
		assertTrue(step.contains("PRODUCT('Body Tube','Body Tube'"));
		assertTrue(step.contains("ADVANCED_BREP_SHAPE_REPRESENTATION("));
		assertTrue(step.contains("MANIFOLD_SOLID_BREP('1_Body Tube'"));
		assertTrue(step.contains("CYLINDRICAL_SURFACE("));
		assertTrue(step.contains("CIRCLE("));
		assertFalse(step.contains("FACETED_BREP"));
	}

	@Test
	void exportsInternalTubeAndRingFamiliesAsAnalyticCylinders(@TempDir Path temporaryDirectory) throws Exception {
		Rocket rocket = OpenRocketDocumentFactory.createNewRocket().getRocket();
		BodyTube bodyTube = new BodyTube(0.3, 0.03, 0.002);
		bodyTube.setName("Parent tube");
		rocket.getStage(0).addChild(bodyTube);

		InnerTube innerTube = new InnerTube();
		innerTube.setName("Inner tube");
		innerTube.setLength(0.08);
		innerTube.setOuterRadius(0.01);
		innerTube.setThickness(0.001);
		bodyTube.addChild(innerTube);

		CenteringRing centeringRing = new CenteringRing();
		centeringRing.setName("Centering ring");
		centeringRing.setOuterRadius(0.028);
		centeringRing.setInnerRadius(0.01);
		bodyTube.addChild(centeringRing);

		Bulkhead bulkhead = new Bulkhead();
		bulkhead.setName("Bulkhead");
		bulkhead.setOuterRadius(0.028);
		bodyTube.addChild(bulkhead);

		TubeCoupler tubeCoupler = new TubeCoupler();
		tubeCoupler.setName("Tube coupler");
		tubeCoupler.setOuterRadius(0.028);
		tubeCoupler.setThickness(0.002);
		bodyTube.addChild(tubeCoupler);

		EngineBlock engineBlock = new EngineBlock();
		engineBlock.setName("Engine block");
		engineBlock.setOuterRadius(0.01);
		engineBlock.setThickness(0.003);
		bodyTube.addChild(engineBlock);

		List<RocketComponent> components = List.of(innerTube, centeringRing, bulkhead, tubeCoupler, engineBlock);
		Path output = temporaryDirectory.resolve("internal-rings.step");
		STEPExportOptions options = new STEPExportOptions(rocket);
		options.setRemoveOffset(false);

		STEPExporterFactory exporter = new STEPExporterFactory(components, rocket.getSelectedConfiguration(),
				output.toFile(), options, new WarningSet());
		STEPWriter.Result result = exporter.doExport();
		String step = Files.readString(output, StandardCharsets.US_ASCII);

		assertEquals(5, result.solidCount());
		assertEquals(0, result.surfaceModelCount());
		for (String name : List.of("1_Inner tube", "2_Centering ring", "3_Bulkhead", "4_Tube coupler",
				"5_Engine block")) {
			assertTrue(step.contains("MANIFOLD_SOLID_BREP('" + name + "'"), name);
		}
		assertTrue(step.contains("CYLINDRICAL_SURFACE("));
		assertTrue(step.contains("CIRCLE("));
		assertFalse(step.contains("FACETED_BREP"));
	}

	@Test
	void exportsConicalTransitionAsRevolvedProfile(@TempDir Path temporaryDirectory) throws Exception {
		Rocket rocket = OpenRocketDocumentFactory.createNewRocket().getRocket();
		Transition transition = new Transition();
		transition.setName("Reducer");
		transition.setLength(0.12);
		transition.setForeRadius(0.015);
		transition.setAftRadius(0.03);
		transition.setThickness(0.002);
		transition.setShapeType(Transition.Shape.CONICAL);
		rocket.getStage(0).addChild(transition);
		Path output = temporaryDirectory.resolve("transition.step");
		STEPExportOptions options = new STEPExportOptions(rocket);
		options.setRemoveOffset(false);

		STEPExporterFactory exporter = new STEPExporterFactory(List.of(transition),
				rocket.getSelectedConfiguration(), output.toFile(), options, new WarningSet());
		STEPWriter.Result result = exporter.doExport();
		String step = Files.readString(output, StandardCharsets.US_ASCII);

		assertEquals(1, result.solidCount());
		assertEquals(0, result.surfaceModelCount());
		assertTrue(step.contains("MANIFOLD_SOLID_BREP('1_Reducer'"));
		assertTrue(step.contains("SURFACE_OF_REVOLUTION("));
		assertTrue(step.contains("B_SPLINE_CURVE_WITH_KNOTS('',1,"));
		assertFalse(step.contains("FACETED_BREP"));
	}

	@Test
	void exportsNoseShoulderAsAnalyticTube(@TempDir Path temporaryDirectory) throws Exception {
		Rocket rocket = OpenRocketDocumentFactory.createNewRocket().getRocket();
		NoseCone noseCone = new NoseCone();
		noseCone.setName("Nose with shoulder");
		noseCone.setLength(0.1);
		noseCone.setBaseRadius(0.025);
		noseCone.setThickness(0.002);
		noseCone.setShoulderLength(0.02);
		noseCone.setShoulderRadius(0.022);
		noseCone.setShoulderThickness(0.002);
		rocket.getStage(0).addChild(noseCone);
		Path output = temporaryDirectory.resolve("nose-with-shoulder.step");
		STEPExportOptions options = new STEPExportOptions(rocket);
		options.setRemoveOffset(false);

		STEPExporterFactory exporter = new STEPExporterFactory(List.of(noseCone),
				rocket.getSelectedConfiguration(), output.toFile(), options, new WarningSet());
		STEPWriter.Result result = exporter.doExport();
		String step = Files.readString(output, StandardCharsets.US_ASCII);

		assertEquals(2, result.solidCount());
		assertTrue(step.contains("MANIFOLD_SOLID_BREP('1_Nose with shoulder'"));
		assertTrue(step.contains("MANIFOLD_SOLID_BREP('1_Nose with shoulder aft shoulder'"));
		assertTrue(step.contains("SURFACE_OF_REVOLUTION("));
		assertTrue(step.contains("CYLINDRICAL_SURFACE("));
		assertFalse(step.contains("FACETED_BREP"));
	}

	@Test
	void exportsEveryNoseProfileAsSurfaceOfRevolution(@TempDir Path temporaryDirectory) throws Exception {
		for (Transition.Shape shape : Transition.Shape.values()) {
			Rocket rocket = OpenRocketDocumentFactory.createNewRocket().getRocket();
			NoseCone noseCone = new NoseCone(shape, 0.12, 0.03);
			noseCone.setName(shape.name());
			noseCone.setThickness(0.002);
			rocket.getStage(0).addChild(noseCone);
			Path output = temporaryDirectory.resolve(shape.name() + ".step");
			STEPExportOptions options = new STEPExportOptions(rocket);
			options.setRemoveOffset(false);

			STEPExporterFactory exporter = new STEPExporterFactory(List.of(noseCone),
					rocket.getSelectedConfiguration(), output.toFile(), options, new WarningSet());
			STEPWriter.Result result = exporter.doExport();
			String step = Files.readString(output, StandardCharsets.US_ASCII);

			assertEquals(1, result.solidCount(), shape.name());
			assertTrue(step.contains("SURFACE_OF_REVOLUTION("), shape.name());
			assertTrue(step.contains("B_SPLINE_CURVE_WITH_KNOTS("), shape.name());
			assertFalse(step.contains("FACETED_BREP"), shape.name());
		}
	}

	@Test
	void exportsZeroThicknessComponentAsSurfaceAndWarns(@TempDir Path temporaryDirectory) throws Exception {
		Rocket rocket = createRocket();
		BodyTube bodyTube = (BodyTube) rocket.getStage(0).getChild(1);
		bodyTube.setThickness(0.0);
		Path output = temporaryDirectory.resolve("surface.step");
		STEPExportOptions options = new STEPExportOptions(rocket);
		WarningSet warnings = new WarningSet();

		STEPExporterFactory exporter = new STEPExporterFactory(List.of(bodyTube), rocket.getSelectedConfiguration(),
				output.toFile(), options, warnings);
		STEPWriter.Result result = exporter.doExport();
		String step = Files.readString(output, StandardCharsets.US_ASCII);

		assertEquals(0, result.solidCount());
		assertTrue(result.surfaceModelCount() > 0);
		assertTrue(step.contains("SHELL_BASED_SURFACE_MODEL"));
		assertTrue(warnings.contains(Warning.STEP_SURFACE_MODEL));
	}

	private static Rocket createRocket() {
		Rocket rocket = OpenRocketDocumentFactory.createNewRocket().getRocket();
		rocket.setName("Rocket");
		AxialStage stage = rocket.getStage(0);

		NoseCone noseCone = new NoseCone();
		noseCone.setName("Nose Cone");
		noseCone.setLength(0.1);
		noseCone.setBaseRadius(0.025);
		noseCone.setThickness(0.002);
		stage.addChild(noseCone);

		BodyTube bodyTube = new BodyTube();
		bodyTube.setName("Body Tube");
		bodyTube.setLength(0.3);
		bodyTube.setOuterRadius(0.025);
		bodyTube.setThickness(0.002);
		stage.addChild(bodyTube);

		TrapezoidFinSet finSet = new TrapezoidFinSet();
		finSet.setName("Fin Set");
		finSet.setFinCount(3);
		finSet.setRootChord(0.08);
		finSet.setTipChord(0.04);
		finSet.setHeight(0.05);
		finSet.setSweep(0.02);
		finSet.setThickness(0.003);
		bodyTube.addChild(finSet);
		return rocket;
	}
}
