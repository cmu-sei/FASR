package edu.cmu.sei.fasr;

import java.awt.event.ActionEvent;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;

import javax.annotation.CheckForNull;
import javax.naming.SizeLimitExceededException;
import javax.swing.KeyStroke;

import com.nomagic.magicdraw.actions.MDAction;
import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.openapi.uml.ModelElementsManager;
import com.nomagic.magicdraw.openapi.uml.SessionManager;
import com.nomagic.uml2.MagicDrawProfile.DiagramTableStereotype;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.jmi.helpers.TagsHelper;

import cmu.s3d.fortis.cli.RobustnessKt;
import kotlin.Pair;

/**
 * 
 */
class FASRAction extends MDAction {

	public FASRAction(@CheckForNull String id, String name, KeyStroke key, String group) {
		super(id, name, key, group);
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		var project = Application.getInstance().getProject();

		if (!SessionManager.getInstance().isSessionCreated(project)) {
			SessionManager.getInstance().createSession(project, "Edit");
		}

		try {
			var pkg = project.getPrimaryModel();
			var tm = new TraverseModel(pkg);
			var translator = new TLATranslator(tm);
			var machine = translator.createMachineSpec();
			var env = getEnvironmentSpec(translator);

			var path = createTempDir(machine.name());
			
			var sysFiles = new ArrayList<Pair<String, String>>();
			var envFiles = new ArrayList<Pair<String, String>>();

			saveSpec(path, machine, sysFiles);
			saveSpec(path, env, envFiles);

			var result = RobustnessKt.computeSTPARobustness(sysFiles, envFiles, "", false, true);
			// clean up files created by tlc
			try (var dirStream = Files.walk(path.resolve("states"))) {
			    dirStream
			        .map(Path::toFile)
			        .sorted(Comparator.reverseOrder())
			        .forEach(File::delete);
			}
			
			var dlc = new DamerauLevenshteinClassifier();
			var ucas = dlc.classifyFortisOutput(result);
			
			var genPkg = new SysMLGenerator(ucas, tm).generateElements(project);
			pkg.getPackagedElement().add(genPkg);

			var eManager = ModelElementsManager.getInstance();
			
			var table = eManager.createDiagram("Unsafe Control Action Table", genPkg);
			table.setName("Unsafe Control Actions");
			
			var diagramTable = StereotypesHelper.getAppliedStereotypeByString(table, "DiagramTable");
			TagsHelper.setStereotypePropertyValue(table, diagramTable, DiagramTableStereotype.SCOPE, genPkg);

			project.getDiagram(table).open();
		} catch (Exception exc) {
			exc.printStackTrace();
		} finally {
			SessionManager.getInstance().closeSession(project);
		}

	}

	private TLATranslator.TLA getEnvironmentSpec(TLATranslator translator) {
		try {
			return translator.createEnvironmentSpec();
		} catch (SizeLimitExceededException e) {
			e.printStackTrace();
			return null;
			// return "Size Limit Exceeded Exception!";
		}
	}

	private Path createTempDir(String name) throws IOException {
		var dir = Files.createTempDirectory(name);
		dir.toFile().deleteOnExit();
		return dir;
	}

	private void saveSpec(Path path, TLATranslator.TLA tla, ArrayList<Pair<String, String>>fileList) {
		try {
			var specPath = path.resolve(tla.name() + ".tla");
			try (BufferedWriter writer = Files.newBufferedWriter(specPath)) {
				writer.write(tla.spec());
			}
			specPath.toFile().deleteOnExit();

			var cfgPath = path.resolve(tla.name() + ".cfg");
			try (BufferedWriter writer = Files.newBufferedWriter(cfgPath)) {
				writer.write(tla.cfg());
			}
			cfgPath.toFile().deleteOnExit();

			fileList.add(new Pair<>(specPath.toString(), cfgPath.toString()));
		} catch (IOException e) {
			System.out.println("Could not write file " + e.getMessage());
		}
	}
	
