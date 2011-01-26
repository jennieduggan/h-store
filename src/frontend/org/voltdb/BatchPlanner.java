package org.voltdb;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.collections15.map.ListOrderedMap;
import org.apache.commons.collections15.set.ListOrderedSet;
import org.apache.commons.pool.BasePoolableObjectFactory;
import org.apache.commons.pool.ObjectPool;
import org.apache.commons.pool.impl.StackObjectPool;
import org.apache.log4j.Logger;
import org.voltdb.catalog.*;
import org.voltdb.exceptions.MispredictionException;
import org.voltdb.messaging.*;
import org.voltdb.plannodes.AbstractPlanNode;

import edu.brown.catalog.CatalogUtil;
import edu.brown.catalog.QueryPlanUtil;
import edu.brown.graphs.AbstractDirectedGraph;
import edu.brown.graphs.AbstractEdge;
import edu.brown.graphs.AbstractVertex;
import edu.brown.graphs.IGraph;
import edu.brown.graphs.VertexTreeWalker;
import edu.brown.graphs.VertexTreeWalker.TraverseOrder;
import edu.brown.plannodes.PlanNodeUtil;
import edu.brown.utils.CollectionUtil;
import edu.brown.utils.LoggerUtil;
import edu.brown.utils.PartitionEstimator;
import edu.brown.utils.StringUtil;

/**
 * @author pavlo
 */
public class BatchPlanner {
    private static final Logger LOG = Logger.getLogger(BatchPlanner.class);
    private final static AtomicBoolean debug = new AtomicBoolean(LOG.isDebugEnabled());
    private final static AtomicBoolean trace = new AtomicBoolean(LOG.isTraceEnabled());
    static {
        LoggerUtil.attachObserver(LOG, debug, trace);
    }
    
    // ----------------------------------------------------------------------------
    // DATA MEMBERS
    // ----------------------------------------------------------------------------
    
    protected static final AtomicInteger NEXT_DEPENDENCY_ID = new AtomicInteger(1000);
    
    // Used for turning ParameterSets into ByteBuffers
    protected final FastSerializer fs = new FastSerializer();
    
    // the set of dependency ids for the expected results of the batch
    // one per sql statment
    protected final ArrayList<Integer> depsToResume = new ArrayList<Integer>();

    protected final Catalog catalog;
    protected final Procedure catalog_proc;
    protected final Statement catalog_stmts[];
    protected final SQLStmt[] batchStmts;
    protected final int batchSize;
    protected final PartitionEstimator p_estimator;
    protected final int initiator_id;

    private final List<Set<Integer>> stmt_partitions[];
    private final Map<PlanFragment, Integer> stmt_input_dependencies[];
    private final Map<PlanFragment, Integer> stmt_output_dependencies[];
    private final Set<Integer> all_partitions[];
    
    private final Map<PlanFragment, Set<Integer>> frag_partitions[];

    
    private final int num_partitions;

    
    private static final ObjectPool planFragmentListPool = new StackObjectPool(new BasePoolableObjectFactory() {
        @Override
        public Object makeObject() throws Exception {
            return (new ArrayList<PlanFragment>());
        }
        @Override
        public void activateObject(Object arg0) throws Exception {
            @SuppressWarnings("unchecked")
            List<PlanFragment> list = (List<PlanFragment>)arg0;
            list.clear();
        }
    });

    

    private class PlanVertex extends AbstractVertex {
        final Integer frag_id;
        final Integer input_dependency_id;
        final Integer output_dependency_id;
        final ParameterSet params;
        final Integer partition;
        final int stmt_index;
        final int hash; 

        public PlanVertex(PlanFragment catalog_frag,
                          Integer frag_id,
                          Integer input_dependency_id,
                          Integer output_dependency_id,
                          ParameterSet params,
                          Integer partition,
                          int stmt_index,
                          boolean is_local) {
            super(catalog_frag);
            this.frag_id = frag_id;
            this.input_dependency_id = input_dependency_id;
            this.output_dependency_id = output_dependency_id;
            this.params = params;
            this.partition = partition;
            this.stmt_index = stmt_index;
            
            this.hash = (catalog_frag.hashCode() * 31) +  this.partition.hashCode();
        }
        
