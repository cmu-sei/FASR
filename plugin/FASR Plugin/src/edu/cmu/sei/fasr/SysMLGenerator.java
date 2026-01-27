/**
 * FASR Source Code
 * 
 * Copyright 2025 Carnegie Mellon University.
 * 
 * NO WARRANTY. THIS CARNEGIE MELLON UNIVERSITY AND SOFTWARE ENGINEERING
 * INSTITUTE MATERIAL IS FURNISHED ON AN "AS-IS" BASIS. CARNEGIE MELLON 
 * UNIVERSITY MAKES NO WARRANTIES OF ANY KIND, EITHER EXPRESSED OR IMPLIED, AS
 * TO ANY MATTER INCLUDING, BUT NOT LIMITED TO, WARRANTY OF FITNESS FOR PURPOSE
 * OR MERCHANTABILITY, EXCLUSIVITY, OR RESULTS OBTAINED FROM USE OF THE 
 * MATERIAL. CARNEGIE MELLON UNIVERSITY DOES NOT MAKE ANY WARRANTY OF ANY KIND
 * WITH RESPECT TO FREEDOM FROM PATENT, TRADEMARK, OR COPYRIGHT INFRINGEMENT.
 * 
 * Licensed under a MIT (SEI)-style license, please see license.txt or contact
 * permission@sei.cmu.edu for full terms.
 * 
 * [DISTRIBUTION STATEMENT A] This material has been approved for public 
 * release and unlimited distribution.  Please see Copyright notice for non-US
 * Government use and distribution.
 * 
 * DM25-0946
 */

package edu.cmu.sei.fasr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com.nomagic.magicdraw.core.Project;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.classes.mddependencies.Dependency;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Generalization;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Namespace;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdcommunications.Signal;
import com.nomagic.uml2.ext.magicdraw.metadata.UMLFactory;

import edu.cmu.sei.fasr.DamerauLevenshteinClassifier.UnsafeControlAction;

public class SysMLGenerator {
	private List<UnsafeControlAction> actionList;
	TraverseModel tm;

	public SysMLGenerator(Collection<UnsafeControlAction> actions, TraverseModel tm) {
		this.actionList = new ArrayList<UnsafeControlAction>();
		this.actionList.addAll(actions);
		this.tm = tm;
	}

	public SysMLGenerator(UnsafeControlAction action, TraverseModel tm) {
		this.actionList = new ArrayList<UnsafeControlAction>();
		this.actionList.add(action);
		this.tm = tm;
	}

	public Package generateElements(Project project) {
		// Used to create signals
		UMLFactory factory = UMLFactory.eINSTANCE;
		// Check if package has already been generated
		// If so, remove it
		EObject oldPkg = findOwnedMember(tm.getModel(), "Generated RAAML Package");
		if (oldPkg instanceof Package) {
			EcoreUtil.delete(oldPkg, true);
		}

		var stpaProfile = StereotypesHelper.getProfileByURI(project, "https://www.omg.org/spec/RAAML/20211101/STPA");
		var coreProfile = StereotypesHelper.getProfileByURI(project, "https://www.omg.org/spec/RAAML/20211101/Core");

		// Create package and add it to model
		Package p = factory.createPackage();
		p.setName("Generated RAAML Package");
		this.tm.getModel().getPackagedElement().add(p);

		for (UnsafeControlAction u : this.actionList) {
			String newContext = String.join("->", u.context());
			// Create class that will be the block for UnsafeControlAction
			Class c = factory.createClass();
			p.getPackagedElement().add(c);
			c.setName(newContext);
			c.setAbstract(false);

			var st = StereotypesHelper.getStereotype(project, "UnsafeControlAction", stpaProfile);
			StereotypesHelper.addStereotype(c, st);

			// Create signal (ControlAction) and correctly stereotype it
			Signal signal = p.getPackagedElement().stream().filter(Signal.class::isInstance).map(Signal.class::cast)
					.filter(s -> u.controlAction().equals(s.getName())).findFirst() // if signal exists, then use this
					.orElseGet(() -> factory.createSignal()); // create the signal
			signal.setName(u.controlAction());
			p.getPackagedElement().add(signal);

			var signalStereotype = StereotypesHelper.getStereotype(project, "ControlAction", stpaProfile);
			if (StereotypesHelper.canApplyStereotype(signal, signalStereotype)) {
				StereotypesHelper.addStereotype(signal, signalStereotype);
			}

			// Create connection from UnsafeControlAction to ControlAction
			Dependency d = factory.createDependency();
			p.getPackagedElement().add(d);
			d.setName(signal.getName() + "_dependency");
			d.getClient().add(c);
			d.getSupplier().add(signal);

			var relevantTo = StereotypesHelper.getStereotype(project, "RelevantTo", coreProfile);
			StereotypesHelper.addStereotype(d, relevantTo);

			// Switch statement that will create the connection from our UnsafeControlAction
			// to the correct UCA Guideword
			Class keyword;
			switch (u.guideword()) {
			case PROVIDING:
				keyword = (Class) findByQualifiedName("STPA Library::Provided");
				break;
			case NOT_PROVIDING:
				keyword = (Class) findByQualifiedName("STPA Library::NotProvided");
				break;
			case TOO_EARLY:
				keyword = (Class) findByQualifiedName("STPA Library::Early");
				break;
			case TOO_LATE:
				keyword = (Class) findByQualifiedName("STPA Library::Late");
				break;
			case OUT_OF_SEQUENCE:
				keyword = (Class) findByQualifiedName("STPA Library::OutOfSequence");
				break;
			case APPLIED_TOO_LONG:
				keyword = (Class) findByQualifiedName("STPA Library::TooLong");
				break;
			case STOPPED_TOO_SOON:
				keyword = (Class) findByQualifiedName("STPA Library::TooShort");
				break;
			default:
				keyword = null;
				break;
			}
			if (keyword == null) {
				throw new NullPointerException("STPA Library was not found!");
			}

			Generalization gen = factory.createGeneralization();
			c.getOwnedElement().add(gen);
			gen.setSpecific(c);
			gen.setGeneral(keyword);
		}
		return p;
	}

	public NamedElement findByQualifiedName(String qualifiedName) {
		var root = tm.getModel();
		String[] segments = qualifiedName.split("::");
		Namespace current = root;

		if (qualifiedName == null || !qualifiedName.contains("::")) {
			return findOwnedMember(root, qualifiedName);
		}

		// traverse through qualified name searching root for ownedMember
		// if ownedMember is found, search its ownedMembers for matching NamedElement
		for (int i = 0; i < segments.length; i++) {
			NamedElement next = findOwnedMember(current, segments[i]);
			if (next instanceof Namespace) {
				current = (Namespace) next;
			} else if (i == segments.length - 1) {
				return next;
			}
		}
		if (current == root) {
			return null;
		}
		return current;
	}

	private NamedElement findOwnedMember(Namespace ns, String name) {
		return ns.getOwnedMember().stream().filter(m -> m.getName().contentEquals(name)).findFirst().orElse(null);
	}
}
