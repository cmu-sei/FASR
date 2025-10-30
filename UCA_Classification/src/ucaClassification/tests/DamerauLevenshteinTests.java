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

package ucaClassification.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import ucaClassification.DamerauLevenshteinClassifier;
import ucaClassification.DamerauLevenshteinClassifier.Activity;

class DamerauLevenshteinTests {

	private static String invariantName = "##INVARIANT-PLACEHOLDER##";
	private static String sourceName = "##SOURCE-PLACEHOLDER##";
	private static DamerauLevenshteinClassifier dlc = new DamerauLevenshteinClassifier();

	@BeforeAll
	static void setUpBeforeClass() throws Exception {
	}

	@AfterAll
	static void tearDownAfterClass() throws Exception {
	}

	@BeforeEach
	void setUp() throws Exception {
	}

	@AfterEach
	void tearDown() throws Exception {
	}

	/*-
	 * 
	 * 
	 * | **Edit** | **Removed** | **Guidewords** | **Control Action** |
	 * | -------- | ----------- | -------------- | ------------------ |
	 * | Deletion | Delay       | Too Early      | Early action       |
	 * | Deletion | Any         | Not Providing  | Removed action     |
	 *  
	 * | **Edit** | **Added** | **Guidewords** | **Control Action** |
	 * | -------- | --------- | -------------- | ------------------ |
	 * | Addition | Delay     | Too Late       | Late action        |
	 * | Addition | Any       | Providing      | Additional action  |
	 * 
	 * | **Edit**      | **Correct** | **Incorrect** | **Guideword(s)**         | **Control Action** |
	 * | ------------- | ----------- | ------------- | ------------------------ | ------------------ |
	 * | Substitution  | Any         | Any           | Providing                | Provided Action    |
	 * | Substitution  | Any         | Delay         | Not Providing            | Removed action     |
	 * | Substitution  | Delay       | Any           | Providing                | Additional action  |
	 * | Transposition | Any         | Any           | Out of Order             | Incorrect action   |
	 * | Transposition | Any         | Delay         | Too Late                 | Late action        |
	 * | Transposition | Delay       | Any           | Too Early                | Early              |
	 * 
	 */

	@Nested
	public class DeletionTests {

		@Test
		void testDeletionEarly() {
			var safe = Arrays.asList("Init", "Sys.TurnPumpOn", "Wait", "Wait", "Wait", "Sys.TurnPumpOff");
			var unsafe = Arrays.asList("Init", "Sys.TurnPumpOn", "Wait", "Wait", "Sys.TurnPumpOff");
			var expected = new DamerauLevenshteinClassifier.UnsafeControlAction(sourceName, // Source
					DamerauLevenshteinClassifier.Guideword.TOO_EARLY, // Guideword
					"Sys.TurnPumpOff", // Control Action
					"After \"Init\" -> \"Sys.TurnPumpOn\" -> \"Wait\" -> \"Wait\" the environment should have performed a \"Wait\" action, but instead performed a \"Sys.TurnPumpOff\" early.",
					invariantName// Violated Constraint
			);
			var actual = dlc.classify(safe, unsafe, invariantName, sourceName);
			assertEquals(expected, actual);
		}

		@Test
		void testDeletionGeneric() {
			var safe = Arrays.asList("Init", "Sys.TurnPumpOn", "Wait", "Wait", "Wait", "Sys.TurnPumpOff");
			var unsafe = Arrays.asList("Init", "Wait", "Wait", "Wait", "Sys.TurnPumpOff");
			var expected = new DamerauLevenshteinClassifier.UnsafeControlAction(sourceName, // Source
					DamerauLevenshteinClassifier.Guideword.NOT_PROVIDING, // Guideword
					"Sys.TurnPumpOn", // Control Action
					"After \"Init\" the environment should have performed a \"Sys.TurnPumpOn\" action, but instead did nothing. It subsequently performed \"Wait\" -> \"Wait\" -> \"Wait\" -> \"Sys.TurnPumpOff\".",
					invariantName// Violated Constraint
			);
			var actual = dlc.classify(safe, unsafe, invariantName, sourceName);
			assertEquals(expected, actual);
		}
	}

	@Nested
	public class AdditionTests {

		@Test
		void testAdditionLate() {
			var safe = Arrays.asList("Init", "Sys.TurnPumpOn", "Wait", "Wait", "Sys.TurnPumpOff");
			var unsafe = Arrays.asList("Init", "Sys.TurnPumpOn", "Wait", "Wait", "Wait", "Sys.TurnPumpOff");
			var expected = new DamerauLevenshteinClassifier.UnsafeControlAction(sourceName, // Source
					DamerauLevenshteinClassifier.Guideword.TOO_LATE, // Guideword
					"Sys.TurnPumpOff", // Control Action
					"After \"Init\" -> \"Sys.TurnPumpOn\" -> \"Wait\" -> \"Wait\" the environment performed an unexpected \"Wait\" action, making \"Sys.TurnPumpOff\" late.",
					invariantName// Violated Constraint
			);
			var actual = dlc.classify(safe, unsafe, invariantName, sourceName);
			assertEquals(expected, actual);
		}

