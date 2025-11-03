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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.Set;
import java.util.stream.Collectors;

import javax.naming.SizeLimitExceededException;

import com.nomagic.uml2.ext.magicdraw.actions.mdbasicactions.SendSignalAction;
import com.nomagic.uml2.ext.magicdraw.activities.mdbasicactivities.ActivityEdge;
import com.nomagic.uml2.ext.magicdraw.activities.mdbasicactivities.InitialNode;
import com.nomagic.uml2.ext.magicdraw.activities.mdfundamentalactivities.Activity;
import com.nomagic.uml2.ext.magicdraw.activities.mdfundamentalactivities.ActivityNode;
import com.nomagic.uml2.ext.magicdraw.activities.mdintermediateactivities.FinalNode;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.OpaqueExpression;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdbasicbehaviors.Behavior;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdbasicbehaviors.OpaqueBehavior;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdcommunications.Signal;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.State;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.StateMachine;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.Transition;

import edu.cmu.sei.fasr.TLATranslator.TLAMachineGraph.CompressedNode;

public class TLATranslator {
	
	String MAX_FORMULA = "max(x,y) == IF x>y THEN x ELSE y"; 
	TraverseModel tm;
	StringBuilder sb;
	TLAEnvGraph graph;
	
	public TLATranslator(TraverseModel tm) {
		this.tm = tm;
		this.sb = new StringBuilder();
	}
	
	public String createMachineSpec() {
		sb = new StringBuilder();
		
		List<List<Behavior>> diagrams = tm.getDiagrams(tm.getModel());
		List<Behavior> machineDiagrams = diagrams.get(0);
		StateMachine machine = null;
		for(Behavior d : machineDiagrams) {
			if(d instanceof StateMachine) {
				machine = (StateMachine) d;
			} 
		}
		if(machine == null) {
			System.err.println("A State Machine was not stereotyped as a Machine in Cameo.");
			return "";
		}
		List<State> states = this.tm.getAllStatesFromStateMachine(machine);
		Transition firstTransition = this.tm.getFirstTransition(machine);
		String specName = machine.getName().replace("\s", "_");
		
		
		TLAMachineGraph graph = new TLAMachineGraph(states);
		createInitMachine(firstTransition, graph);
		graph.compressNodes();
		
		
		
		for(edu.cmu.sei.fasr.TLATranslator.TLAMachineGraph.CompressedNode ng : graph.compressedNodes) {
			sb.append(ng.getName() + " == \n");
			for(String s : ng.states) {
				sb.append(s);
			}
			if(ng.name.equals("Init")) continue;
			Set<String> missing = graph.findMatches(ng);
			if(!missing.isEmpty()) {
				sb.append("\t/\\ UNCHANGED <<" + String.join(", ", missing) + ">>\n\n");
			}
			
		}
		createMachineSpecBeginning(specName, graph);
		createMachineSpecEnding(graph);
		String result = sb.toString();
		saveSpec(specName + ".tla");
		
		return result;
	}
	
	public String createEnvironmentSpec() throws SizeLimitExceededException {
		sb = new StringBuilder();
//		HashMap<String, ActivityNode> visitedNodes = new HashMap<String, ActivityNode>(); //Ensures we do not reprint states
		List<List<Behavior>> diagrams = tm.getDiagrams(tm.getModel()); //Gets Activity diagrams 
		List<Behavior> envDiagrams = diagrams.get(1);
		
		// Enforces that there is only 1 activity diagram that is stereotyped as Environment
		// Could be extended to more but would need to discuss how this would work
		if(envDiagrams.size() > 1 || envDiagrams.size() == 0) {
			throw new IllegalArgumentException("Only 1 Activity diagram may be stereotyped as Environment");
		}
		
		// Adds activity Nodes to StringBuilder
		// TODO: Fill in states
		int step = 0;
		this.graph = new TLAEnvGraph(envDiagrams.get(0).getName());
		HashSet<ActivityNode> visited = new HashSet<ActivityNode>();
		for(ActivityNode an : this.tm.getAllNodesFromActivity((Activity)envDiagrams.get(0))) {
			if(an instanceof InitialNode) {
				boolean notExit = true;
				ActivityNode next = null;
				while(notExit) {
					if(step == 0) {
						next = an.getOutgoing().stream().findFirst().get().getTarget();
						step++;
					} else {
						for(ActivityEdge outgoing : next.getOutgoing()) {
							if(outgoing.getTarget() instanceof FinalNode) {
								graph.addTransition(outgoing.getSource(), outgoing.getTarget(), step);
								notExit = false;
								break;
							}
							graph.addTransition(outgoing.getSource(), outgoing.getTarget(), step);
							step++;
							next = outgoing.getTarget();
						}
					}
					
				}
			}
			break;
		}
		graph.createTLA();
		
		String result = sb.toString();
		
		saveSpec(this.graph.getName() + ".tla");
		
		return result;
	}
	
	
	private void createInitMachine(Transition t, TLAMachineGraph g) {
		Scanner sc;
	    if (t.getEffect() != null) {
	    		OpaqueBehavior effect = (OpaqueBehavior) t.getEffect();
	            String[] newEffect = effect.getBody().get(0).split("\n");
	            // see if there is a name, otherwise add state prefix
	            for (String assignment : newEffect) {
	                String[] temp = assignment.split("=");
	                if (temp.length == 2) {
		                String lhs = temp[0].strip();
		                String rhs = temp[1].strip();
	                    sc = new Scanner(rhs);
	                    if (rhs.equalsIgnoreCase("true") || rhs.equalsIgnoreCase("false")) {
	                        g.addNode("Init", "\t/\\ " + lhs + " = " + rhs.toUpperCase() + "\n");
	                    } else if (sc.hasNextInt()) {
	                        g.addNode("Init", "\t/\\ " + lhs + " = " + sc.nextInt() + "\n");
	                    } else {
	                        g.addNode("Init", "\t/\\ " + lhs + " = " + rhs + "\n");
	                    }
	                    g.addVariable(lhs);
	                }
	            }
	            g.addNode("Init", "\n");
	        }
	}
	
