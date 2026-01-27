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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.xmi.XMIResource;

import com.nomagic.uml2.ext.magicdraw.activities.mdbasicactivities.ActivityEdge;
import com.nomagic.uml2.ext.magicdraw.activities.mdbasicactivities.InitialNode;
import com.nomagic.uml2.ext.magicdraw.activities.mdfundamentalactivities.Activity;
import com.nomagic.uml2.ext.magicdraw.activities.mdfundamentalactivities.ActivityNode;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.PackageableElement;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdbasicbehaviors.Behavior;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdbasicbehaviors.OpaqueBehavior;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdcommunications.Signal;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdcommunications.SignalEvent;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.Pseudostate;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.PseudostateKindEnum;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.Region;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.State;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.StateMachine;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.Transition;

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
		public TraverseModel(Package model) {
			this.model = model;
		}
		

		/*
		 * Returns a list of StateMachines from a project
		 * 
		 * @param	p	a PackageableElement
		 * @return		a list of state machines
		 */
		public List<List<Behavior>> getDiagrams(PackageableElement p) {
			if(p == null) {
				p = this.model;
			}
			List<List<Behavior>> result = new ArrayList<List<Behavior>>();
			List<Behavior> machineList = new ArrayList<Behavior>();
			List<Behavior> environmentList = new ArrayList<Behavior>();
			
			for (TreeIterator<EObject> iter = EcoreUtil.getAllContents(p, true); iter.hasNext(); ) {
				var e = iter.next();
				
				if(e instanceof StateMachine sm) {
					List<Stereotype> i = sm.getAppliedStereotype();
					for(Stereotype stereotype : i) {
						if(stereotype.getName().equals("Machine")) {
							machineList.add(sm);
						}
					}
				} else if( e instanceof Activity a) {
					List<Stereotype> i = a.getAppliedStereotype();
					for(Stereotype stereotype : i) {
						if (stereotype.getName().equals("Environment")){
								environmentList.add(a);
						} else if (stereotype.getName().equals("Machine")) {
							// If the activity is specifically used to just send signals for the State Machine Diagram 
							machineList.add(a);
						}
					}
				}
			}
			
			result.add(machineList);
			result.add(environmentList);
			return result;
		}
		
		public Package getPackageByName(String packageName) {
			Package result = null; 
			for(var p : this.model.getNestedPackage()) {
				if(p.getName().equals(packageName)) {
					result = p;
					break;
				}
			}
			return result;
		}
		
		public List<ActivityNode> getAllNodesFromActivity(Activity a){
			List<ActivityNode> result = new ArrayList<>();
			
			for (TreeIterator<EObject> iter = EcoreUtil.getAllContents(a, true); iter.hasNext(); ) {
				var e = iter.next();
				if(e instanceof ActivityNode an) {
					result.add(an);
				}
			}
			return result;
		}
		
		/*
		 * Returns a list of regions from a StateMachine. We assume, but do not check, that there is 
		 * one composite state
		 * 
		 * @param	sm	a StateMachine
		 * @return		a list of Regions
		 */
		public List<Region> getAllRegionsFromStateMachine(StateMachine sm){
			List<Region> result = new ArrayList<>();
			for (TreeIterator<EObject> iter = EcoreUtil.getAllContents(sm, true); iter.hasNext(); ) {
				var e = iter.next();
				if(e instanceof Region r) {
					result.add(r);
				}
			}
			return result;
		}
		
		/*
		 * Returns a list of States from a Region
		 * 
		 * @param	region	a region
		 * @return		a list of states
		 */
		public List<State> getAllStatesFromRegion(Region region){
			if(region.getName().isBlank()) {
				return Collections.emptyList();
			}
			List<State> result = new ArrayList<>();
			for (NamedElement member : region.getMember()) {
				if(member instanceof State) {
					result.add((State) member);
				}
			}
			return result;
		}
		
		/*
		 * Returns a list of Transitions from a Region
		 * 
		 * @param	region	a region
		 * @return		a list of transitions
		 */
		public List<Transition> getAllTransitionsFromRegion(Region region){
			if(region.getName().isBlank()) {
				return Collections.emptyList();
			}
			List<Transition> result = new ArrayList<>();
			for (NamedElement member : region.getTransition()) {
				if(member instanceof Transition) {					
					result.add((Transition) member);
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
		public List<State> getAllStatesFromStateMachine(StateMachine sm){
			List<State> result = new ArrayList<>();
			for (TreeIterator<EObject> iter = EcoreUtil.getAllContents(sm, true); iter.hasNext(); ) {
				var e = iter.next();
				if(e instanceof State s) {
					result.add(s);
				}
			}
			return result;
		}
		
		public Transition getFirstTransition(StateMachine sm) {
			for(State s : getAllStatesFromStateMachine(sm)) {
				for(Transition t : getTransitionsToState(s, Boolean.TRUE)) {
					// see if the state is being transitioned to from a Pseudostate
					if(t.getSource() instanceof Pseudostate ps) {
						// see if the Pseudostate is PseudostateKind.INITIAL
						if(ps.getKind() == PseudostateKindEnum.INITIAL){
							return t;
						}
						
						System.out.println(ps.getKind().toString());
					}
				}
			}
			System.out.println("Initial state not found!");
			return null;
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
					if(t.getSource() instanceof Pseudostate ps) {
						// see if the Pseudostate is PseudostateKind.INITIAL
						if(ps.getKind() == PseudostateKindEnum.INITIAL){
							return s;
						}
						
						System.out.println(ps.getKind().toString());
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
				for(ActivityEdge ae : n.getIncoming()) {
					if (ae.getSource() instanceof InitialNode in) {
						return in;
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
		public List<Transition> getTransitionsToState(State s, Boolean getPseudostates){
			List<Transition> result = new ArrayList<>();
			Region region;
			if(s.eContainer() instanceof Region) {
				region = (Region) s.eContainer();
			}else {
				System.out.println("Unable to find region!");
				return null;
			}
			
			for(Transition t : region.getTransition()) {
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
		public List<Transition> getTransitionsFromState(State s){
			List<Transition> result = new ArrayList<>();
			Region region;
			if(s.eContainer() instanceof Region) {
				region = (Region) s.eContainer();
			}else {
				System.out.println("Unable to find region!");
				return null;
			}
			
			for(Transition t : region.getTransition()) {
				if(t.getSource() == s) {
					result.add(t);
				}
			}

			return result;
		}
		
		public Map<String, String> getInvariants(StateMachine sm) {
			Map<String, String> result = new HashMap<>();
			for(State s : getAllStatesFromStateMachine(sm)) {
				List<Stereotype> stereotypes = s.getAppliedStereotype();
				for(Stereotype stereotype : stereotypes) {
					if(stereotype.getName().equals("Invariant")) {
						OpaqueBehavior behavior = (OpaqueBehavior) s.getDoActivity();
						String name = s.getName();
						String invariantText = behavior.getBody().get(0);
						result.put(name, invariantText);
					}
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
			SignalEvent s = (SignalEvent) t.getTrigger().stream().findFirst().get().getEvent();
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
			
			URI newUri = URI.createFileURI("modified-model.uml");
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
