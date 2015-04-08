/*-
 * APT - Analysis of Petri Nets and labeled Transition systems
 * Copyright (C) 2014  Uli Schlachter
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package uniol.apt.analysis.synthesize.separation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import uniol.apt.adt.ts.Arc;
import uniol.apt.adt.ts.State;
import uniol.apt.analysis.synthesize.PNProperties;
import uniol.apt.analysis.synthesize.Region;
import uniol.apt.analysis.synthesize.RegionUtility;
import uniol.apt.analysis.synthesize.UnreachableException;
import uniol.apt.util.equations.InequalitySystem;

import static uniol.apt.util.DebugUtil.debug;

/**
 * Helper class for solving separation problems.
 * @author Uli Schlachter
 */
class InequalitySystemSeparation implements Separation {
	private final RegionUtility utility;
	private final PNProperties properties;
	private final String[] locationMap;

	// These must be satisfied
	private final InequalitySystem system;

	// Out of these, only one from each entry is required
	private final InequalitySystem[][] additionalSystems;

	private final int systemWeightsStart;
	private final int systemCoefficientsStart;
	private final int systemForwardWeightsStart;
	private final int systemBackwardWeightsStart;
	private final int systemInitialMarking;
	private final int systemNumberOfVariables;

	/**
	 * Construct a new instance for solving separation problems.
	 * @param utility The region utility to use.
	 * @param properties Properties that the calculated region should satisfy.
	 * @param locationMap Mapping that describes the location of each event.
	 */
	public InequalitySystemSeparation(RegionUtility utility, PNProperties properties, String[] locationMap) {
		this.utility = utility;
		this.properties = new PNProperties(properties);
		this.systemWeightsStart = 0;
		this.systemCoefficientsStart = systemWeightsStart + utility.getNumberOfEvents();
		this.systemForwardWeightsStart = systemCoefficientsStart + utility.getRegionBasis().size();
		this.systemBackwardWeightsStart = systemForwardWeightsStart + utility.getNumberOfEvents();
		this.systemInitialMarking = systemBackwardWeightsStart + utility.getNumberOfEvents();
		this.systemNumberOfVariables = systemInitialMarking + 1;
		this.locationMap = locationMap;

		debug("Variables:");
		debug("Weights start at ", systemWeightsStart);
		debug("Coefficients from basis start at ", systemCoefficientsStart);
		debug("Forward weights start at ", systemForwardWeightsStart);
		debug("Backward weights start at ", systemBackwardWeightsStart);
		debug("Initial marking is variable ", systemInitialMarking);

		this.system = makeInequalitySystem();

		if (properties.isKBounded())
			requireKBounded(properties.getKForKBounded());
		// Our definition of conflict-free requires plainness
		if (properties.isPlain() || properties.isConflictFree())
			requirePlainness();
		
		// ON is handled in SeparationUtility by messing with the locationMap
		assert !properties.isOutputNonbranching();

		int additional = 1;
		if (properties.isTNet())
			additional += 2;
		if (properties.isConflictFree())
			additional++;

		int index = 0;
		additionalSystems = new InequalitySystem[additional][];
		additionalSystems[index++] = requireDistributableNet();
		if (properties.isConflictFree())
			additionalSystems[index++] = requireConflictFree();
		if (properties.isTNet()) {
			additionalSystems[index++] = requireTNetPostset();
			additionalSystems[index++] = requireTNetPreset();
		}

	}

	/**
	 * Get an array of coefficients that describe the marking of the given state.
	 * @param state The state whose marking should be calculated.
	 * @return An array of coefficients or null if the state is not reachable.
	 */
	private int[] coefficientsForStateMarking(State state) throws UnreachableException {
		List<Integer> stateParikhVector = utility.getReachingParikhVector(state);
		int[] inequality = new int[systemNumberOfVariables];

		// Evaluate the Parikh vector in the region described by the system, just as
		// Region.evaluateParikhVector() would do.
		for (int event = 0; event < stateParikhVector.size(); event++)
			inequality[systemWeightsStart + event] = stateParikhVector.get(event);

		inequality[systemInitialMarking] = 1;

		return inequality;
	}