	private void createMachineSpecBeginning(String name, TLAMachineGraph g) {	
		StringBuilder varString = new StringBuilder();
		varString.append("\n\nVARIABLES ");
		
		String commaSepVars = String.join(", ", g.variables);
		varString.append(commaSepVars);
		
		varString.append("\n\nvars == <<");
		varString.append(commaSepVars);
		varString.append(">>\n\n");
		
		sb.insert(0, varString.toString());
		
		sb.insert(0, "\nEXTENDS Integers");
		
//				+ "Init ==\n/\\ step = 0\n\n");
		sb.insert(0, String.format("----------------------------- MODULE %s -----------------------------\n", name));
	}
	
	private void createMachineSpecEnding(TLAMachineGraph g) {
		sb.append("Next ==");
		for(CompressedNode node : g.getCompressedNodes()) {
			if(node.name.equals("Init")) continue;
			sb.append("\n\t\\/ " + node.name);
		}
		sb.append("\n\nSpec == Init /\\ [][Next]_vars");
		sb.append("\n\n=============================================================================");
	}
	
	private void createEnvironmentSpecBeginning(String name) {	
		StringBuilder header = new StringBuilder();
		header.append("----------------------------- MODULE ");
		header.append(name);
		header.append(" -----------------------------\n\n");
		header.append("EXTENDS Integers\n\n");
		header.append("VARIABLES step\n\n");
		header.append("vars == <<step>>\n\n");
		header.append("Init ==\n");
		header.append("\tstep = 0\n\n");
		sb.insert(0, header.toString());
	}
	
	private void createEnvironmentSpecEnding() {
		sb.append("Next ==");
		for(Entry<ActivityNode, List<edu.cmu.sei.fasr.TLATranslator.TLAEnvGraph.TLAEnvTransition>> entry : this.graph.edges.entrySet()) {
			sb.append("\n\t\\/ " + ((SendSignalAction)entry.getKey()).getSignal().getName());
		}
		sb.append("\n\nSpec == Init /\\ [][Next]_vars");
		sb.append("\n\n=============================================================================");
	}
	
	/*
	 * Creates or updates a TLA file
	 */
	private boolean saveSpec(String fileName) {
		try {
			File file = new File(fileName);
			if(file.exists()) {
				FileWriter fw = new FileWriter(file);
				fw.write(sb.toString());
				sb = new StringBuilder();
				fw.close();
				System.out.println("File was updated!");
			}
			else if(file.createNewFile()) {
				FileWriter fw = new FileWriter(file);
				fw.write(sb.toString());
				sb = new StringBuilder();
				fw.close();
				System.out.println(fileName + " was successfully created!");
				return true;
			}
		} catch(IOException e){
			System.out.println("Could not write file " + fileName);
		}
		return false;
		
	}
	
	
	class TLAMachineGraph {
		HashSet<String> variables = new HashSet<String>();
		List<State> conditions = new ArrayList<State>();
		List<MachineNode> tlaNodes = new ArrayList<MachineNode>();
		List<CompressedNode> compressedNodes = new ArrayList<>();
		
