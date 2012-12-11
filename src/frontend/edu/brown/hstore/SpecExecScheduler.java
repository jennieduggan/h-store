package edu.brown.hstore;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.log4j.Logger;
import org.voltdb.catalog.Procedure;
import org.voltdb.types.SpeculationType;

import edu.brown.hstore.conf.HStoreConf;
import edu.brown.hstore.estimators.EstimatorState;
import edu.brown.hstore.specexec.AbstractConflictChecker;
import edu.brown.hstore.txns.AbstractTransaction;
import edu.brown.hstore.txns.LocalTransaction;
import edu.brown.interfaces.Loggable;
import edu.brown.logging.LoggerUtil;
import edu.brown.logging.LoggerUtil.LoggerBoolean;
import edu.brown.profilers.SpecExecProfiler;

/**
 * Special scheduler that can figure out what the next best single-partition
 * to speculatively execute at a partition based on the current distributed transaction 
 * @author pavlo
 */
public class SpecExecScheduler implements Loggable {
    private static final Logger LOG = Logger.getLogger(SpecExecScheduler.class);
    private static final LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private static final LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());
    private static boolean d;
    private static boolean t;
    static {
        LoggerUtil.attachObserver(LOG, debug, trace);
        d = debug.get();
        t = trace.get();
    }
    
    private final int partitionId;
    private final TransactionInitPriorityQueue work_queue;
    private AbstractConflictChecker checker;
    private boolean ignore_all_local = false;
    private final Map<SpeculationType, SpecExecProfiler> profilerMap = new HashMap<SpeculationType, SpecExecProfiler>();
    private SchedulerPolicy policy;
    private int window_size = 1;
    private boolean isProfiling = false;
    
    private AbstractTransaction lastDtxn;
    private SpeculationType lastSpecType;
    private Iterator<AbstractTransaction> lastIterator;
    
    public enum SchedulerPolicy {
          FIRST, // DEFAULT
          SHORTEST,
          LONGEST;
          
          private static final Map<String, SchedulerPolicy> name_lookup = new HashMap<String, SchedulerPolicy>();
          static {
              for (SchedulerPolicy e : EnumSet.allOf(SchedulerPolicy.class)) {
                  SchedulerPolicy.name_lookup.put(e.name().toLowerCase(), e);
              } // FOR
          } // STATIC
          
          public static SchedulerPolicy get(String name) {
              return SchedulerPolicy.name_lookup.get(name.toLowerCase());
          }
    } // ENUM
    
    /**
     * Constructor
     * @param catalogContext
     * @param checker TODO
     * @param partitionId
     * @param work_queue
     */
    public SpecExecScheduler(AbstractConflictChecker checker,
                             int partitionId, TransactionInitPriorityQueue work_queue,
                             SchedulerPolicy schedule_policy, int window_size) {
        assert(schedule_policy != null) : "Unsupported schedule policy parameter passed in";
        
        this.partitionId = partitionId;
        this.work_queue = work_queue;
        this.checker = checker;
        this.policy = schedule_policy;
        this.window_size = window_size;
        
        if (HStoreConf.singleton().site.specexec_profiling) {
            this.isProfiling = true;
            for (SpeculationType type: SpeculationType.getNameMap().values()) {
                this.profilerMap.put(type, new SpecExecProfiler());
            }
        } else {
            this.isProfiling = false; // do not need, but for explicit reason
        }
    }
    
    public void setIgnoreAllLocal(boolean ignore_all_local) {
        this.ignore_all_local = ignore_all_local;
    }
    
    protected void setWindowSize(int window) {
        this.window_size = window;
    }
    protected void setPolicy(SchedulerPolicy policy) {
        this.policy = policy;
    }
    
    protected void reset() {
        this.lastIterator = null;
    }

    public boolean shouldIgnoreProcedure(Procedure catalog_proc) {
        return (this.checker.shouldIgnoreProcedure(catalog_proc));
    }
    
    /**
     * Find the next non-conflicting txn that we can speculatively execute.
     * Note that if we find one, it will be immediately removed from the queue
     * and returned. If you do this and then find out for some reason that you
     * can't execute the StartTxnMessage that is returned, you must be sure
     * to requeue it back.
     * @param dtxn The current distributed txn at this partition.
     * @return
     */
    public LocalTransaction next(AbstractTransaction dtxn, SpeculationType specType) {
        assert(dtxn != null) : "Null distributed transaction"; 
        assert(this.checker.shouldIgnoreProcedure(dtxn.getProcedure()) == false) :
            String.format("Trying to check for speculative txns for %s but the txn should have been ignored");
        
        SpecExecProfiler profiler = null;
        if (this.isProfiling) {
            profiler = profilerMap.get(specType);
            profiler.total_time.start();
        }
        
        if (d) {
            LOG.debug(String.format("%s - Checking queue for transaction to speculatively execute " +
        		      "[specType=%s, queueSize=%d, policy=%s]",
                      dtxn, specType, this.work_queue.size(), this.policy));
            if (t) LOG.trace(String.format("%s - Last Invocation [lastDtxn=%s, lastSpecType=%s, lastIterator=%s]",
                             dtxn, this.lastDtxn, this.lastSpecType, this.lastIterator));
        }
        
        // If this is a LocalTransaction and all of the remote partitions that it needs are
        // on the same site, then we won't bother with trying to pick something out
        // because there is going to be very small wait times.
        if (this.ignore_all_local && dtxn instanceof LocalTransaction && ((LocalTransaction)dtxn).isPredictAllLocal()) {
            if (d) LOG.debug(String.format("%s - Ignoring current distributed txn because all of the partitions that " +
                             "it is using are on the same HStoreSite [%s]", dtxn, dtxn.getProcedure()));
            if (this.isProfiling) profiler.total_time.stop();
            return (null);
        }
        
        // Now peek in the queue looking for single-partition txns that do not
        // conflict with the current dtxn
        LocalTransaction next = null;
        int txn_ctr = 0;
        int examined_ctr = 0;
        long best_time = (this.policy == SchedulerPolicy.LONGEST ? Long.MIN_VALUE : Long.MAX_VALUE);

        // Check whether we can use our same iterator from the last call
        if (this.policy != SchedulerPolicy.FIRST ||
                this.lastDtxn != dtxn ||
                this.lastSpecType != specType ||
                this.lastIterator == null) {
            this.lastIterator = this.work_queue.iterator();    
        }
        if (this.isProfiling) profiler.queue_size.put(this.work_queue.size());
        while (this.lastIterator.hasNext()) {
            AbstractTransaction txn = this.lastIterator.next();
            assert(txn != null) : "Null transaction handle " + txn;
            boolean singlePartition = txn.isPredictSinglePartition();
            txn_ctr++;

            // Skip any distributed or non-local transactions
            if ((txn instanceof LocalTransaction) == false || singlePartition == false) {
                if (t) LOG.trace(String.format("%s - Skipping non-speculative candidate %s", dtxn, txn));
                continue;
            }
            LocalTransaction localTxn = (LocalTransaction)txn;
            
            // Skip anything already speculatively executed
            if (localTxn.isSpeculative()) {
                if (t) LOG.trace(String.format("%s - Skipping %s because it was already executed", dtxn, txn));
                continue;
            }

            // Let's check it out!
            if (this.isProfiling) profiler.compute_time.start();
            if (d) LOG.debug(String.format("Examining whether %s conflicts with current dtxn %s", localTxn, dtxn));
            if (singlePartition == false) {
                if (t) LOG.trace(String.format("%s - Skipping %s because it is not single-partitioned", dtxn, localTxn));
                continue;
            }
            try {
                if (this.checker.canExecute(dtxn, localTxn, this.partitionId)) {
                    if (next == null) {
                        next = localTxn;
                        // Scheduling Policy: FIRST MATCH
                        if (this.policy == SchedulerPolicy.FIRST) {
                            break;
                        }
                    }
                    // Scheduling Policy: Estimated Time Remaining
                    else {
                        EstimatorState es = localTxn.getEstimatorState();
                        if (es != null) {
                            long remaining = es.getLastEstimate().getRemainingExecutionTime();
                            if ((this.policy == SchedulerPolicy.SHORTEST && remaining < best_time) ||
                                (this.policy == SchedulerPolicy.LONGEST && remaining > best_time)) {
                                best_time = remaining;
                                next = localTxn;
                                if (d) LOG.debug(String.format("[%s schedule %d] New Match -> %s / remaining=%d",
                                                 this.policy, this.window_size, next, remaining));
                             }
                        }
                    }
                    // Stop if we've reached our window size
                    if (++examined_ctr == this.window_size) break;
                }
            } finally {
                if (this.isProfiling) profiler.compute_time.stop();
            }
        } // WHILE
        if (this.isProfiling) {
            profiler.num_comparisons.put(txn_ctr);
        }
        
        // We found somebody to execute right now!
        // Make sure that we set the speculative flag to true!
        if (next != null) {
            if (this.isProfiling) profiler.success++;
            this.lastIterator.remove();
            if (d) LOG.debug(dtxn + " - Found next non-conflicting speculative txn " + next);
        }
        
        this.lastDtxn = dtxn;
        this.lastSpecType = specType;
        if (this.isProfiling) profiler.total_time.stop();
        return (next);
    }
    
    /**
     * Replace the ConflictChecker. This should only be used for testing
     * @param checker
     */
    protected void setConflictChecker(AbstractConflictChecker checker) {
        LOG.warn(String.format("Replacing original checker %s with %s",
                 this.checker.getClass().getSimpleName(),
                 checker.getClass().getCanonicalName()));
        this.checker = checker;
    }
    
    public Map<SpeculationType,SpecExecProfiler> getProfilers() {
        //return (this.profiler);
        return (this.profilerMap);
    }
    
    public SpecExecProfiler getProfiler(SpeculationType stype) {
        return (this.profilerMap.get(stype));
    }
    
    @Override
    public void updateLogging() {
        d = debug.get();
        t = trace.get();
    }
}
