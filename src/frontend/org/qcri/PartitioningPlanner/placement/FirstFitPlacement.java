package org.qcri.PartitioningPlanner.placement;


import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.List;

import org.qcri.PartitioningPlanner.placement.Plan;


public class FirstFitPlacement extends Placement {
	
	Long coldPartitionWidth = 1000L; // redistribute cold tuples in chunks of 1000
	
	public FirstFitPlacement(){
		
	}
	
	// hotTuples: tupleId --> access count
	// siteLoads: partitionId --> total access count
	public Plan computePlan(ArrayList<Map<Long, Long>> hotTuplesList, Map<Integer, Long> partitionTotals, String planFilename){
		

		Integer dstPartition = -1;
		Long totalAccesses = 0L;
		Long targetCapacity;
		Map<Integer, Long> oldLoad = new HashMap<Integer, Long> ();
		Plan aPlan = new Plan(planFilename);
		Plan newPlan = new Plan();
		Long hotTupleCount = 0L;
		

		for(Integer i : partitionTotals.keySet()) {
			totalAccesses = totalAccesses + partitionTotals.get(i);			
			oldLoad.put(i,  partitionTotals.get(i));
			// zero out the load for a plan
			partitionTotals.put(i, 0L);
		}

		
		
		targetCapacity = totalAccesses / partitionTotals.size();		
		//System.out.println("Target capacity " + targetCapacity);
		
		Map<Integer, List<Plan.Range>> ranges = aPlan.getAllRanges();
		for(Integer i : ranges.keySet()) {
			newPlan.addPartition(i);
		}
		
		for(Map<Long, Long> hotTuples : hotTuplesList) {
			hotTupleCount = hotTupleCount + hotTuples.size();
		}
		
		// pack the hot tuples first
		for(Integer i = 0; i < hotTupleCount; ++i) {
				getHottestTuple(hotTuplesList);

				Boolean placed = false;
				for(Integer j : partitionTotals.keySet()) {
					if(partitionTotals.get(j) + _hotAccessCount <= targetCapacity) {
						dstPartition = j;
						placed = true;
						break;
					}
			
				} // end inner-for
			
				if(!placed) {
					dstPartition = getMostUnderloadedPartitionId(partitionTotals);
				}		
			
				//System.out.println("Processing hot tuple id " + _hotTupleId + " with access count " + _hotAccessCount + " sending it to " + dstPartition);

				partitionTotals.put(dstPartition,partitionTotals.get(dstPartition)  + _hotAccessCount);
				oldLoad.put(_srcPartition, oldLoad.get(_srcPartition) - _hotAccessCount);

				hotTuplesList.get(_srcPartition).remove(_hotTupleId);
				aPlan.removeTupleId(_srcPartition, _hotTupleId);
				newPlan.addRange(dstPartition, _hotTupleId, _hotTupleId);
			} // end outer-for
		
		
		
		for(Integer i : aPlan.getAllRanges().keySet()) { // foreach partition
			List<List<Plan.Range>> partitionSlices = aPlan.getRangeSlices(i,  coldPartitionWidth);
			if(partitionSlices.size() > 0) {
				Double tupleWeight = ((double) oldLoad.get(i)) / aPlan.getTupleCount(i); // per tuple

				for(List<Plan.Range> slice : partitionSlices) {  // for each slice

						Boolean placed = false;
						Integer newWeight = (int) (tupleWeight *  ((double) Plan.getRangeListWidth(slice)));

						for(Integer k : partitionTotals.keySet()) {
							if(partitionTotals.get(k) + newWeight <= targetCapacity) {
								dstPartition = k;
								placed = true;
								break;
							}
						}
						if(!placed) {
							dstPartition = getMostUnderloadedPartitionId(partitionTotals);
						}
						for(Plan.Range r : slice) { 
							newPlan.addRange(dstPartition, r.from, r.to);
						}
						partitionTotals.put(dstPartition, partitionTotals.get(dstPartition) + newWeight);

				} // end destination partition selection 

					

				} // end for each slice
				
			
			
			
		} // end for each partition
		
		return newPlan;

	}
	

}
