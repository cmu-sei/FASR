package edu.cmu.sei.fasr;

import java.awt.event.ActionEvent;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.Stream;

import javax.annotation.CheckForNull;
import javax.naming.SizeLimitExceededException;
import javax.swing.KeyStroke;

import com.nomagic.magicdraw.actions.MDAction;
import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.openapi.uml.SessionManager;

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

			var translator = new TLATranslator(new TraverseModel(pkg));
			var machine = translator.createMachineSpec();
			var env = getEnvironmentSpec(translator);

			var path = createTempDir(machine.name());
			
			var sysFiles = new ArrayList<Pair<String, String>>();
			var envFiles = new ArrayList<Pair<String, String>>();

			saveSpec(path, machine, sysFiles);
			saveSpec(path, env, envFiles);

			var result = RobustnessKt.computeSTPARobustness(sysFiles, envFiles, false);
			
			// clean up files created by tlc
			try (var dirStream = Files.walk(path.resolve("states"))) {
			    dirStream
			        .map(Path::toFile)
			        .sorted(Comparator.reverseOrder())
			        .forEach(File::delete);
			}

			System.out.println(result);

		} catch (Exception exc) {
			exc.printStackTrace();
		} finally {
			SessionManager.getInstance().closeSession(project);
		}

//		JOptionPane.showMessageDialog(MDDialogParentProvider.getProvider().getDialogOwner(),
//				"This is: " + pkg.getName());
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

}