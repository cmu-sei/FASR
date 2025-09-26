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

package umlTraverse;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.uml2.uml.Package;
import org.eclipse.uml2.uml.Signal;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.uml2.uml.Class;
import org.eclipse.uml2.uml.Dependency;
import org.eclipse.uml2.uml.NamedElement;
import org.eclipse.uml2.uml.Namespace;
import org.eclipse.uml2.uml.Stereotype;
import org.eclipse.uml2.uml.UMLFactory;

//import ucaClassification.DamerauLevenshteinClassifier.Guideword;
import ucaClassification.DamerauLevenshteinClassifier.UnsafeControlAction;

public class SysMLGenerator {
	private List<UnsafeControlAction> actionList;
	TraverseModel tm;
	
	public SysMLGenerator(List<UnsafeControlAction> actionList, TraverseModel tm) {
		this.actionList = actionList;
		this.tm = tm;
	}
	
	public SysMLGenerator(UnsafeControlAction action, TraverseModel tm) {
		this.actionList = new ArrayList<UnsafeControlAction>();
		this.actionList.add(action);
		this.tm = tm;
	}
	
	public NamedElement findByQualifiedName(String qualifiedName) {
		Package root = tm.getModel();
		String[] segments = qualifiedName.split("::");
	    Namespace current = root;
	    
	    if (qualifiedName == null || !qualifiedName.contains("::")) {
	        return root.getOwnedMember(qualifiedName);
	    }
	    
	    // traverse through qualified name searching root for ownedMember
	    // if ownedMember is found, search its ownedMembers for matching NamedElement
	    for (int i = 0; i < segments.length; i++) {
	        NamedElement next = current.getOwnedMember(segments[i]);
	        if (next instanceof Namespace) {
	            current = (Namespace) next;
	        } else if (i == segments.length - 1) {
	            return next;
	        }
	    }
	    if(current == root) {
	    	return null;
	    }
	    return (NamedElement) current;
	}
	
	public Package generateElements() {
		// Used to create signals
		UMLFactory factory = UMLFactory.eINSTANCE;
		// Check if package has already been generated
		// If so, remove it
		EObject oldPkg = tm.getModel().getOwnedMember("TestingPackage");
		if (oldPkg instanceof Package) {
		    EcoreUtil.delete(oldPkg, true);
		}

		// Create package and add it to model
		Package p = factory.createPackage();
		p.setName("Generated RAAML Package");
		this.tm.getModel().getPackagedElements().add(p);
		Class keyword;
		for(UnsafeControlAction u : this.actionList) {
			String newContext = String.join("->", u.context());
			// Create class that will be the block for UnsafeControlAction
			Class c = p.createOwnedClass(newContext, false);
			p.getPackagedElements().add(c);
			Stereotype st = c.getApplicableStereotype("STPA Profile::UnsafeControlAction");
			c.applyStereotype(st);
				
			// Create signal (ControlAction) and correctly stereotype it
			Signal signal = p.getPackagedElements().stream()
					.filter(Signal.class::isInstance)
					.map(Signal.class::cast)
					.filter(s -> u.controlAction().equals(s.getName()))
					.findFirst() // if signal exists, then use this
					.orElseGet(() -> factory.createSignal()); // create the signal
			signal.setName(u.controlAction());
			p.getPackagedElements().add(signal);
			Stereotype singalStereotype = signal.getApplicableStereotype("STPA Profile::ControlAction");
			signal.getApplicableStereotypes();
			// if the signal previously existed, then you cannot re-apply a stereotype
			try {
				signal.applyStereotype(singalStereotype);
			} catch (IllegalArgumentException e) {
				System.out.println(signal.getName() + "Already exists");
			}
			

			// Create connection from UnsafeControlAction to ControlAction
			Dependency d = c.createDependency(signal);
			d.setName(signal.getName() + "_dependency");
			Stereotype relventTo = d.getApplicableStereotype("Core Profile::RelevantTo");
			d.applyStereotype(relventTo);
				
			// Switch statement that will create the connection from our UnsafeControlAction to the correct UCA Guideword 
			switch(u.guideword()) {
				case PROVIDING:
					keyword = (Class) findByQualifiedName("Model::CMOF 2.0 Validation::STPA Library::Provided");
					break;
				case NOT_PROVIDING:
					keyword = (Class) findByQualifiedName("Model::CMOF 2.0 Validation::STPA Library::NotProvided");
					break;
				case TOO_EARLY:
					keyword = (Class) findByQualifiedName("Model::CMOF 2.0 Validation::STPA Library::Early");
					break;
				case TOO_LATE:
					keyword = (Class) findByQualifiedName("Model::CMOF 2.0 Validation::STPA Library::Late");
					break;
				case OUT_OF_SEQUENCE:
					keyword = (Class) findByQualifiedName("Model::CMOF 2.0 Validation::STPA Library::OutOfSequence");
					break;
				case APPLIED_TOO_LONG:
					keyword = (Class) findByQualifiedName("Model::CMOF 2.0 Validation::STPA Library::TooLong");
					break;
				case STOPPED_TOO_SOON:
					keyword = (Class) findByQualifiedName("Model::CMOF 2.0 Validation::STPA Library::TooShort");
					break;
				default:
					keyword = null;
					break;
			}
			if(keyword == null) {
				throw new NullPointerException("STPA Library was not found!");
			}
			c.createGeneralization(keyword);
		}
		return p;
	}
}
