package umlTraverse.tests;


import java.util.Arrays;

import org.eclipse.uml2.uml.Dependency;
import org.eclipse.uml2.uml.Element;
import org.eclipse.uml2.uml.Generalization;
import org.eclipse.uml2.uml.NamedElement;
import org.eclipse.uml2.uml.Package;
import org.eclipse.uml2.uml.PackageableElement;
import org.eclipse.uml2.uml.Class;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ucaClassification.DamerauLevenshteinClassifier.Guideword;
import ucaClassification.DamerauLevenshteinClassifier.UnsafeControlAction;
import umlTraverse.SysMLGenerator;
import umlTraverse.TraverseModel;

class UMLTraverseTests {
	TraverseModel waterTankTM;
	TraverseModel testTM;
	
	@BeforeEach
	void setup() {
		this.waterTankTM = new TraverseModel("Diagrams/WaterTank.uml");
		this.testTM = new TraverseModel("Diagrams/modified-model.uml");
	}
	
	boolean comparePackages(Package generatedPackage, Package correctPackage) {
		for(PackageableElement e : generatedPackage.getPackagedElements()) {
			if(e instanceof Dependency) {
				Dependency d = (Dependency) correctPackage.getPackagedElement(e.getName());
				for(NamedElement client : ((Dependency) e).getClients()) {
					if(d.getClient(client.getName()) == null) {
						return false;
					}
				}
				for(Element source : ((Dependency) e).getSuppliers()) {
					if(d.getSupplier(((NamedElement) source).getName()) == null) {
						return false;
					}
				}
			}
			else if(e instanceof Class) {
				Class c = (Class) correctPackage.getPackagedElement(e.getName());
				if(!c.getName().equals(e.getName())) {
					return false;
				}
				if(c != null) {
					for(Generalization g : ((Class) e).getGeneralizations()){
						for(Generalization correctG : c.getGeneralizations()) {
							if(!correctG.getGeneral().getName().equals(g.getGeneral().getName())) {
								return false;
							}
						}
					}
				}
				
			}
			else if(correctPackage.getPackagedElement(e.getName()) == null) {
				return false;
			}
		}
		return true;
	}

	@Test
	void testProviding() {
		var unsafe = Arrays.asList("Init", "Sys.TurnPumpOn", "Wait", "Wait", "Wait", "Sys.TurnPumpOff");
		UnsafeControlAction uca = new UnsafeControlAction("WaterTank", Guideword.PROVIDING, "PumpOnCmd", unsafe, "NoOverFlow");
		SysMLGenerator gen = new SysMLGenerator(uca, waterTankTM);
		Package generatedPackage = gen.generateElements();
		Package correctPackage = this.testTM.getPackageByName("Generated RAAML Package");
		
		
		assert(comparePackages(generatedPackage, correctPackage));
	}
	
	@Test
	void testNotProviding() {
		var unsafe = Arrays.asList("Init", "Sys.TurnPumpOn", "Wait", "Wait", "Wait");
		UnsafeControlAction uca = new UnsafeControlAction("WaterTank", Guideword.NOT_PROVIDING, "PumpOffCmd", unsafe, "NoOverFlow");
		SysMLGenerator gen = new SysMLGenerator(uca, waterTankTM);
		Package correctPackage = this.testTM.getPackageByName("Generated RAAML Package");
		Package generatedPackage = gen.generateElements();
		
		assert(comparePackages(generatedPackage, correctPackage));
	}

}
