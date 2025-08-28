package tlaTranslation.tests;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.EList;
import org.eclipse.uml2.uml.Behavior;
import org.junit.Before;
import org.junit.jupiter.api.Test;

import tlaTranslation.TLATranslator;
import umlTraverse.TraverseModel;

class MachineTranslationTests {
	
	@Before
	void setup() {
		
	}

	@Test
	void test() {
		System.out.println("Starting tests!");
		TraverseModel tm = new TraverseModel("/Users/kehanna/GitRepos/FASR/FASR_Cameo/Diagrams/WaterTank.uml");
		EList<BasicEList<Behavior>> diagrams = tm.getDiagrams(null);
		TLATranslator translator = new TLATranslator(tm);
		
		translator.createMachineSpec();
		fail("Not yet implemented");
	}

}
