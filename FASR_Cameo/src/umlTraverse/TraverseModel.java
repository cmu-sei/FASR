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

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.xmi.XMIResource;
import org.eclipse.uml2.uml.Activity;
import org.eclipse.uml2.uml.ActivityEdge;
import org.eclipse.uml2.uml.ActivityNode;
import org.eclipse.uml2.uml.Behavior;
import org.eclipse.uml2.uml.Element;
import org.eclipse.uml2.uml.InitialNode;
import org.eclipse.uml2.uml.PackageableElement;
import org.eclipse.uml2.uml.Pseudostate;
import org.eclipse.uml2.uml.PseudostateKind;
import org.eclipse.uml2.uml.Region;
import org.eclipse.uml2.uml.Signal;
import org.eclipse.uml2.uml.SignalEvent;
import org.eclipse.uml2.uml.State;
import org.eclipse.uml2.uml.StateMachine;
import org.eclipse.uml2.uml.Stereotype;
import org.eclipse.uml2.uml.Transition;
import org.eclipse.uml2.uml.UMLPackage;
import org.eclipse.uml2.uml.Package;
import org.eclipse.uml2.uml.resources.util.UMLResourcesUtil;

public class TraverseModel {
		private Package model;
		private ResourceSet resources;
		private URI modelURI;
		/*
		 * Class constructor
		 */
		public TraverseModel() {
			this.model = null;
		}
		
		/*
		 * Class constructor specifying project path
		 * @param path 	specifies path to UML file to load
		 */
		public TraverseModel(String path) {
			this.model = loadModel(path);
		}
		

		/*
		 * Loads the UML model into memory
		 * 
		 * @param 	path	specifies path to UML file to load
		 * @return			a Package object containing the model
		 */
		public Package loadModel(String path) {
			this.resources = new ResourceSetImpl();
			UMLResourcesUtil.init(resources);
			Resource modelResource; 
			
			try {
				this.modelURI  = URI.createFileURI(path);
				modelResource = resources.getResource(modelURI  , true);
                EcoreUtil.resolveAll(modelResource);
			} catch (RuntimeException e) {
                System.err.println(e.getMessage());
                return null;
			}
			Package umlPackage = (Package) EcoreUtil.getObjectByType(modelResource.getContents(),UMLPackage.Literals.PACKAGE);
			EcoreUtil.resolveAll(umlPackage);
			return umlPackage;
		}
		
		
		/*
		 * Returns a list of StateMachines from a project
		 * 
		 * @param	p	a PackageableElement
		 * @return		a list of state machines
		 */
		public EList<BasicEList<Behavior>> getDiagrams(PackageableElement p) {
			EList<BasicEList<Behavior>> result = new BasicEList<BasicEList<Behavior>>();
			BasicEList<Behavior> machineList = new BasicEList<Behavior>();
			BasicEList<Behavior> environmentList = new BasicEList<Behavior>();
			
			for(Element e : p.allOwnedElements()) {
				if(e instanceof StateMachine) {
					EList<Stereotype> i = ((StateMachine) e).getAppliedStereotypes();
					for(Stereotype stereotype : i) {
						if(stereotype.getName().equals("Machine")) {
							machineList.add((StateMachine) e);
						}
					}
				} else if( e instanceof Activity) {
					EList<Stereotype> i = ((Activity) e).getAppliedStereotypes();
					for(Stereotype stereotype : i) {
						if (stereotype.getName().equals("Environment")){
								environmentList.add((Activity) e);
						} else if (stereotype.getName().equals("Machine")) {
							// If the activity is specifically used to just send signals for the State Machine Diagram 
							machineList.add((Activity) e);
						}
					}
				}
			}
			result.add(machineList);
			result.add(environmentList);
			return result;
		}
		
		public EList<ActivityNode> getAllNodesFromActivity(Activity a){
			EList<ActivityNode> result = new BasicEList<ActivityNode>();
			
			for(Element e : a.allOwnedElements()) {
				if(e instanceof ActivityNode) {
					result.add((ActivityNode)e);
				}
			}
			return result;
		}
		
		/*
		 * Returns a list of States from a StateMachine
		 * 
		 * @param	sm	a StateMachine
		 * @return		a list of states
		 */
		public EList<State> getAllStatesFromStateMachine(StateMachine sm){
			EList<State> result = new BasicEList<State>();
			for(Element e : sm.allOwnedElements()) {
				if(e instanceof State) {
					result.add((State) e);
				}
			}
			return result;
		}
		