		public TLAMachineGraph(List<State> states) {
			// TODO Auto-generated constructor stub
			this.conditions = states;
			createNodes();
		}
		
		public void addVariable(String s) {
			this.variables.add(s);
		}
		
		public void addNode(String signal, String condition) {
			tlaNodes.add(new MachineNode(signal, condition));
		}
		
		/*
		 * Creates all uncompressed nodes
		 */
		public void createNodes() {
			for(State s : this.conditions) {
				StringBuilder tlaConditions = new StringBuilder("\t/\\\n");
				StringBuilder tlaAssignments = new StringBuilder("\t/\\\n");
				for(Transition t : s.getIncoming()) {					
					tlaConditions.append("\t\t\\/\n");					
					tlaAssignments.append("\t\t\\/\n");
					for(var trigger : t.getTrigger()) {
						if(trigger.getName().length() == 0) {
							Signal signal = TLATranslator.this.tm.getTransitionTrigger(t);
							if(t.getEffect() != null) { // Happens when there is no effect during a transition
								OpaqueExpression guard = ((OpaqueExpression) t.getGuard().getSpecification());
								OpaqueBehavior effect = (OpaqueBehavior) t.getEffect();
								String[] conditions = guard.getBody().get(0).split("\n");
								String[] assignments = effect.getBody().get(0).split("\n");
								for(String condition : conditions) {
									String[] condit_pieces = condition.split("[=<>!]");
									String[] op_pieces = condition.split("[^=<>!]");
									String lhs = condit_pieces[0].trim();
									String rhs = condit_pieces[condit_pieces.length - 1].trim();
									if(rhs.equalsIgnoreCase("true") || rhs.equalsIgnoreCase("false")) {
										rhs = rhs.toUpperCase();
									}
									String op = getTLAOperatorFromCEAOpeator(String.join("", op_pieces).trim());
									tlaConditions.append("\t\t\t/\\ ");
									tlaConditions.append(lhs);
									tlaConditions.append(op);
									tlaConditions.append(rhs);
									tlaConditions.append("\n");
								}
								for(String assignment : assignments) {
									String[] assig_pieces = assignment.split("=");
									String lhs = assig_pieces[0].trim()+"'";
									String rhs = assig_pieces[1].trim();
									if(rhs.equalsIgnoreCase("true") || rhs.equalsIgnoreCase("false")) {
										rhs = rhs.toUpperCase();
									}
									tlaAssignments.append("\t\t\t/\\ ");
									tlaAssignments.append(lhs);
									tlaAssignments.append(" = ");
									tlaAssignments.append(rhs);
									tlaAssignments.append("\n");
								}
								MachineNode newNode = new MachineNode(signal.getName(), tlaConditions.toString() + tlaAssignments.toString());
								tlaNodes.add(newNode);
//								String[] newEffect = effect.getBodies().get(0).split("=");
//								newEffect[0] = newEffect[0].strip();
//								tlaNodes.add(new MachineNode(signal.getName(), "/\\ state = \"" + s.getName() + "\" => " + newEffect[0] + "' =" + newEffect[1]));
							} else {
//								tlaNodes.add(new MachineNode(signal.getName(), "/\\ state' = "+ s.getName()));
								tlaNodes.add(new MachineNode(signal.getName(), ""));
							}
							
						}
						
					}
					
				}
				
			}
		}
		
		private String getTLAOperatorFromCEAOpeator(String ceaOperator) {
			switch(ceaOperator) {
				case "==":
					return " = ";
			}
			return " UNSUPPORTED OPERATOR ";
		}

		public List<CompressedNode> getCompressedNodes(){
			return this.compressedNodes;
		}
		
		/*
		 * Compresses uncompressed nodes
		 */
		public List<CompressedNode> compressNodes() {
		    // Name order preserved via LinkedHashMap; states deduped & ordered via LinkedHashSet
		    Map<String, LinkedHashSet<String>> map = new LinkedHashMap<>();

		    for (MachineNode n : this.tlaNodes) {
		        if (n == null) continue; // optional: skip nulls
		        String name = n.getName();
		        String var = n.variable;
		        if (name == null || var == null) continue; // optional: skip partials
		        map.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(var);
		    }

		    List<CompressedNode> out = new ArrayList<>(map.size());
		    for (Map.Entry<String, LinkedHashSet<String>> e : map.entrySet()) {
		        out.add(new CompressedNode(e.getKey(), new ArrayList<>(e.getValue())));
		    }
		    this.compressedNodes = out;
		    return out;
		}
		
