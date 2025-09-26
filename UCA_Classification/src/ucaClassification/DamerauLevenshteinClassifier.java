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

package ucaClassification;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Takes pairs of traces -- one safe, one unsafe -- and creates Unsafe Control
 * Actions. This partially automates the third step of the System Theoretic
 * Process Analysis (STPA).
 * 
 * This classification is done using a modified version of the
 * Damerau-Levenshtein edit distance calculation -- we map those the traces to
 * strings, and the edits to the strings to STPA Guidewords classifying the
 * unsafe behavior.
 * 
 * @author Sam Procter
 */
public class DamerauLevenshteinClassifier {

	/**
	 * STPA Guidewords -- essentially classifications of how a control action can be
	 * unsafe
	 */
	public enum Guideword {
		NOT_PROVIDING, PROVIDING, TOO_EARLY, TOO_LATE, OUT_OF_SEQUENCE, STOPPED_TOO_SOON, APPLIED_TOO_LONG
	}

	/**
	 * These five elements describe an Unsafe Control Action -- see page 37 of the
	 * STPA Handbook
	 */
	public record UnsafeControlAction(String source, Guideword guideword, String controlAction, List<String> context,
			String violatedConstraint) {
	};

	/**
	 * Activities link two actions that start and end something. This lets us
	 * determine if the activity is stopped too soon or done for too long, eg:
	 * 
	 * <ul>
	 * <li>Activity: Stop Car
	 * <li>Start: Apply Brakes
	 * <li>End: Release Brakes
	 * </ul>
	 */
	public record Activity(String start, String end) {
	};

	/**
	 * Maps names to activity objects so we can identify Too Soon / Too Long unsafe
	 * control actions.
	 */
	private Map<String, Activity> activities;

	/**
	 * STPA's model of time isn't really wall-clock time, but it's more than
	 * ordering. Several guidewords (Too Early, Too Late, Stopped too Soon, Applied
	 * too Long) have a concept of "too much" or "too little" time -- Delay Actions
	 * signify some amount of time passing, so we can compare safe and unsafe traces
	 * where the amount of time passing differs, and thus identify those guidewords.
	 */
	private static final String DELAY_ACTION = "Wait";

	/**
	 * The Damerau-Levenshtein algorithm recognizes these four types of atomic
	 * string edits.
	 */
	private enum Edit {
		ADD, DELETE, SUBSTITUTE, TRANSPOSE
	}

	/**
	 * Default constructor -- no activities.
	 */
	public DamerauLevenshteinClassifier() {
		this(Collections.emptyMap());
	}

	/**
	 * Creates an instance with the supplied activity mapping
	 * 
	 * @param activities Activities that may be encountered in the traces
	 */
	public DamerauLevenshteinClassifier(Map<String, Activity> activities) {
		this.activities = activities;
	}

