package tlaTranslation.tests;

import static org.junit.jupiter.api.Assertions.*;

import javax.naming.SizeLimitExceededException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import tlaTranslation.TLATranslator;
import umlTraverse.TraverseModel;

class MachineTranslationTests {
	
	protected String machineTLA = "UNSET";
	protected String environmentTLA = "UNSET";
	private TLATranslator translator;
	
	protected String getMachineSpec(String path) {
		translator = new TLATranslator(new TraverseModel(path));
		
		return translator.createMachineSpec();
	}
	
	protected String getEnvironmentSpec() {
		try {
			return translator.createEnvironmentSpec();
		} catch (SizeLimitExceededException e) {
			e.printStackTrace();
			return "Size Limit Exceeded Exception!";
		}
	}
	
	@Nested
	@TestInstance(Lifecycle.PER_CLASS)
	public class WaterTankTests {
		@BeforeAll
		void setup() {
			machineTLA = getMachineSpec("Diagrams/WaterTank/WaterTank.uml");
			environmentTLA = getEnvironmentSpec();
		}
	
		@Test
		void test() {
			System.out.println(machineTLA);
			System.out.println(environmentTLA);
			fail("Not yet implemented");
		}
	}
	
	@Nested
	@TestInstance(Lifecycle.PER_CLASS)
	public class BSCUTests {
		@BeforeAll
		void setup() {
			machineTLA = getMachineSpec("Diagrams/BSCU/BSCU.uml");
			environmentTLA = getEnvironmentSpec();
		}
	
		@Test
		void test() {
			System.out.println(machineTLA);
			System.out.println(environmentTLA);
			fail("Not yet implemented");
		}
	}

}