		@Test
		void testAdditionGeneric() {
			var safe = Arrays.asList("Init", "Sys.TurnPumpOn", "Wait", "Wait", "Wait", "Sys.TurnPumpOff");
			var unsafe = Arrays.asList("Init", "Sys.TurnPumpOn", "Init", "Wait", "Wait", "Wait", "Sys.TurnPumpOff");
			var expected = new DamerauLevenshteinClassifier.UnsafeControlAction(sourceName, // Source
					DamerauLevenshteinClassifier.Guideword.PROVIDING, // Guideword
					"Init", // Control Action
					"After \"Init\" -> \"Sys.TurnPumpOn\" the environment performed an unexpected \"Init\" action. It subsequently performed \"Wait\" -> \"Wait\" -> \"Wait\" -> \"Sys.TurnPumpOff\".",
					invariantName// Violated Constraint
			);
			var actual = dlc.classify(safe, unsafe, invariantName, sourceName);
			assertEquals(expected, actual);
		}
	}

	@Nested
	public class SubstitutionTests {

		@Test
		void testSubstitutionGeneric() {
			var safe = Arrays.asList("Init", "Sys.TurnPumpOn", "Wait", "Wait", "Wait", "Sys.TurnPumpOff");
			var unsafe = Arrays.asList("Init", "Sys.TurnPumpOff", "Wait", "Wait", "Wait", "Sys.TurnPumpOff");
			var expected = new DamerauLevenshteinClassifier.UnsafeControlAction(sourceName, // Source
					DamerauLevenshteinClassifier.Guideword.PROVIDING, // Guideword
					"Sys.TurnPumpOff", // Control Action
					"After \"Init\" the environment should have performed a \"Sys.TurnPumpOn\" action, but instead it performed \"Sys.TurnPumpOff\". It subsequently performed \"Wait\" -> \"Wait\" -> \"Wait\" -> \"Sys.TurnPumpOff\".",
					invariantName// Violated Constraint
			);
			var actual = dlc.classify(safe, unsafe, invariantName, sourceName);
			assertEquals(expected, actual);
		}

		@Test
		void testSubstitutionNotProviding() {

			// Standard substitution
			var safe = Arrays.asList("Init", "Sys.TurnPumpOn", "Wait", "Wait", "Wait", "Sys.TurnPumpOff");
			var unsafe = Arrays.asList("Init", "Wait", "Wait", "Wait", "Wait", "Sys.TurnPumpOff");
			var expected = new DamerauLevenshteinClassifier.UnsafeControlAction(sourceName, // Source
					DamerauLevenshteinClassifier.Guideword.NOT_PROVIDING, // Guideword
					"Sys.TurnPumpOn", // Control Action
					"After \"Init\" the environment did not perform the expected \"Sys.TurnPumpOn\" action; it instead performed \"Wait\". It subsequently performed \"Wait\" -> \"Wait\" -> \"Wait\" -> \"Sys.TurnPumpOff\".",
					invariantName// Violated Constraint
			);
			var actual = dlc.classify(safe, unsafe, invariantName, sourceName);
			assertEquals(expected, actual);
		}

		@Test
		void testSubstitutionProviding() {
			var safe = Arrays.asList("Init", "Sys.TurnPumpOn", "Wait", "Wait", "Wait", "Sys.TurnPumpOff");
			var unsafe = Arrays.asList("Init", "Sys.TurnPumpOn", "Wait", "Wait", "Sys.TurnPumpOff", "Sys.TurnPumpOff");
			var expected = new DamerauLevenshteinClassifier.UnsafeControlAction(sourceName, // Source
					DamerauLevenshteinClassifier.Guideword.PROVIDING, // Guideword
					"Sys.TurnPumpOff", // Control Action
					"After \"Init\" -> \"Sys.TurnPumpOn\" -> \"Wait\" -> \"Wait\" the environment should have performed a \"Wait\" action, but instead it performed \"Sys.TurnPumpOff\". It subsequently performed a \"Sys.TurnPumpOff\" action.",
					invariantName// Violated Constraint
			);
			var actual = dlc.classify(safe, unsafe, invariantName, sourceName);
			assertEquals(expected, actual);
		}
	}