		public Set<String> findMatches(CompressedNode node) {
		    Set<String> matches = new HashSet<>();
		    Set<String> modVars = variables.stream().map(var -> var + "'").collect(Collectors.toSet());
		    for (String state : node.getStates()) {
	            String[] words = state.split("\\s+"); // split on whitespace
	            for (String word : words) {
	                if (modVars.contains(word)) {
	                    matches.add(word.substring(0, word.length()-1)); // collect the variable name
	                }
	            }
	        }
		    
		    Set<String> missing = new HashSet<>(variables);
	        missing.removeAll(matches);
		    return missing;
		}
		
		/*
		 * Class that represents uncompressed nodes pulled from UML diagram
		 */
		class MachineNode {
			String name;
			String variable;
			
			MachineNode(String name, String variable){
				this.name = name;
				this.variable = variable;
			}
			
			public String getName() {
				return this.name;
			}
			
//			public String getState() {
//				return this.variable;
//			}
			
		}
		
		class NewCompressedNode {
			String name;
			HashMap<String, String> variables;
			public NewCompressedNode(String name, HashSet<String> variables) {
				this.name = name;
				this.variables = new HashMap<String, String>();
				for (String key : variables) {
					this.variables.put(key, null);
				}
			}
		}
		
		/*
		 * Class that represents compressed nodes
		 */
		class CompressedNode {
		    private final String name;
		    private final java.util.List<String> states;
		    public CompressedNode(String name, java.util.List<String> states) {
		        this.name = name; this.states = states;
		    }
		    public String getName() { return name; }
		    public List<String> getStates() { return states; }
		}
	}
	
	class NewTLAMachine {
		public List<TLANode> nodeList;
		public HashSet<String> variables;
		
		public NewTLAMachine() {
			this.nodeList = new ArrayList<TLANode>();
		}
		
		public void addVariable(String var) {
			this.variables.add(var + "'");
		}
		
		public void addNode(String name) {
			this.nodeList.add(new TLANode(name, this.variables));
		}
		
		class TLANode{
			private String name;
		    private HashMap<String, String> variables;
		    
			TLANode(String name, HashSet<String> keys){
				this.name = name;
		        this.variables = new HashMap<>();
		        for (String key : keys) {
		            variables.put(key, null);
		        }
			}
			
			public void setVariable(String key, String val) {
				variables.put(key, val);
			}
		}
	}
	
	class TLAEnvGraph {
		private Map<String, ActivityNode> nodes = new HashMap<>();
		
		private Map<ActivityNode, List<TLAEnvTransition>> edges = new HashMap<>();

		private String envName;
		
		public TLAEnvGraph(String name) {
			this.envName = name;
		}
		
		public String getName() {
			return envName;
		}
		
		public void addTransition(ActivityNode from, ActivityNode to, int step) {
			from = getOrInsertByName(from);
			to = getOrInsertByName(to);
			edges.computeIfAbsent(from, k -> new ArrayList<TLAEnvTransition>())
			        .add(new TLAEnvTransition(to, step));
		}
		
		private ActivityNode getOrInsertByName(ActivityNode node) {
			String name = "UNSET";
			if(node instanceof SendSignalAction) {
				name = ((SendSignalAction)node).getSignal().getName();
			}
	        if (name == null) {
	            throw new IllegalArgumentException("ActivityNode must have a name.");
	        }

	        return nodes.computeIfAbsent(name, n -> node);
	    }
		
		public void createTLA() {
			createEnvironmentSpecBeginning(envName); //TODO : update to include initial transition
			for (Entry<ActivityNode, List<TLAEnvTransition>> entry : edges.entrySet()) {
				sb.append(((SendSignalAction)entry.getKey()).getSignal().getName() + " ==\n");
				sb.append("\t/\\ ");
				
				if(entry.getValue().size() > 1) {
					sb.append("step \\in {"); 
					for(int i = 0; i < entry.getValue().size(); i++) {
						if(i != entry.getValue().size()-1) {
							sb.append(entry.getValue().get(i).step + ",");
						} else {
							sb.append(entry.getValue().get(i).step + "}");
						}
					}
				}else {
					sb.append("step = " + (entry.getValue().get(0).step-1));
				}
				
				sb.append("\n");
				sb.append("\t/\\ step' = step + 1\n\n");

			}
			createEnvironmentSpecEnding();
				
		}
		class TLAEnvTransition {
	        ActivityNode to;
	        int step;

	        public TLAEnvTransition(ActivityNode to, int step) {
	            this.to = to;
	            this.step = step;
	        }
	    }
	
	}
}