        @Override
        public int hashCode() {
            return (this.hash);
        }
        
        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof PlanVertex)) return (false);
            PlanVertex other = (PlanVertex)obj;
            if (this.input_dependency_id != other.input_dependency_id ||
                this.output_dependency_id != other.output_dependency_id ||
                this.params.equals(other.params) != true ||
                this.partition.equals(other.partition) != true ||
                this.stmt_index != other.stmt_index ||
                this.getCatalogItem().equals(other.getCatalogItem()) != true    
            ) return (false);
            return (true);
        }
        
        @Override
        public String toString() {
            return String.format("<%s [Partition#%02d]>", this.getCatalogItem().getName(), this.partition);
        }
    }
    
    private class PlanEdge extends AbstractEdge {
        final Integer dep_id;
        public PlanEdge(IGraph<PlanVertex, PlanEdge> graph, Integer dep_id) {
            super(graph);
            this.dep_id = dep_id;
        }
        
        @Override
        public String toString() {
            return this.dep_id.toString();
        }
    }
    
    private class PlanGraph extends AbstractDirectedGraph<PlanVertex, PlanEdge> {
        private static final long serialVersionUID = 1L;
        
        private final Map<Integer, Set<PlanVertex>> output_dependency_xref = new HashMap<Integer, Set<PlanVertex>>();
        
        public PlanGraph(Database catalog_db) {
            super(catalog_db);
        }
        
        @Override
        public boolean addVertex(PlanVertex v) {
            Integer output_id = v.output_dependency_id;
            assert(output_id != null) : "Unexpected: " + v;
            
            if (!this.output_dependency_xref.containsKey(output_id)) {
                this.output_dependency_xref.put(output_id, new HashSet<PlanVertex>());
            }
            this.output_dependency_xref.get(output_id).add(v);
            return super.addVertex(v);
        }
        
        public Set<PlanVertex> getOutputDependencies(int output_id) {
            return (this.output_dependency_xref.get(output_id));
        }
        
    }
    
    
    
    /**
     * BatchPlan
     */
    public class BatchPlan {
        private final int local_partition;

        private final PlanGraph plan_graph;
        private boolean plan_graph_ready = false;

        private final Set<PlanVertex> local_fragments = new HashSet<PlanVertex>();
        private final Set<PlanVertex> remote_fragments = new HashSet<PlanVertex>();
        
        
        // Whether the fragments of this batch plan consist of read-only operations
        protected boolean readonly = true;
        
        // Whether the batch plan can all be executed locally
        protected boolean all_local = true;
        
        // Whether the fragments in the batch plan can be executed on a single site
        protected boolean all_singlesited = true;    

        // check if all local fragment work is non-transactional
        protected boolean localFragsAreNonTransactional = true;
        
        // Partition -> FragmentIdx
        protected Map<Integer, ListOrderedSet<Integer>> partition_frag_xref = new HashMap<Integer, ListOrderedSet<Integer>>(); 
        
        // Statement Target Partition Ids
        protected final int[][] stmt_partition_ids = new int[BatchPlanner.this.batchSize][];
        protected final int[] stmt_partition_ids_length = new int[BatchPlanner.this.batchSize];
        
        /**
         * Constructor
         */
        public BatchPlan(int local_partition) {
            this.local_partition = local_partition;
            this.plan_graph = new PlanGraph(CatalogUtil.getDatabase(catalog));
        }
        
        /**
         * Construct a map from PartitionId->FragmentTaskMessage
         * Note that a FragmentTaskMessage may contain multiple fragments that need to be executed
         * @param txn_id
         * @return
         */
        public List<FragmentTaskMessage> getFragmentTaskMessages(long txn_id, long clientHandle) {
            assert(this.plan_graph_ready);
            if (debug.get()) LOG.debug("Constructing list of FragmentTaskMessages to execute [txn_id=#" + txn_id + ", local_partition=" + local_partition + "]");
//            try {
//                GraphVisualizationPanel.createFrame(this.plan_graph).setVisible(true);
//                Thread.sleep(100000000);
//            } catch (Exception ex) {
//                ex.printStackTrace();
//                System.exit(1);
//            }
            
            // FIXME Map<Integer, ByteBuffer> buffer_params = new HashMap<Integer, ByteBuffer>(this.allFragIds.size());

            
            // We need to generate a list of FragmentTaskMessages that we will ship off to either
            // remote execution sites or be executed locally. Note that we have to separate
            // any tasks that have a input dependency from those that don't,  because
            // we can only block at the FragmentTaskMessage level (as opposed to blocking by PlanFragment)
            final List<FragmentTaskMessage> ftasks = new ArrayList<FragmentTaskMessage>();
            
            // Round# -> Map<PartitionId, Set<PlanFragments>>
            final TreeMap<Integer, Map<Integer, Set<PlanVertex>>> rounds = new TreeMap<Integer, Map<Integer, Set<PlanVertex>>>();
            assert(!this.plan_graph.getRoots().isEmpty()) : this.plan_graph.getRoots();
            final List<PlanVertex> roots = new ArrayList<PlanVertex>(this.plan_graph.getRoots());
            for (PlanVertex root : roots) {
                new VertexTreeWalker<PlanVertex>(this.plan_graph, TraverseOrder.LONGEST_PATH) {
                    @Override
                    protected void callback(PlanVertex element) {
                        Integer round = null;
                        // If the current element is one of the roots, then we want to put it in a separate
                        // round so that it can be executed without needing all of the other input to come back first
                        // 2010-07-26: NO! For now because we always have to dispatch multi-partition fragments from the coordinator,
                        // then we can't combine the fragments for the local partition together. They always need
                        // to be sent our serially. Yes, I know it's lame but go complain Evan and get off my case!
                        //if (roots.contains(element)) {
                        //    round = -1 - roots.indexOf(element);
                        //} else {
                            round = this.getDepth();
                        //}
                        
                        Integer partition = element.partition;
                        if (!rounds.containsKey(round)) {
                            rounds.put(round, new HashMap<Integer, Set<PlanVertex>>());
                        }
                        if (!rounds.get(round).containsKey(partition)) {
                            rounds.get(round).put(partition, new HashSet<PlanVertex>());
                        }
                        rounds.get(round).get(partition).add(element);
                    }
                }.traverse(root);
            } // FOR
            
            if (trace.get()) LOG.trace("Generated " + rounds.size() + " rounds of tasks for txn #"+ txn_id);
            for (Entry<Integer, Map<Integer, Set<PlanVertex>>> e : rounds.entrySet()) {
                if (trace.get()) LOG.trace("Txn #" + txn_id + " - Round " + e.getKey() + ": " + e.getValue().size() + " partitions");
                for (Integer partition : e.getValue().keySet()) {
                    Set<PlanVertex> vertices = e.getValue().get(partition);
                
                    int num_frags = vertices.size();
                    long frag_ids[] = new long[num_frags];
                    int input_ids[] = new int[num_frags];
                    int output_ids[] = new int[num_frags];
                    int stmt_indexes[] = new int[num_frags];
                    ByteBuffer params[] = new ByteBuffer[num_frags];
            
                    int i = 0;
                    for (PlanVertex v : vertices) {
                        assert(v.partition.equals(partition));
                        
                        // Fragment Id
                        frag_ids[i] = v.frag_id;
                        
                        // Not all fragments will have an input dependency
                        input_ids[i] = (v.input_dependency_id == null ? ExecutionSite.NULL_DEPENDENCY_ID : v.input_dependency_id);
                        
                        // All fragments will produce some output
                        output_ids[i] = v.output_dependency_id;
                        
                        // SQLStmt Index
                        stmt_indexes[i] = v.stmt_index;
                        
                        // Parameters
                        params[i] = null; // FIXME buffer_params.get(v);
                        if (params[i] == null) {
                            try {
                                FastSerializer fs = new FastSerializer();
                                v.params.writeExternal(fs);
                                params[i] = fs.getBuffer();
                            } catch (Exception ex) {
                                LOG.fatal("Failed to serialize parameters for FragmentId #" + frag_ids[i], ex);
                                System.exit(1);
                            }
                            if (trace.get()) LOG.trace("Stored ByteBuffer for " + v);
                            // FIXME buffer_params.put(frag_idx, params[i]);
                        }
                        assert(params[i] != null) : "Parameter ByteBuffer is null for partition #" + v.partition + " fragment index #" + i + "\n"; //  + buffer_params;
                        
                        if (trace.get())
                            LOG.trace("Fragment Grouping " + i + " => [" +
                                       "txn_id=#" + txn_id + ", " +
                                       "frag_id=" + frag_ids[i] + ", " +
                                       "input=" + input_ids[i] + ", " +
                                       "output=" + output_ids[i] + ", " +
                                       "stmt_indexes=" + stmt_indexes[i] + "]");
                        
                        i += 1;
                    } // FOR (frag_idx)
                
                    if (i == 0) {
                        if (trace.get()) {
                            LOG.warn("For some reason we thought it would be a good idea to construct a FragmentTaskMessage with no fragments! [txn_id=#" + txn_id + "]");
                            LOG.warn("In case you were wondering, this is a terrible idea, which is why we didn't do it!");
                        }
                        continue;
                    }
                
                    FragmentTaskMessage task = new FragmentTaskMessage(
                            BatchPlanner.this.initiator_id,
                            partition,
                            txn_id,
                            clientHandle,
                            false, // IGNORE
                            frag_ids,
                            input_ids,
                            output_ids,
                            params,
                            stmt_indexes,
                            false); // FIXME(pavlo) Final task?
                    task.setFragmentTaskType(BatchPlanner.this.catalog_proc.getSystemproc() ? FragmentTaskMessage.SYS_PROC_PER_PARTITION : FragmentTaskMessage.USER_PROC);
                    if (debug.get()) LOG.debug("New FragmentTaskMessage to run at partition #" + partition + " with " + num_frags + " fragments for txn #" + txn_id + " " + 
                                         "[ids=" + Arrays.toString(frag_ids) + ", inputs=" + Arrays.toString(input_ids) + ", outputs=" + Arrays.toString(output_ids) + "]");
                    if (trace.get()) LOG.trace("Fragment Contents: [txn_id=#" + txn_id + "]\n" + task.toString());
                    ftasks.add(task);
                    
                } // PARTITION
            } // ROUND            
            assert(ftasks.size() > 0) : "Failed to generate any FragmentTaskMessages in this BatchPlan for txn #" + txn_id;
            if (debug.get()) LOG.debug("Created " + ftasks.size() + " FragmentTaskMessage(s) for txn #" + txn_id);
            return (ftasks);
        }
        
        protected void buildPlanGraph() {
            assert(this.plan_graph.getVertexCount() > 0);
            
            for (PlanVertex v0 : this.plan_graph.getVertices()) {
                Integer input_id = v0.input_dependency_id;
                if (input_id == null) continue;
                for (PlanVertex v1 : this.plan_graph.getOutputDependencies(input_id)) {
                    assert(!v0.equals(v1)) : v0;
                    if (!this.plan_graph.findEdgeSet(v0, v1).isEmpty()) continue;
                    PlanEdge e = new PlanEdge(this.plan_graph, input_id);
                    this.plan_graph.addEdge(e, v0, v1);
                } // FOR
            } // FOR
            this.plan_graph_ready = true;
        }
        
        /**
         * 
         * @return
         */
        public PlanGraph getPlanGraph() {
            return plan_graph;
        }
        
        /**
         * Adds a new FragmentId that needs to be executed on some number of partitions
         * @param frag_id
         * @param output_dependency_id
         * @param params
         * @param partitions
         * @param is_local
         */
        public void addFragment(PlanFragment catalog_frag,
                                Integer input_dependency_id,
                                Integer output_dependency_id,
                                ParameterSet params,
                                Set<Integer> partitions,
                                int stmt_index,
                                boolean is_local) {
            this.plan_graph_ready = false;
            int frag_id = Integer.parseInt(catalog_frag.getName());
            if (trace.get())
                LOG.trace("New Fragment: [" +
                            "frag_id=" + frag_id + ", " +
                            "output_dep_id=" + output_dependency_id + ", " +
                            "input_dep_id=" + input_dependency_id + ", " +
                            "params=" + params + ", " +
                            "partitons=" + partitions + ", " +
                            "stmt_index=" + stmt_index + ", " +
                            "is_local=" + is_local + "]");
                           
            for (Integer partition : partitions) {
                PlanVertex v = new PlanVertex(catalog_frag,
                                              frag_id,
                                              input_dependency_id,
                                              output_dependency_id,
                                              params,
                                              partition,
                                              stmt_index,
                                              is_local);
                this.plan_graph.addVertex(v);
                if (is_local) {
                    this.local_fragments.add(v);
                } else {
                    this.remote_fragments.add(v);
                }
            }
        }
        
        public int getBatchSize() {
            return (BatchPlanner.this.batchSize);
        }
        
        public Statement[] getStatements() {
            return (BatchPlanner.this.catalog_stmts);
        }
        
        public int[][] getStatementPartitions() {
            return (this.stmt_partition_ids);
        }
        
        /**
         * 
         * @return
         */
        public int getRemoteFragmentCount() {
            return (this.remote_fragments.size());
        }
        
        /**
         * 
         * @return
         */
        public int getLocalFragmentCount() {
            return (this.local_fragments.size());
        }
        
        public boolean isReadOnly() {
            return (this.readonly);
        }
        
        public boolean isLocal() {
            return (this.all_local);
        }
        
        public boolean isSingleSited() {
            return (this.all_singlesited);
        }
        
        @Override
        public String toString() {
            StringBuilder b = new StringBuilder();
            b.append("Read Only:        ").append(this.readonly).append("\n")
             .append("All Local:        ").append(this.all_local).append("\n")
             .append("All Single-Sited: ").append(this.all_singlesited).append("\n")
             .append("# of Fragments:   ").append(this.plan_graph.getVertexCount()).append("\n")
             .append("------------------------------\n");

            /*
            for (int i = 0, cnt = this.allFragIds.size(); i < cnt; i++) {
                int frag_id = this.allFragIds.get(i);
                Set<Integer> partitions = this.allFragPartitions.get(i);
                ParameterSet params = this.allParams.get(i);
                b.append("  [" + i + "] ")
                  .append("FragId=" + frag_id + ", ")
                  .append("Partitions=" + partitions + ", ")
                  .append("Params=[" + params + "]\n");
            } // FOR
            */
            return (b.toString());
        }
    } // END CLASS
    

    
    /**
     * Constructor
     */
    protected BatchPlanner(SQLStmt[] batchStmts, Procedure catalog_proc, PartitionEstimator p_estimator, int initiator_id) {
        this(batchStmts, batchStmts.length, catalog_proc, p_estimator, initiator_id);
        
    }

    
    /**
     * Constructor
     * @param batchStmts
     * @param batchSize
     * @param catalog_proc
     * @param p_estimator
     * @param local_partition
     */
    public BatchPlanner(SQLStmt[] batchStmts, int batchSize, Procedure catalog_proc, PartitionEstimator p_estimator, int initiator_id) {
        assert(catalog_proc != null);
        assert(p_estimator != null);

        this.batchStmts = batchStmts;
        this.batchSize = batchSize;
        this.catalog_proc = catalog_proc;
        this.catalog = catalog_proc.getCatalog();
        this.p_estimator = p_estimator;
        this.initiator_id = initiator_id;
        this.num_partitions = CatalogUtil.getNumberOfPartitions(catalog_proc);
        
        this.catalog_stmts = new Statement[this.batchSize];
        
        this.stmt_partitions = (List<Set<Integer>>[])new List<?>[this.batchSize];
        this.stmt_input_dependencies = (Map<PlanFragment, Integer>[])new Map<?, ?>[this.batchSize];
        this.stmt_output_dependencies = (Map<PlanFragment, Integer>[])new Map<?, ?>[this.batchSize];
        this.all_partitions = (Set<Integer>[])new Set<?>[this.batchSize];
        this.frag_partitions = (Map<PlanFragment, Set<Integer>>[])new HashMap<?, ?>[this.batchSize];
        
        for (int i = 0; i < this.batchSize; i++) {
            this.catalog_stmts[i] = this.batchStmts[i].catStmt;
            assert(this.catalog_stmts[i] != null);

            this.stmt_partitions[i] = new ArrayList<Set<Integer>>();
            this.stmt_input_dependencies[i] = new HashMap<PlanFragment, Integer>();
            this.stmt_output_dependencies[i] = new HashMap<PlanFragment, Integer>();
            this.all_partitions[i] = new HashSet<Integer>();
            this.frag_partitions[i] = new HashMap<PlanFragment, Set<Integer>>();
        } // FOR
    }
   
    /**
     * 
     * @param batchArgs
     * @param predict_singlepartitioned TODO
     */
    public BatchPlan plan(ParameterSet[] batchArgs, int base_partition, boolean predict_singlepartitioned) {
        if (debug.get()) LOG.debug("Constructing a new BatchPlan for " + this.catalog_proc);
        BatchPlan plan = new BatchPlan(base_partition);

        // ----------------------
        // DEBUG DUMP
        // ----------------------
        if (trace.get()) {
            Map<String, Object> m = new ListOrderedMap<String, Object>();
            m.put("Batch Size", this.batchSize);
            for (int i = 0; i < this.batchSize; i++) {
                m.put(String.format("[%02d] %s", i, this.batchStmts[i].catStmt.getName()), Arrays.toString(batchArgs[i].toArray()));
            }
            LOG.trace("\n" + StringUtil.formatMapsBoxed(m));
        }
       
        List<PlanFragment> frag_list = null;
        try {
            frag_list = (List<PlanFragment>)BatchPlanner.planFragmentListPool.borrowObject();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        
        
        for (int stmt_index = 0; stmt_index < this.batchSize; ++stmt_index) {
            final SQLStmt stmt = this.batchStmts[stmt_index];
            assert(stmt != null) : "The SQLStmt object at index " + stmt_index + " is null for " + this.catalog_proc;
            final Statement catalog_stmt = stmt.catStmt;
            final ParameterSet paramSet = batchArgs[stmt_index];
            final Object params[] = paramSet.toArray();
            if (trace.get()) LOG.trace(String.format("[%02d] Constructing fragment plans for %s", stmt_index, stmt.catStmt.fullName()));
            
            this.stmt_partitions[stmt_index].clear();
            for (Set<Integer> p : this.frag_partitions[stmt_index].values()) {
                p.clear();
            }

            boolean has_singlepartition_plan = catalog_stmt.getHas_singlesited();
            boolean stmt_localFragsAreNonTransactional = plan.localFragsAreNonTransactional;
            boolean mispredict = false;
            boolean is_singlepartition = has_singlepartition_plan;
            CatalogMap<PlanFragment> fragments = null;
            
            try {
                // Optimization: If we were told that the transaction is suppose to be single-partitioned, then we will
                // throw the single-partitioned PlanFragments at the PartitionEstimator to get back what partitions
                // each PlanFragment will need to go to. If we get multiple partitions, then we know that we mispredicted and
                // we should throw a MispredictionException
                // If we originally didn't predict that it was single-partitioned, then we actually still need to check
                // whether the query should be single-partitioned or not. This is because a query may actually just want
                // to execute on just one partition (note that it could be a local partition or the remote partition).
                // We'll assume that it's single-partition <<--- Can we cache that??
                while (true) {
                    this.all_partitions[stmt_index].clear();
                    fragments = (is_singlepartition ? catalog_stmt.getFragments() : catalog_stmt.getMs_fragments());
                    this.p_estimator.getAllFragmentPartitions(this.frag_partitions[stmt_index],
                                                              this.all_partitions[stmt_index],
                                                              fragments, params, base_partition);

                    if (is_singlepartition && this.all_partitions[stmt_index].size() > 1) {
                        // If this was suppose to be multi-partitioned, then we want to stop right here!!
                        if (predict_singlepartitioned) {
                            mispredict = true;
                            break;
                        }
                        // Otherwise we can let it wrap back around and construct the fragment mapping for the
                        // multi-partition PlanFragments
                        is_singlepartition = false;
                        continue;
                    }
                    // Score! We have a plan that works!
                    break;
                } // WHILE
            } catch (Exception ex) {
                LOG.fatal("Unexpected error when planning " + catalog_stmt.fullName(), ex);
                throw new RuntimeException(ex);
            }
            
            // Misprediction!!
            if (mispredict) {
                throw new MispredictionException(123l); // FIXME
            }

            frag_list.clear();
            frag_list = QueryPlanUtil.getSortedPlanFragments((List<PlanFragment>)CollectionUtil.addAll(frag_list, fragments));
            
            boolean is_local = (this.all_partitions[stmt_index].size() == 1 && this.all_partitions[stmt_index].contains(base_partition));
            plan.readonly = plan.readonly && catalog_stmt.getReadonly();
            plan.localFragsAreNonTransactional = plan.localFragsAreNonTransactional || stmt_localFragsAreNonTransactional;
            plan.all_singlesited = plan.all_singlesited && is_singlepartition;
            plan.all_local = plan.all_local && is_local;

            // Update the Statement->PartitionId array
            // This is needed by TransactionEstimator
            plan.stmt_partition_ids[stmt_index] = new int[this.all_partitions[stmt_index].size()];
            int idx = 0;
            for (int partition_id : this.all_partitions[stmt_index]) {
                plan.stmt_partition_ids[stmt_index][idx++] = partition_id;
            } // FOR
            
            // Generate the synthetic DependencyIds for the query
            Integer last_output_id = null;
            for (int i = 0, cnt = frag_list.size(); i < cnt; i++) {
                PlanFragment catalog_frag = frag_list.get(i);
                Set<Integer> f_partitions = this.frag_partitions[stmt_index].get(catalog_frag);
                boolean f_local = (f_partitions.size() == 1 && f_partitions.contains(base_partition));
                Integer output_id = BatchPlanner.NEXT_DEPENDENCY_ID.getAndIncrement();

                plan.addFragment(catalog_frag, 
                                 last_output_id,
                                 output_id,
                                 paramSet,
                                 f_partitions,
                                 stmt_index,
                                 f_local);
                
                last_output_id = output_id;
            } // FOR
            
            
            // ----------------------
            // DEBUG DUMP
            // ----------------------
            if (debug.get()) {
                int ii = 0;
                Map<String, Object> m = new ListOrderedMap<String, Object>();
                for (PlanFragment catalog_frag : fragments) {
                    Set<Integer> p = this.frag_partitions[stmt_index].get(catalog_frag);
                    boolean frag_local = (p.size() == 1 && p.contains(base_partition));
                    m.put(String.format("[%02d] Fragment", ii), catalog_frag.fullName());
                    m.put(String.format("     Partitions"), p);
                    m.put(String.format("     IsLocal"), frag_local);
                    m.put(String.format("     Inputs"), this.stmt_input_dependencies[stmt_index].get(ii));
                    m.put(String.format("     Outputs"), this.stmt_output_dependencies[stmt_index].get(ii));

                    ii++;
                } // FOR

                Map<String, Object> header = new ListOrderedMap<String, Object>();
                header.put("Batch Statement#", String.format("%02d / %02d", stmt_index, this.batchSize));
                header.put("Catalog Statement", stmt.catStmt.fullName());
                header.put("Statement SQL", stmt.getText());
                header.put("Initiator Id", this.initiator_id); 
                header.put("All Partitions", this.all_partitions[stmt_index]);
                header.put("Local Partition", base_partition);
                header.put("IsSingledSited", is_singlepartition);
                header.put("IsStmtLocal", is_local);
                header.put("IsBatchLocal", plan.all_local);
                header.put("Fragments", fragments.size());

                LOG.debug("\n" + StringUtil.formatMapsBoxed(header, m));
            }
            

        } // FOR (SQLStmt)
        
        plan.buildPlanGraph();
        
        if (debug.get()) LOG.debug("Created BatchPlan:\n" + plan.toString());
        return (plan);
    }

    /**
     * List of DependencyIds that need to be satisfied before we return control
     * back to the Java control code
     * @return
     */
    public List<Integer> getDependencyIdsNeededToResume() {
        return (this.depsToResume);
    }

}