	String x = """
		[
		  {
		    "goodTrace": [
		      "TurnBSCUOn",
		      "SelfCheck",
		      "SetMode"
		    ],
		    "badTrace": [
		      "TurnBSCUOn",
		      "SelfCheck",
		      "Wait",
		      "Wait",
		      "SetMode"
		    ],
		    "violatingComponents": [
		      ""
		    ],
		    "violatedInvs": []
		  },
		  {
		    "goodTrace": [
		      "TurnBSCUOn",
		      "SelfCheck"
		    ],
		    "badTrace": [
		      "TurnBSCUOn",
		      "Wait",
		      "Wait",
		      "SelfCheck",
		      "SetMode"
		    ],
		    "violatingComponents": [
		      ""
		    ],
		    "violatedInvs": []
		  },
		  {
		    "goodTrace": [
		      "TurnBSCUOn"
		    ],
		    "badTrace": [
		      "Wait",
		      "Wait",
		      "TurnBSCUOn",
		      "SelfCheck",
		      "SetMode"
		    ],
		    "violatingComponents": [
		      ""
		    ],
		    "violatedInvs": []
		  },
		  {
		    "goodTrace": [
		      "TurnBSCUOn",
		      "SelfCheck",
		      "SetMode",
		      "ArmAutobrake"
		    ],
		    "badTrace": [
		      "TurnBSCUOn",
		      "SelfCheck",
		      "SetMode",
		      "Wait",
		      "ArmAutobrake"
		    ],
		    "violatingComponents": [
		      ""
		    ],
		    "violatedInvs": []
		  },
		  {
		    "goodTrace": [
		      "TurnBSCUOn",
		      "SelfCheck",
		      "SetMode"
		    ],
		    "badTrace": [
		      "TurnBSCUOn",
		      "SelfCheck",
		      "Wait",
		      "SetMode",
		      "ArmAutobrake"
		    ],
		    "violatingComponents": [
		      ""
		    ],
		    "violatedInvs": []
		  },
		  {
		    "goodTrace": [
		      "TurnBSCUOn",
		      "SelfCheck"
		    ],
		    "badTrace": [
		      "TurnBSCUOn",
		      "Wait",
		      "SelfCheck",
		      "SetMode",
		      "ArmAutobrake"
		    ],
		    "violatingComponents": [
		      ""
		    ],
		    "violatedInvs": []
		  },
		  {
		    "goodTrace": [
		      "TurnBSCUOn"
		    ],
		    "badTrace": [
		      "Wait",
		      "TurnBSCUOn",
		      "SelfCheck",
		      "SetMode",
		      "ArmAutobrake"
		    ],
		    "violatingComponents": [
		      ""
		    ],
		    "violatedInvs": []
		  },
		  {
		    "goodTrace": [
		      "TurnBSCUOn",
		      "SelfCheck"
		    ],
		    "badTrace": [
		      "TurnBSCUOn",
		      "Wait",
		      "Wait",
		      "Wait",
		      "SelfCheck"
		    ],
		    "violatingComponents": [
		      ""
		    ],
		    "violatedInvs": []
		  },
		  {
		    "goodTrace": [
		      "TurnBSCUOn"
		    ],
		    "badTrace": [
		      "Wait",
		      "Wait",
		      "Wait",
		      "TurnBSCUOn",
		      "SelfCheck"
		    ],
		    "violatingComponents": [
		      ""
		    ],
		    "violatedInvs": []
		  },
		  {
		    "goodTrace": [
		      "TurnBSCUOn"
		    ],
		    "badTrace": [
		      "Wait",
		      "Wait",
		      "Wait",
		      "Wait",
		      "TurnBSCUOn"
		    ],
		    "violatingComponents": [
		      ""
		    ],
		    "violatedInvs": []
		  },
		  {
		    "goodTrace": [
		      "TurnBSCUOn",
		      "SelfCheck",
		      "SetMode",
		      "ArmAutobrake",
		      "SetDecelRate"
		    ],
		    "badTrace": [
		      "TurnBSCUOn",
		      "SelfCheck",
		      "SetMode",
		      "ArmAutobrake",
		      "Wait"
		    ],
		    "violatingComponents": [
		      ""
		    ],
		    "violatedInvs": []
		  },
		  {
		    "goodTrace": [
		      "TurnBSCUOn",
		      "SelfCheck",
		      "SetMode",
		      "ArmAutobrake"
		    ],
		    "badTrace": [
		      "TurnBSCUOn",
		      "SelfCheck",
		      "SetMode",
		      "Wait",
		      "Wait"
		    ],
		    "violatingComponents": [
		      ""
		    ],
		    "violatedInvs": []
		  },
		  {
		    "goodTrace": [
		      "TurnBSCUOn",
		      "SelfCheck",
		      "SetMode"
		    ],
		    "badTrace": [
		      "TurnBSCUOn",
		      "SelfCheck",
		      "Wait",
		      "SetMode",
		      "Wait"
		    ],
		    "violatingComponents": [
		      ""
		    ],
		    "violatedInvs": []
		  },
		  {
		    "goodTrace": [
		      "TurnBSCUOn",
		      "SelfCheck"
		    ],
		    "badTrace": [
		      "TurnBSCUOn",
		      "Wait",
		      "SelfCheck",
		      "SetMode",
		      "Wait"
		    ],
		    "violatingComponents": [
		      ""
		    ],
		    "violatedInvs": []
		  },
		  {
		    "goodTrace": [
		      "TurnBSCUOn"
		    ],
		    "badTrace": [
		      "Wait",
		      "TurnBSCUOn",
		      "SelfCheck",
		      "SetMode",
		      "Wait"
		    ],
		    "violatingComponents": [
		      ""
		    ],
		    "violatedInvs": []
		  },
		  {
		    "goodTrace": [
		      "TurnBSCUOn",
		      "SelfCheck",
		      "SetMode"
		    ],
		    "badTrace": [
		      "TurnBSCUOn",
		      "SelfCheck",
		      "Wait",
		      "Wait",
		      "Wait"
		    ],
		    "violatingComponents": [
		      ""
		    ],
		    "violatedInvs": []
		  },
		  {
		    "goodTrace": [
		      "TurnBSCUOn",
		      "SelfCheck"
		    ],
		    "badTrace": [
		      "TurnBSCUOn",
		      "Wait",
		      "Wait",
		      "SelfCheck",
		      "Wait"
		    ],
		    "violatingComponents": [
		      ""
		    ],
		    "violatedInvs": []
		  },
		  {
		    "goodTrace": [
		      "TurnBSCUOn"
		    ],
		    "badTrace": [
		      "Wait",
		      "Wait",
		      "TurnBSCUOn",
		      "SelfCheck",
		      "Wait"
		    ],
		    "violatingComponents": [
		      ""
		    ],
		    "violatedInvs": []
		  },
		  {
		    "goodTrace": [
		      "TurnBSCUOn",
		      "SelfCheck"
		    ],
		    "badTrace": [
		      "TurnBSCUOn",
		      "Wait",
		      "Wait",
		      "Wait",
		      "Wait"
		    ],
		    "violatingComponents": [
		      ""
		    ],
		    "violatedInvs": []
		  },
		  {
		    "goodTrace": [
		      "TurnBSCUOn"
		    ],
		    "badTrace": [
		      "Wait",
		      "Wait",
		      "Wait",
		      "TurnBSCUOn",
		      "Wait"
		    ],
		    "violatingComponents": [
		      ""
		    ],
		    "violatedInvs": []
		  },
		  {
		    "goodTrace": [
		      "TurnBSCUOn"
		    ],
		    "badTrace": [
		      "Wait",
		      "Wait",
		      "Wait",
		      "Wait",
		      "Wait"
		    ],
		    "violatingComponents": [
		      ""
		    ],
		    "violatedInvs": []
		  }
		]
		""";

}