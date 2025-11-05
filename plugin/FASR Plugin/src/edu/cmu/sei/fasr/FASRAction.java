package edu.cmu.sei.fasr;

import java.awt.event.ActionEvent;
import java.util.ArrayList;

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

		var pkg = project.getPrimaryModel();

		var translator = new TLATranslator(new TraverseModel(pkg));
		var mSpec = translator.createMachineSpec();
		var eSpec = getEnvironmentSpec(translator);
		
		var sysFiles = new ArrayList<Pair<String, String>>();
		var envFiles = new ArrayList<Pair<String, String>>();
		
		sysFiles.add(new Pair<>(mSpec, mSpec.substring(0, mSpec.length() - 3) + "cfg"));
		envFiles.add(new Pair<>(eSpec, eSpec.substring(0, eSpec.length() - 3) + "cfg"));
		
		var result = RobustnessKt.computeSTPARobustness(sysFiles, envFiles, false);
		
		SessionManager.getInstance().closeSession(project);

//		JOptionPane.showMessageDialog(MDDialogParentProvider.getProvider().getDialogOwner(),
//				"This is: " + pkg.getName());
	}

	protected String getEnvironmentSpec(TLATranslator translator) {
		try {
			return translator.createEnvironmentSpec();
		} catch (SizeLimitExceededException e) {
			e.printStackTrace();
			return "Size Limit Exceeded Exception!";
		}
	}

}