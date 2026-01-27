package edu.cmu.sei.fasr.tla.environment;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import edu.cmu.sei.fasr.tla.TLANode;

public class EnvironmentSpec extends TLANode {

	// Map name to action
	private Map<String, EnvAction> envActions;
	private Collection<ObsvAction> obsvActions;
	private Collection<FlagData> flagData; // used to store flag data until all nodes are created

	public record Neighbor(String pred, String succ, Collection<Flag> flags) {
	};

	public record EnvAction(String name, Collection<Neighbor> neighbors) {
	};

	public record ObsvAction(String actionName, String flagName) {
	};
	
	public record FlagData(String flagName, String actionName, String stateElement, boolean isRequired) {
		
	};
	
	public record Flag(String flagName, Boolean isRequired) {
		
	};
	
	public EnvironmentSpec(String name) {
		super(name);
		envActions = new HashMap<>();
		obsvActions = new HashSet<>();
		flagData = new HashSet<>();
	}

	public void addSimpleAction(String myName, String predName, String succName) {
		if (!envActions.containsKey(myName)) {
			envActions.put(myName, new EnvAction(myName, new HashSet<>()));
		}
		envActions.get(myName).neighbors().add(new Neighbor(predName, succName, new HashSet<>()));
	}
	
	public void addFlag(String flagName, String actionName, String stateElement, boolean isRequired) {
		flagData.add(new FlagData(flagName, actionName, stateElement, isRequired));
	}
	
	public void processFlags() {
		for(FlagData fd : flagData) {
			for(Neighbor n : envActions.get(fd.actionName()).neighbors()) {
				if(n.pred().contains(fd.stateElement())) {
					n.flags.add(new Flag(fd.flagName(), fd.isRequired()));
				}
			}
		}
	}

	public void addSystemStateObservationAction(String actionName, String flagName) {
		obsvActions.add(new ObsvAction(actionName, flagName));
	}
	
	public String getTLA() {
		String stateVar = name + "_state"; 
		StringBuilder tla = new StringBuilder();
		tla.append("----------------------------- MODULE ");
		tla.append(name);
		tla.append(" -----------------------------\n\n");
		tla.append("EXTENDS Integers\n\n");
		tla.append("VARIABLES ");
		tla.append(stateVar);
		if(!flagData.isEmpty()) {
			tla.append(", ");
			tla.append(flagData.stream().map(FlagData::flagName).distinct().collect(Collectors.joining(", ")));
		}
		tla.append("\n\nvars == <<");
		tla.append(stateVar);
		if(!flagData.isEmpty()) {
			tla.append(", ");
			tla.append(flagData.stream().map(FlagData::flagName).distinct().collect(Collectors.joining(", ")));
		}
		tla.append(">>\n\n");
		tla.append("Init == \n");
		tla.append("\t/\\ ");
		tla.append(stateVar);
		tla.append(" = \"INIT\"\n");
		for(String flagName : flagData.stream().map(FlagData::flagName).distinct().collect(Collectors.toSet())) {
			tla.append("\t/\\ ");
			tla.append(flagName);
			tla.append(" = FALSE\n");
		}
		tla.append("\n");
		for(String actionName : envActions.keySet()) {
			tla.append(actionName);
			tla.append(" == ");
			for(Neighbor n : envActions.get(actionName).neighbors()) {
				tla.append("\n\t\\/");
				tla.append("\n\t\t/\\ ");
				tla.append(stateVar);
				if(n.pred().contains("INIT")) {
					tla.append(" = \"INIT");
				} else if (n.pred().contains("BRANCH")){
					tla.append(" = \"");
					tla.append(n.pred());
				} else {
					tla.append(" = \"in_");
					tla.append(n.pred());
					tla.append("_out_");
					tla.append(actionName);
				}
				tla.append("\"");
				for(Flag flag : n.flags()) {
					tla.append("\n\t\t/\\ ");
					tla.append(flag.flagName());
					tla.append(" = ");
					if(flag.isRequired()) {
						tla.append("TRUE");
					} else {
						tla.append("FALSE");
					}
				}
				tla.append("\n\t\t/\\ ");
				tla.append(stateVar);
				if (n.succ().contains("BRANCH")){
					tla.append("' = \"");
					tla.append(n.succ());
				} else {
				tla.append("' = \"in_");
				tla.append(actionName);
				tla.append("_out_");
				tla.append(n.succ());
				}
				tla.append("\"");
				
				if(!flagData.isEmpty()) {
					tla.append("\n\t\t/\\ UNCHANGED <<");
					tla.append(flagData.stream().map(FlagData::flagName).distinct().collect(Collectors.joining(", ")));
					tla.append(">>");
				}
				tla.append("\n");
			}
			tla.append("\n");
		}
		for(ObsvAction oAct : obsvActions) {
			tla.append(oAct.actionName());
			tla.append(" == ");
			tla.append("\n\t/\\ ");
			tla.append(oAct.flagName());
			tla.append(" = FALSE");
			tla.append("\n\t/\\ ");
			tla.append(oAct.flagName());
			tla.append("' = TRUE");
			tla.append("\n\t/\\ UNCHANGED <<");
			tla.append(stateVar);
			if(!flagData.stream().map(FlagData::flagName).distinct().filter(n -> !n.equals(oAct.flagName())).collect(Collectors.toSet()).isEmpty()) {
				tla.append(", ");
				tla.append(flagData.stream().map(FlagData::flagName).distinct().collect(Collectors.joining(", ")));
			}
			tla.append(">>\n\n");
		}
		tla.append("Next == ");
		for(String actionName : envActions.keySet()) {
			tla.append("\n\t\\/ ");
			tla.append(actionName);
		}
		for(ObsvAction oAct : obsvActions) {
			tla.append("\n\t\\/ ");
			tla.append(oAct.actionName());
		}
		tla.append("\n\n");
		tla.append("Spec == Init /\\ [][Next]_vars");
		tla.append("\n\n");
		tla.append("=============================================================================");
		return tla.toString();
	}
	
	public String getCFG() {
		StringBuilder cfg = new StringBuilder();
		cfg.append("SPECIFICATION Spec\n");
		cfg.append("CHECK_DEADLOCK FALSE\n");
		return cfg.toString();		
	}
}