	/**
	 * Create an inequality system for calculating a region.
	 * @return An inequality system prepared for calculating separating regions.
	 */
	private InequalitySystem makeInequalitySystem() {
		// Generate an inequality system. The first eventList.size() variables are the weight of the calculated
		// region. The next basis.size() variables represent how this region is a linear combination of the
		// basis.
		final int events = utility.getNumberOfEvents();
		final List<Region> basis = utility.getRegionBasis();
		final int basisSize = basis.size();
		InequalitySystem sys = new InequalitySystem();

		// The resulting region is a linear combination of the basis:
		//   region = sum lambda_i * r^i
		//        0 = sum lambda_i * r^i - region
		for (int thisEvent = 0; thisEvent < events; thisEvent++) {
			int[] inequality = new int[events + basisSize];
			inequality[systemWeightsStart + thisEvent] = -1;
			int basisEntry = 0;
			for (Region region : basis)
				inequality[systemCoefficientsStart + basisEntry++] = region.getWeight(thisEvent);

			sys.addInequality(0, "=", inequality, "Resulting region is a linear combination "
					+ "of basis for event " + thisEvent);
		}

		// The weight is a combination of the forward and backward weight
		int inequalitySize = systemBackwardWeightsStart + utility.getNumberOfEvents();
		for (int thisEvent = 0; thisEvent < events; thisEvent++) {
			// weight = forwardWeight - backwardWeight (=> 0 = -w + f - b)
			int[] inequality = new int[inequalitySize];
			inequality[systemWeightsStart + thisEvent] = -1;
			inequality[systemForwardWeightsStart + thisEvent] = 1;
			inequality[systemBackwardWeightsStart + thisEvent] = -1;
			sys.addInequality(0, "=", inequality, "weight = forward - backward for event " + thisEvent);

			// Forward weight must be non-negative
			inequality = new int[inequalitySize];
			inequality[systemForwardWeightsStart + thisEvent] = 1;
			sys.addInequality(0, "<=", inequality, "Forward weight must be positive");

			// Backward weight must be non-negative
			inequality = new int[inequalitySize];
			inequality[systemBackwardWeightsStart + thisEvent] = 1;
			sys.addInequality(0, "<=", inequality, "Backward weight must be positive");
		}

		// Any enabled event really must be enabled in the calculated region
		for (Arc arc : utility.getTransitionSystem().getEdges()) {
			// r_B(event) <= r_S(state) = r_S(s0) + r_E(Psi_state)
			State state = arc.getSource();
			int[] inequality;
			try {
				inequality = coefficientsForStateMarking(state);
			} catch (UnreachableException e) {
				continue;
			}
			inequality[systemBackwardWeightsStart + utility.getEventIndex(arc.getLabel())] += -1;

			sys.addInequality(0, "<=", inequality, "Event " + arc.getLabel()
					+ " is enabled in state " + state);
		}

		return sys;
	}

	/**
	 * Add the needed inequalities so that the system may only produce k-bounded regions.
	 * @param k The limit for the bound.
	 */
	private void requireKBounded(int k) {
		// Require k >= r_S(s) = r_S(s0) + r_E(Psi_s)
		for (State state : utility.getTransitionSystem().getNodes()) {
			try {
				// k >= r_S(state)
				int[] inequality = coefficientsForStateMarking(state);
				system.addInequality(k, ">=", inequality, "State " + state
						+ " must obey " + k + "-bounded");
			} catch (UnreachableException e) {
				continue;
			}
		}
	}

	/**
	 * Add the needed inequalities so that the system may only produce plain regions.
	 */
	private void requirePlainness() {
		for (int event = 0; event < utility.getNumberOfEvents(); event++) {
			int[] inequality = new int[systemNumberOfVariables];

			inequality[systemForwardWeightsStart + event] = 1;
			inequality[systemBackwardWeightsStart + event] = 0;
			system.addInequality(1, ">=", inequality, "Plain");

			inequality[systemForwardWeightsStart + event] = 0;
			inequality[systemBackwardWeightsStart + event] = 1;
			system.addInequality(1, ">=", inequality, "Plain");
		}
	}