	@Nested
	public class TranspositionTests {
		@Test
		void testTranspositionGeneric() {
			var safe = Arrays.asList("Init", "Sys.TurnPumpOn", "Wait", "Wait", "Wait", "Sys.TurnPumpOff");
			var unsafe = Arrays.asList("Sys.TurnPumpOn", "Init", "Wait", "Wait", "Wait", "Sys.TurnPumpOff");
			var expected = new DamerauLevenshteinClassifier.UnsafeControlAction(sourceName, // Source
					DamerauLevenshteinClassifier.Guideword.OUT_OF_SEQUENCE, // Guideword
					"Sys.TurnPumpOn", // Control Action
					"At the start, the environment should have performed \"Init\" -> \"Sys.TurnPumpOn\" but instead swapped their order and performed \"Sys.TurnPumpOn\" -> \"Init\". It subsequently performed \"Wait\" -> \"Wait\" -> \"Wait\" -> \"Sys.TurnPumpOff\".",
					invariantName// Violated Constraint
			);
			var actual = dlc.classify(safe, unsafe, invariantName, sourceName);
			assertEquals(expected, actual);
		}

		@Test
		void testTranspositionLate() {
			var safe = Arrays.asList("Init", "Sys.TurnPumpOn", "Wait", "Wait", "Sys.TurnPumpOff", "Wait");
			var unsafe = Arrays.asList("Init", "Sys.TurnPumpOn", "Wait", "Wait", "Wait", "Sys.TurnPumpOff");
			var expected = new DamerauLevenshteinClassifier.UnsafeControlAction(sourceName, // Source
					DamerauLevenshteinClassifier.Guideword.TOO_LATE, // Guideword
					"Sys.TurnPumpOff", // Control Action
					"After \"Init\" -> \"Sys.TurnPumpOn\" -> \"Wait\" -> \"Wait\" the environment should have performed \"Sys.TurnPumpOff\" -> \"Wait\" but instead swapped their order, making \"Sys.TurnPumpOff\" late.",
					invariantName// Violated Constraint
			);
			var actual = dlc.classify(safe, unsafe, invariantName, sourceName);
			assertEquals(expected, actual);
		}

		@Test
		void testTranspositionEarly() {
			var safe = Arrays.asList("Init", "Sys.TurnPumpOn", "Wait", "Wait", "Wait", "Sys.TurnPumpOff");
			var unsafe = Arrays.asList("Init", "Sys.TurnPumpOn", "Wait", "Wait", "Sys.TurnPumpOff", "Wait");
			var expected = new DamerauLevenshteinClassifier.UnsafeControlAction(sourceName, // Source
					DamerauLevenshteinClassifier.Guideword.TOO_EARLY, // Guideword
					"Sys.TurnPumpOff", // Control Action
					"After \"Init\" -> \"Sys.TurnPumpOn\" -> \"Wait\" -> \"Wait\" the environment should have performed \"Wait\" -> \"Sys.TurnPumpOff\" but instead swapped their order, making \"Sys.TurnPumpOff\" early. It subsequently performed a \"Wait\" action.",
					invariantName// Violated Constraint
			);
			var actual = dlc.classify(safe, unsafe, invariantName, sourceName);
			assertEquals(expected, actual);
		}
	}

	@Nested
	public class DurationTests {
		private static Activity fillTank = new Activity("Sys.TurnPumpOn", "Sys.TurnPumpOff");
		private static DamerauLevenshteinClassifier dlc = new DamerauLevenshteinClassifier(
				Map.of("FillTank", fillTank));

		@Test
		void testStoppedTooSoon() {
			var safe = Arrays.asList("Init", "Sys.TurnPumpOn", "Wait", "Wait", "Wait", "Sys.TurnPumpOff");
			var unsafe = Arrays.asList("Init", "Sys.TurnPumpOn", "Wait", "Wait", "Sys.TurnPumpOff");
			var expected = new DamerauLevenshteinClassifier.UnsafeControlAction(sourceName, // Source
					DamerauLevenshteinClassifier.Guideword.STOPPED_TOO_SOON, // Guideword
					"Sys.TurnPumpOff", // Control Action
					"After \"Init\" -> \"Sys.TurnPumpOn\" -> \"Wait\" -> \"Wait\" the environment should have performed an additional \"Wait\" but didn't; \"Sys.TurnPumpOff\" was early and stopped the \"FillTank\" activity too soon.",
					invariantName// Violated Constraint
			);
			var actual = dlc.classify(safe, unsafe, invariantName, sourceName);
			assertEquals(expected, actual);
		}