	public static void main(String[] args) {
		BufferedReader f = new BufferedReader(new InputStreamReader(System.in));
		DamerauLevenshteinClassifier dlc = new DamerauLevenshteinClassifier(Collections.emptyMap());
		try {
			String x = f.readLine();
			while (x != null) {
				if (x.startsWith("[{\"goodTrace\":[\"")) {
					Collection<UnsafeControlAction> classifierOutput = dlc.classifyFortisOutput(x);
					ObjectMapper mapper = new ObjectMapper();
					System.out.print(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(classifierOutput));
					return;
				} else {
					x = f.readLine();
				}
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		System.out.println("Usage: java -jar fortis-core.jar robustness --stpa ... | java -jar fasr-classifier.jar");
	}

	public Collection<UnsafeControlAction> classifyFortisOutput(File jsonFile) {
		Collection<UnsafeControlAction> ret = null;
		Scanner s = null;
		try {
			s = new Scanner(jsonFile);
			ret = classifyFortisOutput(s.useDelimiter("\\Z").next());
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			s.close();
		}
		return ret;
	}

	public Collection<UnsafeControlAction> classifyFortisOutput(String s) {
		Collection<UnsafeControlAction> ret = new HashSet<>();
		ObjectMapper mapper = new ObjectMapper();
		try {
			ArrayNode root = (ArrayNode) mapper.readTree(s);
			for (JsonNode jsonPair : root) {
				var pair = (ObjectNode) jsonPair;
				List<String> safe = mapper.readerForListOf(String.class).readValue(pair.get("goodTrace"));
				List<String> unsafe = mapper.readerForListOf(String.class).readValue(pair.get("badTrace"));
				List<String> invariants = mapper.readerForListOf(String.class).readValue(pair.get("violatedInvs"));
				String invariantStr = String.join(",", invariants);
				List<String> components = mapper.readerForListOf(String.class).readValue(pair.get("violatingComponents"));
				String componentStr = String.join(",", components);
				ret.add(classify(safe, unsafe, invariantStr, componentStr));
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return ret;
	}

	public UnsafeControlAction classify(List<String> safe, List<String> unsafe, String invariantName, String sourceName) {
		if (safe.equals(unsafe)) {
			throw new IllegalArgumentException(
					"The unsafe trace is identical to the safe trace; there is no error to classify.");
		}

		// We don't use DamerauLevenshtein at all to check if the guideword "Applied Too
		// Long" or "Stopped too Soon" applies. They're checked using a different
		// algorithm, so we check for that / return early if possible.
		Optional<UnsafeControlAction> tooLongOrShort = checkTooLongOrShort(safe, unsafe, invariantName, sourceName);
		if (tooLongOrShort.isPresent()) {
			return tooLongOrShort.get();
		}

		// The D-L initialization / setup takes advantage of the fact that only edit
		// distance is being computed, rather than the edits themselves as we track (see
		// in particular the loops which initialize C[i][0] to i and C[0][j] to j). We
		// are forced to actually calculate these edits, which we do by prepending an
		// idle action to both traces. Note this requires subsequent removal for the UCA
		// context.
		List<String> newSafe = new LinkedList<>();
		newSafe.add(DELAY_ACTION);
		newSafe.addAll(safe);
		safe = newSafe;

		List<String> newUnsafe = new LinkedList<>();
		newUnsafe.add(DELAY_ACTION);
		newUnsafe.addAll(unsafe);
		unsafe = newUnsafe;

		int[][] C = new int[safe.size() + 1][unsafe.size() + 1];

		@SuppressWarnings("unchecked")
		Deque<UnsafeControlAction>[][] CG = new LinkedList[safe.size() + 1][unsafe.size() + 1];

		// This gets the alphabet of the strings: it de-duplicates them by combining
		// them into a set, then puts them in a list since we need stable indices of
		// elements
		List<String> Σ = Set.copyOf(Stream.concat(safe.stream(), unsafe.stream()).toList()).stream()
				.collect(Collectors.toList());

		int[] CP = new int[Σ.size() + 1];

		int iPrime, jPrime, CS;
		int delScore, addScore, subScore;
		Optional<Integer> transScore = Optional.empty();

		for (int i = 0; i <= safe.size(); i++) {
			C[i][0] = i;
			CG[i][0] = new LinkedList<UnsafeControlAction>();
		}

		for (int j = 0; j <= unsafe.size(); j++) {
			C[0][j] = j;
			CG[0][j] = new LinkedList<UnsafeControlAction>();
		}

		for (int i = 1; i <= Σ.size(); i++) {
			CP[i] = 0;
		}

		for (int i = 1; i <= safe.size(); i++) {
			CS = 0;
			for (int j = 1; j <= unsafe.size(); j++) {
				int d;
				if (safe.get(i - 1).equals(unsafe.get(j - 1))) {
					d = 0;
				} else {
					d = 1;
				}
				delScore = C[i - 1][j] + 1;
				addScore = C[i][j - 1] + 1;
				subScore = C[i - 1][j - 1] + d;
				C[i][j] = Math.min(delScore, Math.min(addScore, subScore));
				// CP[c] stores the largest index i' < i such that p[i'] = c.
				// CS stores the largest index j' < j such that s[j'] = p[i]
				iPrime = CP[Σ.indexOf(unsafe.get(j - 1))];
				jPrime = CS;
				if (iPrime > 0 && jPrime > 0) {
					transScore = Optional.of(C[iPrime - 1][jPrime - 1] + (i - iPrime) + (j - jPrime) - 1);
					C[i][j] = Math.min(C[i][j], transScore.get());
				}
				if (d == 0) {
					CS = j;
				}
				Edit edit = null;
				if (C[i][j] == delScore) {
					edit = Edit.DELETE;
				} else if (C[i][j] == addScore) {
					edit = Edit.ADD;
				} else if (C[i][j] == subScore) {
					edit = Edit.SUBSTITUTE;
				} else if (transScore.isPresent() && C[i][j] == transScore.get()) {
					edit = Edit.TRANSPOSE;
				}
				Optional<UnsafeControlAction> newUCA = classifyUCA(safe, unsafe, edit, CG, i, j, d, iPrime, jPrime,
						invariantName, sourceName);
				if (newUCA.isPresent()) {
					CG[i][j].addLast(newUCA.get());
				}

			}
			CP[Σ.indexOf(safe.get(i - 1))] = i;
		}
		return CG[safe.size()][unsafe.size()].getFirst();
	}

	/**
	 * Checks to see if the traces can be classified using the "Applied Too Long" or
	 * "Stopped Too Soon" guidewords. This relies on the
	 * {@link DamerauLevenshteinClassifier#activities} field.
	 * 
	 * @param safe          A safe trace of system behaviors
	 * @param unsafe        An unsafe trace of system behaviors
	 * @param invariantName The name of the safety property that is violated by the
	 *                      unsafe trace but not the safe trace.
	 * @return The UnsafeControlAction associated with these traces, or empty if
	 *         neither "Applied Too Long" or "Stopped Too Soon" apply
	 */
	private Optional<UnsafeControlAction> checkTooLongOrShort(List<String> safe, List<String> unsafe,
			String invariantName, String sourceName) {
		var safeActivityDurations = getActivityDurations(safe);
		if (!safeActivityDurations.isEmpty()) {
			var unsafeActivityDurations = getActivityDurations(unsafe);
			for (String activityName : safeActivityDurations.keySet()) {
				if (!unsafeActivityDurations.containsKey(activityName)) {
					continue;
				}
				List<String> prefix = Collections.emptyList();
				if (safeActivityDurations != unsafeActivityDurations) {
					int diffIdx = 0;
					while (safe.get(diffIdx).equals(unsafe.get(diffIdx))) {
						diffIdx++;
					}
					prefix = safe.subList(0, diffIdx);
				}
				if (unsafeActivityDurations.get(activityName) < safeActivityDurations.get(activityName)) {
					return Optional.of(new UnsafeControlAction(sourceName, Guideword.STOPPED_TOO_SOON,
							activities.get(activityName).end(), prefix, invariantName));
				} else if (unsafeActivityDurations.get(activityName) > safeActivityDurations.get(activityName)) {
					return Optional.of(new UnsafeControlAction(sourceName, Guideword.APPLIED_TOO_LONG,
							activities.get(activityName).end(), prefix, invariantName));
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * This determines the "duration" (number of delay actions) of each activity in
	 * the provided trace
	 * 
	 * @param actions A trace of system behavior
	 * @return A mapping from activity name -> number of delay actions between the
	 *         start and stop action of the named activity
	 */
	private Map<String, Integer> getActivityDurations(List<String> actions) {
		Map<String, Integer> ret = new HashMap<>();
		for (String activityName : activities.keySet()) {
			Activity a = activities.get(activityName);
			int startPos = actions.indexOf(a.start());
			// Make sure the end comes after the start
			int endPos = actions.subList(startPos, actions.size()).indexOf(a.end());
			if (startPos >= 0 && endPos >= 0 && startPos < endPos) {
				int numWaits = Collections.frequency(actions.subList(startPos, endPos), DELAY_ACTION);
				ret.put(activityName, numWaits);
			}
		}
		return ret;
	}

	private Optional<UnsafeControlAction> classifyUCA(List<String> safeActions, List<String> unsafeActions, Edit edit,
			Deque<UnsafeControlAction>[][] CG, int i, int j, int d, int iPrime, int jPrime, String invariantName, String sourceName) {
		CG[i][j] = new LinkedList<UnsafeControlAction>();
		Guideword guideword = null;
		String controlAction = null;
		List<String> context = null;
		if (edit == Edit.DELETE) {
			CG[i][j].addAll(CG[i - 1][j]);
			if (!CG[i][j].isEmpty()) {
				// Calculating subsequent UCAs is 1) Difficult, and 2) Unnecessary, so we skip it
				return Optional.empty();
			}
			String deletedAction = safeActions.get(i - 1);
			if (deletedAction.equals(DELAY_ACTION)) {
				controlAction = unsafeActions.get(j);
				context = unsafeActions.subList(0, i - 1);
				guideword = Guideword.TOO_EARLY;
			} else {
				controlAction = deletedAction;
				context = unsafeActions.subList(0, i - 1);
				guideword = Guideword.NOT_PROVIDING;
			}
		} else if (edit == Edit.ADD) {
			CG[i][j].addAll(CG[i][j - 1]);
			if (!CG[i][j].isEmpty()) {
				// Calculating subsequent UCAs is 1) Difficult, and 2) Unnecessary, so we skip it
				return Optional.empty();
			}
			String addedAction = unsafeActions.get(j - 1);
			if (addedAction.equals(DELAY_ACTION)) {
				// We need to find the next non-wait action, though if the trace ends in all
				// waits, there will be no subsequent action so we don't have a UCA
				controlAction = null;
				for (int k = j; k < unsafeActions.size(); k++) {
					if (!unsafeActions.get(k).equals(DELAY_ACTION)) {
						controlAction = unsafeActions.get(k);
						break;
					}
				}
				context = unsafeActions.subList(0, i);
				guideword = Guideword.TOO_LATE;
			} else {
				controlAction = addedAction;
				context = unsafeActions.subList(0, i);
				guideword = Guideword.PROVIDING;
			}
		} else if (edit == Edit.SUBSTITUTE) {
			CG[i][j].addAll(CG[i - 1][j - 1]);
			if (!CG[i][j].isEmpty()) {
				// Calculating subsequent UCAs is 1) Difficult, and 2) Unnecessary, so we skip it
				return Optional.empty();
			}
			if (d == 1) {
				/*
				 * We have a special case where the safe or unsafe traces are singletons. This
				 * is required because we initialize CG[i][j] slightly differently than C[i][j]
				 * -- the first row of C is 0, 1, 2... i and the first column is 0, 1, 2... j.
				 * But CG is initialized with only empty lists of guidewords, so when we have
				 * single-element traces, we would return an empty list instead of the correct
				 * guideword.
				 */

				String correctAction = safeActions.get(i - 1);
				String incorrectAction = unsafeActions.get(j - 1);
				if (!incorrectAction.equals(DELAY_ACTION) && !correctAction.equals(DELAY_ACTION)) {
					controlAction = incorrectAction;
					context = unsafeActions.subList(0, i - 1);
					guideword = Guideword.PROVIDING;
				} else if (incorrectAction.equals(DELAY_ACTION)) {
					controlAction = correctAction;
					context = unsafeActions.subList(0, i - 1);
					guideword = Guideword.NOT_PROVIDING;
				} else if (correctAction.equals(DELAY_ACTION)) {
					controlAction = incorrectAction;
					context = unsafeActions.subList(0, i - 1);
					guideword = Guideword.PROVIDING;
				}
			}
		} else if (edit == Edit.TRANSPOSE) {
			CG[i][j].addAll(CG[iPrime - 1][jPrime - 1]);
			if (!CG[i][j].isEmpty()) {
				// Calculating subsequent UCAs is 1) Difficult, and 2) Unnecessary, so we skip it
				return Optional.empty();
			}
			String correctAction = safeActions.get(iPrime - 1);
			String incorrectAction = unsafeActions.get(jPrime - 1);
			if (!incorrectAction.equals(DELAY_ACTION) && !correctAction.equals(DELAY_ACTION)) {
				controlAction = incorrectAction;
				context = unsafeActions.subList(0, i - 2);
				guideword = Guideword.OUT_OF_SEQUENCE;
			} else if (incorrectAction.equals(DELAY_ACTION)) {
				controlAction = correctAction;
				context = safeActions.subList(0, iPrime - 1);
				guideword = Guideword.TOO_LATE;
			} else if (correctAction.equals(DELAY_ACTION)) {
				controlAction = incorrectAction;
				context = unsafeActions.subList(0, iPrime - 1);
				guideword = Guideword.TOO_EARLY;
			}
		}
		if (guideword == null || controlAction == null || context == null) {
			return Optional.empty();
		} else {
			return Optional.of(new UnsafeControlAction(sourceName, guideword, controlAction,
					// Remove the "fake" delay action we inserted to make the initialization work
					context.subList(1, context.size()), invariantName));
		}
	}
}