	/**
	 * Add the needed inequalities so that the system may only produce T-Net regions - postset part.
	 */
	private InequalitySystem[] requireTNetPostset() {
		final int numberEvents = utility.getNumberOfEvents();
		InequalitySystem[] result = new InequalitySystem[numberEvents];
		int index = 0;
		for (int event = 0; event < numberEvents; event++) {
			int[] inequality = new int[systemNumberOfVariables];
			Arrays.fill(inequality, systemForwardWeightsStart,
					systemForwardWeightsStart + utility.getNumberOfEvents(), 1);
			inequality[systemForwardWeightsStart + event] = 0;

			result[index] = new InequalitySystem();
			result[index].addInequality(0, "=", inequality, "Only event" + utility.getEventList().get(event)
					+ " produces");
			index++;
		}

		return result;
	}

	/**
	 * Add the needed inequalities so that the system may only produce T-Net regions - preset part.
	 */
	private InequalitySystem[] requireTNetPreset() {
		final int numberEvents = utility.getNumberOfEvents();
		InequalitySystem[] result = new InequalitySystem[numberEvents];
		int index = 0;
		for (int event = 0; event < numberEvents; event++) {
			int[] inequality = new int[systemNumberOfVariables];
			Arrays.fill(inequality, systemBackwardWeightsStart,
					systemBackwardWeightsStart + utility.getNumberOfEvents(), 1);
			inequality[systemBackwardWeightsStart + event] = 0;

			result[index] = new InequalitySystem();
			result[index].addInequality(0, "=", inequality, "Only event" + utility.getEventList().get(event)
					+ " consumes");
			index++;
		}

		return result;
	}

	/**
	 * Add the needed inequalities to guarantee that a distributable Petri Net region is calculated.
	 * @return A set of inequality systems of which at least one has to be satisfied.
	 */
	private InequalitySystem[] requireDistributableNet() {
		Set<String> locations = new HashSet<>(Arrays.asList(locationMap));
		locations.remove(null);
		if (locations.isEmpty())
			// No locations specified
			return new InequalitySystem[0];

		InequalitySystem[] result = new InequalitySystem[locations.size()];
		int index = 0;
		for (String location : locations) {
			int[] inequality = new int[systemNumberOfVariables];

			// Only events having location "location" may consume token.
			for (int eventIndex = 0; eventIndex < utility.getNumberOfEvents(); eventIndex++) {
				if (locationMap[eventIndex] != null && !locationMap[eventIndex].equals(location))
					inequality[systemBackwardWeightsStart + eventIndex] = 1;
			}

			result[index] = new InequalitySystem();
			result[index].addInequality(0, "=", inequality, "Only events with location " + location
					+ " may consume tokens from this region");
			index++;
		}

		return result;
	}

	/**
	 * Generate the necessary inequalities for a conflict free solution.
	 */
	private InequalitySystem[] requireConflictFree() {
		InequalitySystem[] result = new InequalitySystem[utility.getNumberOfEvents() + 1];
		int index = 0;

		// Conflict free: Either there is just a single transition consuming token...
		// (And thus this automatically satisfies any distribution)
		for (int event = 0; event < utility.getNumberOfEvents(); event++) {
			int[] inequality = new int[systemNumberOfVariables];
			Arrays.fill(inequality, 0);
			Arrays.fill(inequality, systemBackwardWeightsStart,
						systemBackwardWeightsStart + utility.getNumberOfEvents(), 1);
			inequality[systemBackwardWeightsStart + event] = 0;

			result[index] = new InequalitySystem();
			result[index].addInequality(0, "=", inequality, "Only event "
					+ utility.getEventList().get(event) + " may consume tokens");
			index++;
		}

		// ...or the preset is contained in the postset
		result[index] = new InequalitySystem();
		for (int event = 0; event < utility.getNumberOfEvents(); event++) {
			int[] inequality = new int[systemNumberOfVariables];
			inequality[systemWeightsStart + event] = 1;
			result[index].addInequality(0, "<=", inequality, "Preset contains postset for event "
					+ utility.getEventList().get(event) + " (No tokens are consumed)");
		}

		return result;
	}