		@Test
		void testAppliedTooLong() {
			var safe = Arrays.asList("Init", "Sys.TurnPumpOn", "Wait", "Wait", "Sys.TurnPumpOff");
			var unsafe = Arrays.asList("Init", "Sys.TurnPumpOn", "Wait", "Wait", "Wait", "Sys.TurnPumpOff");
			var expected = new DamerauLevenshteinClassifier.UnsafeControlAction(sourceName, // Source
					DamerauLevenshteinClassifier.Guideword.APPLIED_TOO_LONG, // Guideword
					"Sys.TurnPumpOff", // Control Action
					"After \"Init\" -> \"Sys.TurnPumpOn\" -> \"Wait\" -> \"Wait\" the environment should have performed \"Sys.TurnPumpOff\" but performed \"Wait\" instead. This allowed the \"FillTank\" activity to go on too long.",
					invariantName// Violated Constraint
			);
			var actual = dlc.classify(safe, unsafe, invariantName, sourceName);
			assertEquals(expected, actual);
		}
	}

//	@Test
//	void testJSON() {
//		// Used to examine classification of fortis output, not a true test
//		var actual = dlc.classifyFortisOutput(new File("resources/fortis-out.json"));
//	}

	@Nested
	public class IssueTests {
		// Tests relating to specific issues exposed in integration / other testing

		@Test
		void testMultipleWaits_TooMany() {
			var safe = Arrays.asList("TurnBSCUOn", "Wait", "Wait", "TurnBSCUOff");
			var unsafe = Arrays.asList("TurnBSCUOn", "Wait", "Wait", "Wait", "Wait", "TurnBSCUOff");
			var expected = new DamerauLevenshteinClassifier.UnsafeControlAction(sourceName, // Source
					DamerauLevenshteinClassifier.Guideword.TOO_LATE, // Guideword
					"TurnBSCUOff", // Control Action
					"After \"TurnBSCUOn\" -> \"Wait\" -> \"Wait\" the environment performed an unexpected \"Wait\" action, making \"TurnBSCUOff\" late. It subsequently performed \"Wait\" -> \"TurnBSCUOff\".",
					invariantName// Violated Constraint
			);
			var actual = dlc.classify(safe, unsafe, invariantName, sourceName);
			assertEquals(expected, actual);
		}

		@Test
		void testMultipleWaits_TooFew() {
			var safe = Arrays.asList("TurnBSCUOn", "Wait", "Wait", "Wait", "Wait", "TurnBSCUOff");
			var unsafe = Arrays.asList("TurnBSCUOn", "Wait", "Wait", "TurnBSCUOff");
			var expected = new DamerauLevenshteinClassifier.UnsafeControlAction(sourceName, // Source
					DamerauLevenshteinClassifier.Guideword.TOO_EARLY, // Guideword
					"TurnBSCUOff", // Control Action
					"PLACEHOLDER",
					invariantName// Violated Constraint
			);
			var actual = dlc.classify(safe, unsafe, invariantName, sourceName);
			assertEquals(expected, actual);
		}

		@Test
		void testSingleElementSafeTrace() {
			var safe = Arrays.asList("TurnBSCUOn");
			var unsafe = Arrays.asList("Other", "Other", "Other", "Other", "TurnBSCUOn");
			var expected = new DamerauLevenshteinClassifier.UnsafeControlAction(sourceName, // Source
					DamerauLevenshteinClassifier.Guideword.PROVIDING, // Guideword
					"Other", // Control Action
					"PLACEHOLDER",
					invariantName// Violated Constraint
			);
			var actual = dlc.classify(safe, unsafe, invariantName, sourceName);
			assertEquals(expected, actual);
		}

		@Test
		void testSingleElementUnsafeTrace() {
			var safe = Arrays.asList("Wait", "Wait", "Wait", "Wait", "TurnBSCUOn");
			var unsafe = Arrays.asList("TurnBSCUOn");
			var expected = new DamerauLevenshteinClassifier.UnsafeControlAction(sourceName, // Source
					DamerauLevenshteinClassifier.Guideword.TOO_EARLY, // Guideword
					"TurnBSCUOn", // Control Action
					"PLACEHOLDER",
					invariantName// Violated Constraint
			);
			var actual = dlc.classify(safe, unsafe, invariantName, sourceName);
			assertEquals(expected, actual);
		}
	}

	@Test
	void testNoError() {
		var safe = Arrays.asList("Init", "Sys.TurnPumpOn", "Wait", "Wait", "Wait", "Sys.TurnPumpOff");
		var unsafe = Arrays.asList("Init", "Sys.TurnPumpOn", "Wait", "Wait", "Wait", "Sys.TurnPumpOff");
		var expected = "The unsafe trace is identical to the safe trace; there is no error to classify.";
		IllegalArgumentException actual = assertThrows(IllegalArgumentException.class,
				() -> dlc.classify(safe, unsafe, invariantName, sourceName));
		assertEquals(expected, actual.getMessage());
	}
}