		/*
		 * Returns the starting state of a StateMachine
		 * 
		 * @param 	sm	a StateMachine
		 * @return		a State
		 */
		public State getFirstState(StateMachine sm) {
			// Get all states and find which one is transitioned to from InitialState
			for(State s : getAllStatesFromStateMachine(sm)) {
				for(Transition t : getTransitionsToState(s, Boolean.TRUE)) {
					// see if the state is being transitioned to from a Pseudostate
					if(t.getSource() instanceof Pseudostate) {
						// see if the Pseudostate is PseudostateKind.INITIAL
						if(((Pseudostate) t.getSource()).getKind() == PseudostateKind.get("initial")){
							return s;
						}
						
						System.out.println(((Pseudostate) t.getSource()).getKind().getValue());
					}
				}
			}
			System.out.println("Initial state not found!");
			return null;
		}
		
		
		/*
		 * Returns the the InitialNode of an Activity
		 * 
		 *  @param	a	an Activity
		 *  @return		an ActivityNode
		 */
		public ActivityNode getFirstActivityNode(Activity a) {
			for(ActivityNode n : getAllNodesFromActivity(a)) {
				for(ActivityEdge ae : n.getIncomings()) {
					if (ae.getSource() instanceof InitialNode) {
						return ae.getSource();
					}
				}
			}
			return null;
		}
		
		/*
		 *  Gets all transitions where the given state is the target 
		 *  If getPseudostates is true, then all transitions are returned
		 *  If getPseudostates is false, then transitions that include a Pseudostate are excluded
		 *  
		 *  @param	s				a state
		 *  @param	getPseudostate	a boolean determining if pseudostates will be included
		 *  @return					a list of transitions
		 */
		public EList<Transition> getTransitionsToState(State s, Boolean getPseudostates){
			EList<Transition> result = new BasicEList<Transition>();
			Region region;
			if(s.eContainer() instanceof Region) {
				region = (Region) s.eContainer();
			}else {
				System.out.println("Unable to find region!");
				return null;
			}
			
			for(Transition t : region.getTransitions()) {
				if(t.getTarget() == s) {
					if(!getPseudostates && t.getSource() instanceof Pseudostate) {
						continue;
					}
					result.add(t);
				}
			}

			return result;
		}
		
		/*
		 * Gets all transitions where the given state is the source
		 * 
		 *  @param s	a state
		 *  @return		a list of transitions
		 */
		public EList<Transition> getTransitionsFromState(State s){
			EList<Transition> result = new BasicEList<Transition>();
			Region region;
			if(s.eContainer() instanceof Region) {
				region = (Region) s.eContainer();
			}else {
				System.out.println("Unable to find region!");
				return null;
			}
			
			for(Transition t : region.getTransitions()) {
				if(t.getSource() == s) {
					result.add(t);
				}
			}

			return result;
		}
		
		/*
		 * Returns a single Trigger for a given Transition
		 * Only cares about getting the signal 
		 * 
		 * @param t		a transition
		 * @return		a signal
		 */
		public Signal getTransitionTrigger(Transition t) {
			// A transition can only have one trigger
			SignalEvent s = (SignalEvent) t.getTriggers().get(0).getEvent();
			s.getSignal().getName();
			return s.getSignal();
		}
		
		/*
		 * A getter that returns the UML model
		 * 
		 * @return	a loaded UML model
		 */
		public Package getModel() {
			return this.model;
		}
		
		public boolean updateUML() {
			Map<String, Object> options = new HashMap<>();
			options.put(XMIResource.OPTION_ENCODING, "UTF-8");
			options.put(XMIResource.OPTION_SAVE_TYPE_INFORMATION, Boolean.TRUE);
			options.put(XMIResource.OPTION_SCHEMA_LOCATION, Boolean.TRUE);
			
			Resource resource = resources.getResource(modelURI  , true);

			try {
			    resource.save(options);
			} catch (IOException e) {
			    e.printStackTrace();
			    return false;
			}
			return true;
		}
		
		public void exportModel() {
			Map<String, Object> options = new HashMap<>();
			options.put(XMIResource.OPTION_ENCODING, "UTF-8");
			options.put(XMIResource.OPTION_SAVE_TYPE_INFORMATION, Boolean.TRUE);
			options.put(XMIResource.OPTION_SCHEMA_LOCATION, Boolean.TRUE);
			
			URI newUri = URI.createFileURI("/Users/kehanna/GitRepos/fasr/TestUML/Diagrams/modified-model.uml");
			Resource newResource = this.resources.createResource(newUri);
			Resource modelResource = resources.getResource(modelURI  , true);
			newResource.getContents().add(modelResource.getContents().get(0)); // add the root element

			try {
				newResource.save(options);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			System.out.println("Exported modified model to: " + newUri);

		}
		

}
