package org.qcri.PartitioningPlanner.placement;


import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

import org.voltdb.catalog.Site;

public class Placement {
	Collection<Site> allSites;
	Map<Site,Collection<Integer>> siteToPartitions;	
	Map<Integer, Site> partitionToSite;
	
	public Placement(){
		
	}
	
	// hotTuples: tupleId --> access count
	// siteLoads: partitionId --> total access count
	public Plan computePlan(ArrayList<Map<Integer, Integer>> hotTuplesList, Map<Integer, Integer> siteLoads, Plan currentPlan){
		return currentPlan;
	}
}