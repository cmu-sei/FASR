package tlaTranslation.tests;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import tlaTranslation.TLATranslator;
import umlTraverse.TraverseModel;

class MachineTranslationTests {
	
	protected String tla = "UNSET";
	
	protected String getMachineSpec(String path) {
		TLATranslator translator = new TLATranslator(new TraverseModel(path));
		
		return translator.createMachineSpec();
	}
	
	@Nested
	@TestInstance(Lifecycle.PER_CLASS)
	public class WaterTankTests {
		@BeforeAll
		void setup() {
			tla = getMachineSpec("Diagrams/WaterTank/WaterTank.uml");
		}
	
		@Test
		void test() {
			System.out.println(tla);
			fail("Not yet implemented");
		}
	}
	
	@Nested
	@TestInstance(Lifecycle.PER_CLASS)
	public class BSCUTests {
		@BeforeAll
		void setup() {
			tla = getMachineSpec("Diagrams/BSCU/BSCU.uml");
		}
	
		@Test
		void test() {
			System.out.println(tla);
			fail("Not yet implemented");
		}
	}

}