	/**
	 * Try to solve the given inequality system and create a region from the solution found.
	 * @param system An inequality system that is suitably prepared.
	 * @param anyOf A array of additional systems of which at least one must be satisfied
	 * @param pure Whether the generated region should describe part of a pure Petri Net and thus must not generate
	 * any side-conditions.
	 * @return A region or null.
	 */
	private Region regionFromSolution(InequalitySystem sys, boolean pure) {
		InequalitySystem[][] systemToSolve = Arrays.copyOf(additionalSystems, additionalSystems.length + 2);
		systemToSolve[additionalSystems.length] = new InequalitySystem[] { sys };
		systemToSolve[additionalSystems.length + 1] = new InequalitySystem[] { system };

		List<Integer> solution = InequalitySystem.findSolution(systemToSolve);
		if (solution.isEmpty())
			return null;

		final int events = utility.getNumberOfEvents();
		Region r = new Region(utility,
				solution.subList(systemBackwardWeightsStart, systemBackwardWeightsStart + events),
				solution.subList(systemForwardWeightsStart, systemForwardWeightsStart + events))
			.withInitialMarking(solution.get(systemInitialMarking));
		debug("region: ", r);

		if (pure)
			r = r.makePure();
		assert r.getNormalRegionMarking() <= solution.get(systemInitialMarking) : solution;
		return r;
	}

	/**
	 * Get a region solving some separation problem.
	 * @param state The first state of the separation problem
	 * @param otherState The second state of the separation problem
	 * @return A region solving the problem or null.
	 */
	@Override
	public Region calculateSeparatingRegion(State state, State otherState) {
		// Unreachable states cannot be separated
		if (!utility.getSpanningTree().isReachable(state) || !utility.getSpanningTree().isReachable(otherState))
			return null;

		InequalitySystem solveSeparation = new InequalitySystem();
		try {
			// We want r_S(s) != r_S(s'). Since for each region there exists a complementary region (we are
			// only looking at the bounded case!), we can require r_S(s) < r_S(s')
			int[] inequality = coefficientsForStateMarking(state);
			int[] otherInequality = coefficientsForStateMarking(otherState);

			for (int i = 0; i < inequality.length; i++)
				inequality[i] -= otherInequality[i];

			solveSeparation.addInequality(-1, ">=", inequality, "Region should separate state " + state
					+ " from state " + otherState);
		} catch (UnreachableException e) {
			throw new AssertionError("Made sure state is reachable, but still it isn't?!", e);
		}

		return regionFromSolution(solveSeparation, properties.isPure());
	}

	/**
	 * Get a region solving some separation problem.
	 * @param state The state of the separation problem
	 * @param event The event of the separation problem
	 * @return A region solving the problem or null.
	 */
	@Override
	public Region calculateSeparatingRegion(State state, String event) {
		// Unreachable states cannot be separated
		if (!utility.getSpanningTree().isReachable(state))
			return null;

		InequalitySystem solveSeparation = new InequalitySystem(this.system);
		try {
			final int eventIndex = utility.getEventIndex(event);

			// Each state must be reachable in the resulting region, but event 'event' should be disabled
			// in state. We want -1 >= r_S(s) - r_B(event)
			int[] inequality = coefficientsForStateMarking(state);

			if (properties.isPure()) {
				// In the pure case, in the above -r_B(event) is replaced with +r_E(event). Since all
				// states must be reachable, this makes sure that r_E(event) really is negative and thus
				// the resulting region solves ESSP.
				inequality[systemWeightsStart + eventIndex] += 1;
			} else {
				inequality[systemBackwardWeightsStart + eventIndex] += -1;
			}

			solveSeparation.addInequality(-1, ">=", inequality, "Region should separate state " + state
					+ " from event " + event);
		} catch (UnreachableException e) {
			throw new AssertionError("Made sure state is reachable, but still it isn't?!", e);
		}

		return regionFromSolution(solveSeparation, properties.isPure());
	}
}

// vim: ft=java:noet:sw=8:sts=8:ts=8:tw=120
