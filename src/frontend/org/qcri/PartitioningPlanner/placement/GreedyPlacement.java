package org.qcri.PartitioningPlanner.placement;


import java.util.ArrayList;
import java.util.Map;

import org.qcri.PartitioningPlanner.placement.Plan;


public class GreedyPlacement extends Placement {
	
	public GreedyPlacement(){
		
	}
	
	
	// hotTuples: tupleId --> access count
	// siteLoads: partitionId --> total access count
	public Plan computePlan(ArrayList<Map<Long, Long>> hotTuplesList, Map<Integer, Long> partitionTotals, String planFilename){
		
		Integer dstPartition;
		Long totalAccesses = 0L;
		Long meanAccesses;
		Integer partitionId = 0;
		Long hotTupleCount = 0L;
		
		Plan aPlan = new Plan(planFilename);

		for(Integer i : partitionTotals.keySet()) {
			totalAccesses = totalAccesses + partitionTotals.get(i);			
		}
		
		meanAccesses = totalAccesses / partitionTotals.size();

		System.out.println("Mean access count: " + meanAccesses);
		
		for(Map<Long, Long> hotTuples : hotTuplesList) {
			hotTupleCount = hotTupleCount + hotTuples.size();
		}

		System.out.println("Received " + hotTupleCount + " hot tuples.");
		
		for(Long i = 0L; i < hotTupleCount; ++i) {
			getHottestTuple(hotTuplesList);
			//System.out.println("Processing hot tuple id " + _hotTupleId + " with access count " + _hotAccessCount);

			if(partitionTotals.get(_srcPartition) > meanAccesses) {
					dstPartition = getMostUnderloadedPartitionId(partitionTotals);
					if(dstPartition != _srcPartition) {
						//System.out.println(" sending it to " + dstPartition);
						partitionTotals.put(_srcPartition, partitionTotals.get(_srcPartition)  - _hotAccessCount);
						partitionTotals.put(dstPartition,partitionTotals.get(dstPartition)  + _hotAccessCount);
						aPlan.removeTupleId(_srcPartition, _hotTupleId);
						aPlan.addRange(dstPartition, _hotTupleId, _hotTupleId);
					}
				}
			hotTuplesList.get(_srcPartition).remove(_hotTupleId);

		}

		return aPlan;
		
	}
	

}
