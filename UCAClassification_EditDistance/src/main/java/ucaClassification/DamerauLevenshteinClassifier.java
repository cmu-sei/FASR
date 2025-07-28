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
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class DamerauLevenshteinClassifier {

	public enum Guideword {
		NotProviding, Providing, TooEarly, TooLate, OutOfSequence, StoppedTooSoon, AppliedTooLong
	}

	public record UnsafeControlAction(String source, Guideword guideword, String controlAction, List<String> context,
			String violatedConstraint) {
	};

	private static final String DELAY_ACTION = "Wait";

	private enum Edit {
		add, delete, substitute, transpose
	}
	
	public static void main(String[] args) {
		BufferedReader f = new BufferedReader(new InputStreamReader(System.in));
		try {
			String x = f.readLine();
			while(x != null)
			{
				if(x.startsWith("[{\"goodTrace\":[\"")) {
					Collection<UnsafeControlAction> classifierOutput = classifyFortisOutput(x);
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
	
	public static Collection<UnsafeControlAction> classifyFortisOutput(String s) {
		Collection<UnsafeControlAction> ret = new HashSet<>();
		ObjectMapper mapper = new ObjectMapper();
		try {
			ArrayNode root = (ArrayNode) mapper.readTree(s);
			for(JsonNode jsonPair : root) {
				var pair = (ObjectNode) jsonPair;
				List<String> safe = mapper.readerForListOf(String.class).readValue(pair.get("goodTrace"));
				List<String> unsafe = mapper.readerForListOf(String.class).readValue(pair.get("badTrace"));
				ret.add(classify(safe, unsafe, "##PLACEHOLDER##"));
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return ret;
	}

	public static UnsafeControlAction classify(List<String> safe, List<String> unsafe, String invariantName) {
		if (safe.equals(unsafe)) {
			throw new IllegalArgumentException(
					"The unsafe trace is identical to the safe trace; there is no error to classify.");
		}

		int[][] C = new int[safe.size() + 1][unsafe.size() + 1];

		@SuppressWarnings("unchecked")
		Deque<UnsafeControlAction>[][] CG = new LinkedList[safe.size() + 1][unsafe.size() + 1];

		// This gets the alphabet of the strings: it de-duplicates them by combining
		// them
		// into a set, then puts them in a list since we need stable indices of elements
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
				if (safe.get(i - 1).equals(unsafe.get(j - 1))) {
					CS = j;
				}
				Edit edit = null;
				if (C[i][j] == delScore) {
					edit = Edit.delete;
				} else if (C[i][j] == addScore) {
					edit = Edit.add;
				} else if (C[i][j] == subScore) {
					edit = Edit.substitute;
				} else if (transScore.isPresent() && C[i][j] == transScore.get()) {
					edit = Edit.transpose;
				}
				Optional<UnsafeControlAction> newUCA = classifyUCA(safe, unsafe, edit, CG, i, j, d, iPrime, jPrime,
						invariantName);
				if (newUCA.isPresent()) {
					CG[i][j].addLast(newUCA.get());
				}

			}
			CP[Σ.indexOf(safe.get(i - 1))] = i;
		}
		return CG[safe.size()][unsafe.size()].getFirst();
	}

	private static Optional<UnsafeControlAction> classifyUCA(List<String> safeActions, List<String> unsafeActions, Edit edit,
			Deque<UnsafeControlAction>[][] CG, int i, int j, int d, int iPrime, int jPrime, String invariantName) {
		CG[i][j] = new LinkedList<UnsafeControlAction>();
		String source = "##PLACEHOLDER##";
		Guideword guideword = null;
		String controlAction = null;
		List<String> context = null;
		if (edit == Edit.delete) {
			CG[i][j].addAll(CG[i - 1][j]);
			String deletedAction = safeActions.get(i - 1);
			if (deletedAction.equals(DELAY_ACTION)) {
				controlAction = unsafeActions.get(j);
				context = unsafeActions.subList(0, i - 1);
				guideword = Guideword.TooEarly;
			} else {
				controlAction = deletedAction;
				context = unsafeActions.subList(0, i - 1);
				guideword = Guideword.NotProviding;
			}
		} else if (edit == Edit.add) {
			CG[i][j].addAll(CG[i][j - 1]);
			String addedAction = unsafeActions.get(j - 1);
			if (addedAction.equals(DELAY_ACTION)) {
				// If the trace ends on a wait it doesn't make sense to check what's after the
				// wait
				if (unsafeActions.size() > j) {
					controlAction = unsafeActions.get(j);
				} else {
					controlAction = null;
				}
				context = unsafeActions.subList(0, i);
				guideword = Guideword.TooLate;
			} else {
				controlAction = addedAction;
				context = unsafeActions.subList(0, i);
				guideword = Guideword.Providing;
			}
		} else if (edit == Edit.substitute) {
			CG[i][j].addAll(CG[i - 1][j - 1]);
			if (d == 1) {
				String correctAction = safeActions.get(i - 1);
				String incorrectAction = unsafeActions.get(j - 1);
				if (!incorrectAction.equals(DELAY_ACTION) && !correctAction.equals(DELAY_ACTION)) {
					// Not implemented pending discussion
					controlAction = null; // Nullity prevents the UCA object from getting instantiated
					context = null;
					guideword = null;
				} else if (incorrectAction.equals(DELAY_ACTION)) {
					controlAction = correctAction;
					context = unsafeActions.subList(0, i - 1);
					guideword = Guideword.NotProviding;
				} else if (correctAction.equals(DELAY_ACTION)) {
					controlAction = incorrectAction;
					context = unsafeActions.subList(0, i - 1);
					guideword = Guideword.Providing;
				}
			}
		} else if (edit == Edit.transpose) {
			CG[i][j].addAll(CG[iPrime - 1][jPrime - 1]);
			String correctAction = safeActions.get(iPrime - 1);
			String incorrectAction = unsafeActions.get(jPrime - 1);
			if (!incorrectAction.equals(DELAY_ACTION) && !correctAction.equals(DELAY_ACTION)) {
				controlAction = incorrectAction;
				context = unsafeActions.subList(0, i - 2);
				guideword = Guideword.OutOfSequence;
			} else if (incorrectAction.equals(DELAY_ACTION)) {
				controlAction = correctAction;
				context = safeActions.subList(0, iPrime - 1);
				guideword = Guideword.TooLate;
			} else if (correctAction.equals(DELAY_ACTION)) {
				controlAction = incorrectAction;
				context = unsafeActions.subList(0, iPrime - 1);
				guideword = Guideword.TooEarly;
			}
		}
		if (guideword == null || controlAction == null || context == null) {
			return Optional.empty();
		} else {
			return Optional.of(new UnsafeControlAction(source, guideword, controlAction, context, invariantName));
		}
	}

	/**
	 * Calculates the unrestricted Damerau-Levenshtein distance
	 * 
	 * As close as possible to a direct implementation of Algorithm 2 from page 84
	 * of [1], except it uses lists of strings as inputs (where each string is
	 * treated as a token) instead of raw strings (with characters as tokens).
	 * 
	 * [1]: Boytsov, Leonid. 2011. “Indexing Methods for Approximate Dictionary
	 * Searching: Comparative Analysis.” ACM J. Exp. Algorithmics 16
	 * (May):1.1:1.1-1.1:1.91. https://doi.org/10.1145/1963190.1963191.
	 * 
	 * @param p The first string for comparison
	 * @param s The second string for comparison
	 * @return The edit distance from p to s
	 */
	public int unrestrictedDamerauLevenshtein(List<String> p, List<String> s) {
		int[][] C = new int[p.size() + 1][s.size() + 1];

		// This gets the alphabet of the strings: it de-duplicates them by combining
		// them
		// into a set, then puts them in a list since we need stable indices of elements
		List<String> Σ = Set.copyOf(Stream.concat(p.stream(), s.stream()).toList()).stream()
				.collect(Collectors.toList());

		int[] CP = new int[Σ.size() + 1];

		int iPrime, jPrime, CS;

		for (int i = 0; i <= p.size(); i++) {
			C[i][0] = i;
		}

		for (int j = 0; j <= s.size(); j++) {
			C[0][j] = j;
		}

		for (int i = 1; i <= Σ.size(); i++) {
			CP[i] = 0;
		}

		for (int i = 1; i <= p.size(); i++) {
			CS = 0;
			for (int j = 1; j <= s.size(); j++) {
				int d;
				if (p.get(i - 1).equals(s.get(j - 1))) {
					d = 0;
				} else {
					d = 1;
				}
				C[i][j] = Math.min(C[i - 1][j] + 1, Math.min(C[i][j - 1] + 1, C[i - 1][j - 1] + d));
				// CP[c] stores the largest index i' < i such that p[i'] = c.
				// CS stores the largest index j' < j such that s[j'] = p[i]
				iPrime = CP[Σ.indexOf(s.get(j - 1))];
				jPrime = CS;
				if (iPrime > 0 && jPrime > 0) {
					C[i][j] = Math.min(C[i][j], C[iPrime - 1][jPrime - 1] + (i - iPrime) + (j - jPrime) - 1);
				}
				if (p.get(i - 1).equals(s.get(j - 1))) {
					CS = j;
				}
			}
			CP[Σ.indexOf(p.get(i - 1))] = i;
		}
		return C[p.size()][s.size()];
	}
}