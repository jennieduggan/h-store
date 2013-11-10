package org.qcri.PartitioningPlanner.placement;

import org.qcri.PartitioningPlanner.placement.Plan;
import org.qcri.PartitioningPlanner.placement.FirstFitPlacement;

import edu.brown.BaseTestCase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;



public class TestFirstFitPlacement extends BaseTestCase {
	static Integer partitionCount = 4;
	static Integer tupleCount = 10000; // 10k tuples in a table
	static int seed = 1024;
	static Integer hotTupleCount = 10;
	static Integer accessRange = 1024; 
	static Integer hotTupleRange = 1000;

	
    
	public void testFirstFitPlacement() throws Exception {

		// start with an evenly distributed plan
		Plan aPlan = new Plan(); // start with uniform case
		Random generator = new Random(seed);
		FirstFitPlacement aPlacement = new FirstFitPlacement();

		Map<Integer, Integer> partitionTotals = new HashMap<Integer, Integer>();  // partitionID --> summed access count
		Map<Integer, Integer> hotTuples = new HashMap<Integer, Integer>();  // tupleId --> summed access count
		
		Integer tuplesPerInstance = tupleCount / partitionCount;
		Integer modulusCount = tupleCount % partitionCount;
		if(modulusCount > 0) {
			++tuplesPerInstance;
		}
		
		Integer startRange = 0;
		Integer endRange = tuplesPerInstance - 1; // inclusive
		
		System.out.println("Populating " + partitionCount + " partitions");
		for(Integer i = 0; i < partitionCount; ++i) {
			aPlan.addPartition(i);
			aPlan.addRange(i, startRange, endRange);
			if(i == modulusCount && modulusCount > 0) {
				--tuplesPerInstance;
			}
			startRange = endRange + 1;
			endRange = startRange + tuplesPerInstance - 1;
		}

		System.out.println("Starting with plan:");
		aPlan.printPlan();
		

		for(Integer i = 0; i < partitionCount; ++i) {
			partitionTotals.put(i, generator.nextInt(accessRange));			
		}

		System.out.println("Starting with load:");
		for(Integer i : partitionTotals.keySet()) {
			System.out.println("Partition " + i + ": " + partitionTotals.get(i));
		}
		
		for(Integer i = 0; i < hotTupleCount; ++i) {
			Integer tupleId = generator.nextInt(tupleCount);
			hotTuples.put(tupleId, generator.nextInt(hotTupleRange));
			Integer tupleLocation = aPlan.getTuplePartition(tupleId);
			//add capacity for partitionTotals
			partitionTotals.put(tupleLocation, hotTuples.get(tupleId) + partitionTotals.get(tupleLocation)); 		
				
		}

		System.out.println("After hot tuple insert have load:");
		for(Integer i : partitionTotals.keySet()) {
			System.out.println("Partition " + i + ": " + partitionTotals.get(i));
		}

		ArrayList<Map<Integer, Integer>> hotTuplesList = new ArrayList<Map<Integer, Integer>>();
		hotTuplesList.add(hotTuples);
		
		aPlan = aPlacement.computePlan(hotTuplesList, partitionTotals,  aPlan);

		System.out.println("Ending with plan:");
		aPlan.printPlan();

		System.out.println("Ending with load:");
		for(Integer i : partitionTotals.keySet()) {
			System.out.println("Partition " + i + ": " + partitionTotals.get(i));
		}
		aPlan.toJSON("test.txt");

	}
	
}