package edu.cmu.sei.fasr;

import com.nomagic.magicdraw.actions.MDAction;
import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.openapi.uml.SessionManager;
import com.nomagic.magicdraw.ui.dialogs.MDDialogParentProvider;

import javax.annotation.CheckForNull;
import javax.naming.SizeLimitExceededException;
import javax.swing.*;
import java.awt.event.ActionEvent;

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

		SessionManager.getInstance().closeSession(project);

		JOptionPane.showMessageDialog(MDDialogParentProvider.getProvider().getDialogOwner(),
				"This is: " + pkg.getName());
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