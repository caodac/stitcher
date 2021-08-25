package ncats.stitcher;

import java.util.*;
import java.io.*;
import java.net.URL;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.lang.reflect.Array;
import java.util.stream.Stream;
import java.util.stream.Collectors;
import java.util.function.BiPredicate;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.concurrent.Callable;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.DynamicRelationshipType;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Resource;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.index.Index;
import org.neo4j.graphdb.index.IndexHits;
import org.neo4j.graphdb.NotFoundException;
import org.neo4j.graphdb.index.RelationshipIndex;
import org.neo4j.index.lucene.LuceneTimeline;
import org.neo4j.index.lucene.TimelineIndex;
import org.neo4j.graphdb.traversal.TraversalDescription;
import org.neo4j.graphdb.traversal.Traverser;

import ncats.stitcher.graph.UnionFind;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

// NOTE: methods and variables that begin with underscore "_" generally assume that a graph database transaction is already open!

public class EntityFactory implements Props, AutoCloseable {
    static final Logger logger = Logger.getLogger
        (EntityFactory.class.getName());

    static final double CLIQUE_WEIGHT = 0.7;
    static final int CLIQUE_MINSIZE = 2;
    static final String TEXT_INDEXER = "lucene";

    static class DefaultGraphMetrics implements GraphMetrics {
        int entityCount;
        Map<EntityType, Integer> entityHistogram =
            new EnumMap<EntityType, Integer>(EntityType.class);
        Map<Integer, Integer> entitySizeDistribution =
            new TreeMap<Integer, Integer>();
        int stitchCount;
        Map<String, Integer> stitchHistogram =
            new TreeMap<String, Integer>();
        int connectedComponentCount;
        Map<Integer, Integer> connectedComponentHistogram =
            new TreeMap<Integer, Integer>();
        int singletonCount;

        DefaultGraphMetrics () {}

        public int getEntityCount () { return entityCount; }
        public Map<EntityType, Integer> getEntityHistogram () {
            return entityHistogram;
        }
        public Map<Integer, Integer> getEntitySizeDistribution () {
            return entitySizeDistribution;
        }
        public int getStitchCount () { return stitchCount; }
        public Map<String, Integer> getStitchHistogram () {
            return stitchHistogram;
        }
        public int getConnectedComponentCount () {
            return connectedComponentCount;
        }
        public Map<Integer, Integer> getConnectedComponentHistogram () {
            return connectedComponentHistogram;
        }
        public int getSingletonCount () { return singletonCount; }
    }

    static class EntityIterator implements Iterator<Entity> {
        final ResourceIterator<Node> iter;
        final GraphDatabaseService gdb;

        EntityIterator (GraphDatabaseService gdb, ResourceIterator<Node> iter) {
            this.iter = iter;
            this.gdb = gdb;
        }

        public boolean hasNext () {
            try (Transaction tx = gdb.beginTx()) {
                boolean next = iter.hasNext();
                tx.success();
                return next;
            }
        }
        
        public Entity next () {
            try (Transaction tx = gdb.beginTx()) {
                Node n = iter.next();
                tx.success();
                return Entity.getEntity(n);
            }
        }
        
        public void remove () {
            throw new UnsupportedOperationException ("remove not supported");
        }
    }

    static class EquivalenceClass {
        final List<Long> singletons = new ArrayList<>();
        final UnionFind eqv = new UnionFind ();
        final Predication predication;
        final Map<Integer, Integer> hist = new TreeMap<>();
        final int minscore;

        EquivalenceClass (Predication predication) {
            this (1, predication);
        }
        
        EquivalenceClass (int minscore, Predication predication) {
            if (predication == null)
                predication = (s, sv, t) -> 1;
            this.minscore = minscore;
            this.predication = predication;
        }

        public void add (Node node) {
            Map<Node, Map<StitchKey, Object>> sv = stitchValues (node);
            if (sv.isEmpty()) {
                singletons.add(node.getId());
            }
            else {
                Entity n = Entity._getEntity(node);
                List<Node> bestNodes = new ArrayList<>();
                List<Node> eqvnodes = new ArrayList<>();
                int bestScore = 0;
                for (Map.Entry<Node, Map<StitchKey, Object>>
                         me : sv.entrySet()) {
                    Map<StitchKey, Object> stitches = me.getValue();
                    Entity m = Entity._getEntity(me.getKey());
                    
                    // we should properly make sure directionality
                    // is correct here
                    int score = predication.score(n, stitches, m);
                    Integer c = hist.get(score);
                    hist.put(score, c==null ? 1:c+1);

                    if ((stitches.containsKey(StitchKey.R_equivalentClass)
                         || stitches.containsKey(StitchKey.R_exactMatch))
                        && score > 0) {
                        eqvnodes.add(me.getKey());
                    }
                    else if (score < minscore
                             || stitches.containsKey(StitchKey.R_subClassOf)) {
                    }
                    else if (score > bestScore) {
                        bestScore = score;
                        bestNodes.clear();
                        bestNodes.add(me.getKey());
                    }
                    else if (score == bestScore) {
                        bestNodes.add(me.getKey());
                    }
                }

                if (!eqvnodes.isEmpty()) {
                    for (Node eqn : eqvnodes)
                        eqv.union(node.getId(), eqn.getId());
                }
                else if (!bestNodes.isEmpty()) {
                    for (Node bn : bestNodes)
                        eqv.union(node.getId(), bn.getId());
                }
                else {
                    singletons.add(node.getId());
                }
            }            
        } // add ()

        Map<Node, Map<StitchKey, Object>> stitchValues (Node node) {
            Map<Node, Map<StitchKey, List>> edges = new HashMap<>();
            for (Relationship rel : node.getRelationships(Entity.KEYS)) {
                StitchKey type =  StitchKey.valueOf(rel.getType().name());
                Node start = rel.getStartNode();
                Node end = rel.getEndNode();
                
                Node n = start.equals(node) ? end : start;
                Map<StitchKey, List> values = edges.get(n);
                if (values == null) {
                    values = new EnumMap (StitchKey.class);
                    edges.put(n, values);
                }
                
                List lv = values.get(type);
                if (lv == null) {
                    values.put(type, lv = new ArrayList ());
                }
                Object v = rel.getProperty(VALUE);
                if (v != null) lv.add(v);
            }

            Map<Node, Map<StitchKey, Object>> sv = new HashMap<>();
            for (Map.Entry<Node, Map<StitchKey, List>> me : edges.entrySet()) {
                Map<StitchKey, Object> values = new TreeMap<>();
                for (Map.Entry<StitchKey, List> le: me.getValue().entrySet()) {
                    List lv = le.getValue();
                    if (!lv.isEmpty()) {
                        if (lv.size() == 1)
                            values.put(le.getKey(), lv.get(0));
                        else
                            values.put(le.getKey(), lv.toArray(new Object[0]));
                    }
                }
                
                if (!values.isEmpty())
                    sv.put(me.getKey(), values);
            }
            return sv;
        }
        
        public long[] singletons () { return Util.toArray(singletons); }
        public long[][] components () { return eqv.components(); }

        public void dumpScoreHist () {
            try (PrintStream ps = new PrintStream
                 (new FileOutputStream ("strong_cc.hist"))) {
                for (Map.Entry<Integer, Integer> me : hist.entrySet()) {
                    ps.println(me.getKey()+" "+me.getValue());
                }
            }
            catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    } // EquivalenceClass
    
    static class StronglyConnectedComponents implements Iterator<Component> {
        int current;
        long[][] components;
        long[] singletons;
        final GraphDatabaseService gdb;

        StronglyConnectedComponents (final GraphDatabaseService gdb,
                                     final Predication predication) {
            this (gdb, predication, 1);
        }
        
        StronglyConnectedComponents (final GraphDatabaseService gdb,
                                     final Predication predication,
                                     final int minscore) {
            EquivalenceClass eqv = new EquivalenceClass (minscore, predication);
            try (Transaction tx = gdb.beginTx()) {
                gdb.findNodes(AuxNodeType.ENTITY).stream().forEach(node -> {
                        eqv.add(node);
                    });
                
                tx.success();
            }
            eqv.dumpScoreHist();
            
            this.singletons = eqv.singletons();
            this.components = eqv.components();
            this.gdb = gdb;
        }

        public long[][] components () {
            return components;
        }
        
        public long[] singletons () {
            return singletons;
        }

        public boolean hasNext () {
            boolean next = current < components.length;
            if (!next) {
                next = (current - components.length) < singletons.length;
            }
            return next;
        }

        public Component next () {
            long[] comp;
            if (current < components.length) {
                comp = components[current];
            }
            else {
                comp = new long[1];
                comp[0] = singletons[current-components.length];
            }
            ++current;
            return new ComponentImpl (gdb, comp);
        }
        
        public void remove () {
            throw new UnsupportedOperationException ("remove not supported");
        }
    }

    /*
     * a simple graph wrapper around a set of nodes
     */
    static class Graph {
        final BitSet[] adj;
        StitchKey key;
        GraphDatabaseService gdb;
        Node[] nodes;

        Graph (Component comp) {
            Entity[] entities = comp.entities();
            adj = new BitSet[entities.length];
            if (entities.length > 0) {
                gdb = entities[0].getGraphDb();
                try (Transaction tx = gdb.beginTx()) {
                    for (int i = 0; i < entities.length; ++i) {
                        Node n = entities[i]._node();
                        BitSet bs = new BitSet (entities.length);
                        for (Relationship rel :
                                 n.getRelationships(key, Direction.BOTH)) {
                            Node m = rel.getOtherNode(n);
                            long id = m.getId();
                            for (int j = 0; j < entities.length; ++j) 
                                if (i != j && entities[j].getId() == id) {
                                    bs.set(j);
                                    break;
                                }
                        }
                        adj[i] = bs;
                        nodes[i] = n;
                    }
                    tx.success();
                }
            }
        }
        
        Graph (GraphDatabaseService gdb, StitchKey key, long[] nodes) {
            adj = new BitSet[nodes.length];
            try (Transaction tx = gdb.beginTx()) {
                this.nodes = new Node[nodes.length];
                for (int i = 0; i < nodes.length; ++i) {
                    Node n = gdb.getNodeById(nodes[i]);
                    BitSet bs = new BitSet (nodes.length);
                    for (Relationship rel :
                             n.getRelationships(key, Direction.BOTH)) {
                        Node m = rel.getOtherNode(n);
                        long id = m.getId();
                        for (int j = 0; j < nodes.length; ++j) 
                            if (i != j && nodes[j] == id) {
                                bs.set(j);
                                break;
                            }
                    }
                    adj[i] = bs;
                    this.nodes[i] = n;
                }
                tx.success();
            }
            this.gdb = gdb;
            this.key = key;
        }

        public BitSet maxclique (StitchKey key, Object value) {
            return null;
        }

        // construct a clique based on the given set of nodes
        
        public BitSet edges (int n) { return adj[n]; }
        public StitchKey key () { return key; }
        public int size () { return adj.length; }
    }

    static class ComponentImpl implements Component {
        TreeSet<Long> nodes = new TreeSet<>();
        Entity[] entities;
        String id;
        GraphDatabaseService gdb;
        Entity root;

        ComponentImpl () {
        }
        
        ComponentImpl (Node node) {
            instrument (node);
        }

        void instrument (Node node) {
            gdb = node.getGraphDatabase();
            try (Transaction tx = gdb.beginTx()) {
                if (!node.hasLabel(AuxNodeType.COMPONENT)
                    || !node.hasProperty(RANK))
                    throw new IllegalArgumentException
                        ("Not a valid component node: "+node.getId());
                traverse (node);

                Integer rank = (Integer)node.getProperty(RANK);
                if (rank != nodes.size()) {
                    logger.warning("Node #"+node.getId()
                                   +": Rank is "+rank+" but there are "
                                   +nodes.size()+" nodes in this component!");
                }
                
                entities = new Entity[nodes.size()];
                int i = 0;
                for (Long id : nodes) {
                    entities[i] = Entity._getEntity(gdb.getNodeById(id));
                    if (id.equals(node.getId())) {
                        root = entities[i];
                    }
                    ++i;
                }
                
                tx.success();
            }
            id = Util.sha1(nodes).substring(0, 9);
        }

        void traverse (Node node) {
            LinkedList<Node> stack = new LinkedList<>();
            stack.push(node);
            while (!stack.isEmpty()) {
                Node n = stack.pop();
                nodes.add(n.getId());
                for (Relationship rel :
                         n.getRelationships(Direction.BOTH, Entity.KEYS)) {
                    Node xn = rel.getOtherNode(n);
                    if (!nodes.contains(xn.getId()))
                        stack.push(xn);
                }
            }
        }

        ComponentImpl (GraphDatabaseService gdb, long[] nodes) {
            try (Transaction tx = gdb.beginTx()) {
                entities = new Entity[nodes.length];
                int rank = 0;
                
                for (int i = 0; i < nodes.length; ++i) {
                    Node n = gdb.getNodeById(nodes[i]);
                    entities[i] = Entity._getEntity(n);
                    Integer r = (Integer)n.getProperty(RANK, 0);
                    if (r > rank || root == null) {
                        rank = r;
                        root = entities[i];
                    }
                    this.nodes.add(nodes[i]);
                }
                id = Util.sha1(this.nodes).substring(0, 9);
                tx.success();
            }
            this.gdb = gdb;         
        }

        ComponentImpl (GraphDatabaseService gdb, Long... nodes) {
            this (gdb, Util.toPrimitive(nodes));
        }

        ComponentImpl (Component... comps) {
            Set<Entity> entities = new TreeSet<Entity>();
            int rank = 0;
            for (Component c : comps) {
                Integer r = (Integer)c.root()
                    ._node().getProperty(RANK, 0);
                if (r > rank || root == null) {
                    rank = r;
                    root = c.root();
                }
                
                for (Entity e : c) {
                    if (gdb == null) {
                        // this assumes that all entities come from the same
                        // underlying graphdb instance!
                        gdb = e.getGraphDb();
                    }
                    
                    entities.add(e);
                    nodes.add(e.getId());
                }
            }

            this.entities = entities.toArray(new Entity[0]);
            id = Util.sha1(nodes).substring(0, 9);
        }

        ComponentImpl (Entity... entities) {
            int rank = 0;
            for (Entity e : entities) {
                if (gdb == null)
                    gdb = e.getGraphDb();
                Integer r = (Integer)e._node().getProperty(RANK, 0);
                if (r > rank || root == null) {
                    rank = r;
                    root = e;
                }
                nodes.add(e.getId());
            }
            this.entities = entities;
            id = Util.sha1(nodes).substring(0, 9);
        }

        protected void setRoot (Long root) {
            if (root == null)
                throw new IllegalArgumentException
                    ("Can't set root entity to null");
            
            for (Entity e : entities) {
                if (e.getId() == root) {
                    this.root = e;
                    return;
                }
            }
            
            throw new IllegalArgumentException
                ("Entity "+root+" isn't part of component "+id);
        }
        
        protected void setRoot (Entity root) {
            if (root == null)
                throw new IllegalArgumentException
                    ("Can't set root entity to null");
            
            for (Entity e : entities) {
                if (e.equals(root)) {
                    this.root = e;
                    return;
                }
            }
            
            throw new IllegalArgumentException
                ("Entity "+root.getId()+" isn't part of component "+id);
        }

        public Entity root () { return root; }
        
        /*
         * unique set of values that span the given stitch key
         */
        public Map<Object, Integer> values (StitchKey key) {
            Map<Object, Integer> values = new HashMap<>();
            try (Transaction tx = gdb.beginTx()) {
                Set xv = new HashSet ();
                Set<Long> seen = new HashSet<>();
                for (Entity e : entities) {
                    Node n = e._node();
                    for (Relationship rel : n.getRelationships(key)) {
                        long xn = rel.getOtherNodeId(n.getId());
                        if (rel.hasProperty(VALUE)
                            && nodes.contains(xn) && !seen.contains(xn)) {
                            xv.add(rel.getProperty(VALUE));
                        }
                    }
                    seen.add(n.getId());
                }
                
                for (Object v : xv) {
                    long[] nodes = this.nodes(key, v);
                    values.put(v, nodes.length);
                }
                
                tx.success();
            }
            return values;
        }
        
        public Component filter (StitchKey key, Object value) {
            try (Transaction tx = gdb.beginTx()) {
                Component comp = _filter (key, value);
                tx.success();
                return comp;
            }
        }

        public Component _filter (StitchKey key, Object value) {
            long[] nodes = EntityFactory.nodes(gdb, key.name(), value);
            Set<Long> subset = new TreeSet<>();
            // restrict to be subset of this component
            for (int i = 0; i < nodes.length; ++i)
                if (this.nodes.contains(nodes[i]))
                    subset.add(nodes[i]);
            return new ComponentImpl (gdb, Util.toArray(subset));
        }
        
        public Iterator<Entity> iterator () {
            return Arrays.asList(entities).iterator();
        }
        
        public String getId () { return id; }
        public Entity[] entities () { return entities; }
        public int size () { return nodes.size(); }
        public Set<Long> nodeSet () {
            return Collections.unmodifiableSortedSet(nodes);
        }
        public int hashCode () { return nodes.hashCode(); }
        public boolean equals (Object obj) {
            if (obj instanceof ComponentImpl) {
                return nodes.equals(((ComponentImpl)obj).nodes);
            }
            return false;
        }

        /*
         * potential function for this component
         */
        public Double potential (StitchKey... keys) {
            if (keys == null || keys.length == 0)
                keys = Entity.KEYS;

            double score = 0.0;
            // this assumes the component is a clique
            for (int i = 0; i < entities.length; ++i) {
                for (int j = i+1; j < entities.length; ++j) {
                    double s = entities[i].similarity(entities[j], keys);
                    score += s;
                }
            }

            if (entities.length > 1) {
                score /= entities.length*(entities.length-1);
            }
            else score = 0.;
            
            return score;
        }
        
        @Override
        public long[] nodes (StitchKey key, Object value) {
            Set<Long> nodes = new TreeSet<>();
            for (Iterator<Entity> it =
                     EntityFactory.find(gdb, key.name(), value);
                 it.hasNext(); ) {
                long id = it.next().getId();
                if (this.nodes.contains(id))
                    nodes.add(id);
            }
            
            return Util.toArray(nodes);
        }

        public Map<Object, Integer> stats (StitchKey key) {
            Map<Object, Integer> stats = new HashMap<>();
            try (Transaction tx = gdb.beginTx()) {
                Set<Long> seen = new HashSet<>();
                for (int i = 0; i < entities.length; ++i) {
                    Node n = entities[i]._node();
                    for (Relationship rel :
                             n.getRelationships(Direction.BOTH, key)) {
                        if (!seen.contains(rel.getId())) {
                            Node xn = rel.getOtherNode(n);
                            if (nodes.contains(xn.getId())) {
                                Object val = rel.getProperty(VALUE, null);
                                if (val != null) {
                                    Integer c = stats.get(val);
                                    stats.put(val, c == null ? 1 : (c+1));
                                }
                                seen.add(rel.getId());
                            }
                        }
                    }
                }
                tx.success();
            }
            
            return stats;
        }

        @Override
        public void cliques (CliqueVisitor visitor, StitchKey... keys) {
            try (Transaction tx = gdb.beginTx()) {
                CliqueEnumeration clique = new CliqueEnumeration
                    (gdb, keys == null || keys.length == 0
                     ? Entity.KEYS : keys);
                clique.enumerate(nodes (), visitor);
                tx.success();
            }
        }
        
        Set<Long> getNodes (StitchKey key, Object value) {
            return getNodes (key, value, Stitchable.ANY);
        }

        Set<Long> getNodes (StitchKey key, Object value, int inclusion) {
            Set<Long> all = new TreeSet<>();
            for (Iterator<Entity> it = find (gdb, key.name(), value, inclusion);
                 it.hasNext(); ) {
                Entity e = it.next();
                if (nodes.contains(e.getId()))
                    all.add(e.getId());
            }
            return all;
        }

        @Override
        public void cliques (CliqueVisitor visitor,
                             StitchKey key, Object value, int inclusion) {
            try (Transaction tx = gdb.beginTx()) {
                    Set<Long> nodes = getNodes (key, value, inclusion);
                if (!nodes.isEmpty()) {
                    CliqueEnumeration clique = new CliqueEnumeration (gdb, key);
                    clique.enumerate(Util.toArray(nodes), visitor);
                }
                tx.success();
            }
        }

        public void stitches (BiConsumer<Entity, Entity> consumer,
                              StitchKey... keys) {
            try (Transaction tx = gdb.beginTx()) {
                Map<Long, Entity> seen = new HashMap<>();
                for (int i = 0; i < entities.length; ++i) {
                    Node n = entities[i]._node();
                    for (StitchKey key : keys) {
                        Set<Long> others = new HashSet<>();
                        for (Relationship rel : n.getRelationships(key)) {
                            Node m = rel.getOtherNode(n);
                            Entity e = seen.get(m.getId());
                            if (e != null && !others.contains(m.getId())) {
                                
                                if (m.getId() == rel.getStartNodeId()) {
                                    //logger.info("** node "+m.getId()+" -"+key+"-> "+n.getId()+" "+seen);
                                    consumer.accept(e, entities[i]);
                                }
                                else {
                                    //logger.info("** node "+n.getId()+" -"+key+"-> "+m.getId()+" "+seen);
                                    consumer.accept(entities[i], e);
                                }
                                others.add(m.getId());
                            }
                        }
                    }
                    seen.put(n.getId(), entities[i]);
                }
                seen.clear();
                tx.success();
            }
        }

        public void stitches (StitchVisitor visitor, StitchKey... keys) {
            try (Transaction tx = gdb.beginTx()) {
                Map<Long, Entity> seen = new HashMap<>();
                for (int i = 0; i < entities.length; ++i) {
                    Node n = entities[i]._node();
                    for (Relationship rel : n.getRelationships(keys)) {
                        Node m = rel.getOtherNode(n);
                        Entity e = seen.get(m.getId());
                        if (e != null) {
                            Map<StitchKey, Object> values = new TreeMap<>();
                            for (Relationship r : m.getRelationships(keys)) {
                                StitchKey key =
                                    StitchKey.valueOf(r.getType().name());
                                if (r.getOtherNode(m).equals(n)) {
                                    getStitchValues (values, r);
                                }
                            }
                            visitor.visit(entities[i], e, values);
                        }
                    }
                    seen.put(n.getId(), entities[i]);
                }
                seen.clear();
                tx.success();
            }
        }

        protected List<Entity> ov (Component comp) {
            List<Entity> ov = new ArrayList<Entity>();
            for (Entity e : comp) {
                long id = e.getId();
                if (nodes.contains(id))
                    ov.add(e);
            }
            return ov;
        }

        @Override
        public Component add (Component comp) {
            Component _comp;
            try (Transaction tx = gdb.beginTx()) {
                _comp = new ComponentImpl (this, comp);
                tx.success();
            }
            return _comp;
        }

        @Override
        public Component and (Component comp) {
            Component _comp;
            try (Transaction tx = gdb.beginTx()) {
                List<Entity> ov = ov (comp);
                _comp = ov.isEmpty() ? null
                    : new ComponentImpl (ov.toArray(new Entity[0]));
                tx.success();
            }
            return _comp;
        }

        protected Iterable<Relationship> getRelationships
            (Node node, StitchKey... keys) {
            return keys == null || keys.length == 0
                ? node.getRelationships(Direction.BOTH)
                : node.getRelationships(Direction.BOTH, keys);
        }

        protected boolean dfs (Set<Long> visited, LinkedList<Long> path,
                               Node node, Consumer<long[]> consumer,
                               BiPredicate<Long, StitchValue> predicate,
                               StitchKey... keys) {
            path.push(node.getId());
            visited.add(node.getId());
            int v = 0, edges = 0;
            for (Relationship rel : getRelationships (node, keys)) {
                Node xn = rel.getOtherNode(node);
                if (!visited.contains(xn.getId())) {
                    try {
                        boolean ok = predicate == null;
                        if (!ok) {
                            StitchValue sv = new StitchValue
                                (StitchKey.valueOf(rel.getType().name()),
                                 VALUE, rel.getProperty(VALUE, null));
                            ok = predicate.test(xn.getId(), sv);
                        }
                        
                        if (ok) {
                            if (dfs (visited, path, xn,
                                     consumer, predicate, keys))
                                consumer.accept(Util.toArray(path));
                            path.pop();
                        }
                    }
                    catch (IllegalArgumentException ex) {
                        // not a stitch key neighbor
                    }
                }
                else
                    ++v;
                ++edges;
            }
            
            return v == edges;
        }
        
        @Override
        public void depthFirst (long node, Consumer<long[]> consumer,
                                BiPredicate<Long, StitchValue> predicate,
                                StitchKey... keys) {
            try (Transaction tx = gdb.beginTx()) {
                LinkedList<Long> path = new LinkedList<>();
                Set<Long> visited = new HashSet<>();
                dfs (visited, path, gdb.getNodeById(node),
                     consumer, predicate, keys);
                tx.success();
            }
        }
        
        @Override
        public Component add (long[] nodes,
                              BiPredicate<Long, StitchValue> predicate,
                              StitchKey... keys) {
            try (Transaction tx = gdb.beginTx()) {
                Set<Long> added = new TreeSet<>(this.nodes);
                int size = added.size();
                for (int i = 0; i < nodes.length; ++i) {
                    Node n = gdb.getNodeById(nodes[i]);
                    for (Relationship rel : getRelationships (n, keys)) {
                        long xid = rel.getOtherNode(n).getId();
                        if (this.nodes.contains(xid)) {                 
                            if (predicate != null) {
                                try {
                                    StitchValue sv = new StitchValue
                                        (StitchKey.valueOf(rel.getType().name()),
                                         VALUE, rel.getProperty(VALUE, null));
                                    if (predicate.test(xid, sv))
                                        added.add(nodes[i]);
                                }
                                catch (IllegalArgumentException ex) {
                                }
                            }
                            else 
                                added.add(nodes[i]);
                        }
                    }
                }
                
                Component comp = this;
                if (added.size() > size) {
                    comp = new ComponentImpl (gdb, added.toArray(new Long[0]));
                }
                tx.success();
                
                return comp;
            }
        }

        @Override
        public Component xor (Component comp) {
            List<Entity> ov = new ArrayList<Entity>();
            for (Entity e : comp) {
                long id = e.getId();
                if (!nodes.contains(id))
                    ov.add(e);
            }
            
            for (Entity e : this) {
                long id = e.getId();
                if (!comp.nodeSet().contains(id))
                    ov.add(e);
            }
            
            return ov.isEmpty()
                ? null : new ComponentImpl (ov.toArray(new Entity[0]));
        }

        public String toString () {
            return getClass().getName()+"{id="+id+",size="
                +nodes.size()+",nodes="+nodes+"}";
        }

        public JsonNode toJson () {
            ObjectMapper mapper = new ObjectMapper ();
            ObjectNode comp = mapper.createObjectNode();            
            try (Transaction tx = gdb.beginTx()) {
                comp.put("id", id);
                comp.put("size", nodes.size());
                comp.put("node_ids", mapper.valueToTree(nodes));
                ObjectNode stitches = mapper.createObjectNode();
                for (StitchKey key : Entity.KEYS) {
                    Map<Object, Integer> sv = values (key);
                    if (!sv.isEmpty()) {
                        ArrayNode svkey = mapper.createArrayNode();
                        for (Map.Entry<Object, Integer> me : sv.entrySet()) {
                            ObjectNode sk = mapper.createObjectNode();
                            sk.put("value", mapper.valueToTree(me.getKey()));
                            sk.put("count", me.getValue());
                            sk.put("total",
                                   stitchCount (gdb, key.name(), me.getKey()));
                            svkey.add(sk);
                        }
                        stitches.put(key.name(), svkey);
                    }
                }
                comp.put("stitches", stitches);

                ArrayNode nodes = mapper.createArrayNode();
                Set<Relationship> relationships = new LinkedHashSet<>();
                for (Long id : this.nodes) {
                    ObjectNode n = mapper.createObjectNode();
                    Node node = gdb.getNodeById(id);
                    n.put("id", id);
                    ArrayNode labels = mapper.createArrayNode();
                    for (Label l : node.getLabels())
                        labels.add(l.name());
                    n.put("labels", labels);
                    n.put("properties", Util.toJsonNode(node));
                    
                    for (Relationship rel : node.getRelationships()) {
                        Node xn = rel.getOtherNode(node);
                        if (this.nodes.contains(xn.getId()))
                            relationships.add(rel);
                    }
                    nodes.add(n);
                }
                comp.put("nodes", nodes);

                ArrayNode rels = mapper.createArrayNode();
                for (Relationship rel : relationships) {
                    ObjectNode rn = mapper.createObjectNode();
                    rn.put("id", rel.getId());
                    rn.put("source", rel.getStartNode().getId());
                    rn.put("target", rel.getEndNode().getId());
                    rn.put("type", rel.getType().name());
                    rn.put("properties", Util.toJsonNode(rel));
                    rels.add(rn);
                }
                comp.put("relationships", rels);
                tx.success();
            }
            return comp;
        }
    } // ComponentImpl

    static class ComponentLazy extends ComponentImpl {
        final Node seed;
        final Integer rank;
        AtomicBoolean inited = new AtomicBoolean (false);
        
        ComponentLazy (Node node) {
            try (Transaction tx = node.getGraphDatabase().beginTx()) {
                if (!node.hasLabel(AuxNodeType.COMPONENT))
                    throw new IllegalArgumentException
                        ("Not a valid component node: "+node.getId());
                
                rank = (Integer)node.getProperty(RANK);
                if (rank == null)
                    throw new IllegalArgumentException
                        ("Node does not contain rank");
                
                seed = node;
                tx.success();
            }
        }

        void init () {
            if (!inited.get()) {
                instrument (seed);
                inited.set(true);
            }
        }

        @Override public int size () {
            init ();
            return rank;
        }
        @Override public Set<Long> nodeSet () {
            init ();
            return super.nodeSet();
        }
        @Override public Entity[] entities () {
            init ();
            return super.entities();
        }
        @Override public Iterator<Entity> iterator () {
            init ();
            return super.iterator();
        }
        @Override public Double potential (StitchKey... keys) {
            return rank.doubleValue();
        }
    }

    static class CliqueImpl extends ComponentImpl implements Clique {
        final Map<StitchKey, Object> values = new EnumMap<>(StitchKey.class);
        
        CliqueImpl (Component... comps) {
            super (comps);
        }

        CliqueImpl (BitSet C, long[] gnodes, Set<StitchKey> keys,
                    GraphDatabaseService gdb) {
            entities = new Entity[C.cardinality()];
            try (Transaction tx = gdb.beginTx()) {
                Node[] nodes = new Node[C.cardinality()];
                for (int i = C.nextSetBit(0), j = 0;
                     i >= 0; i = C.nextSetBit(i+1)) {
                    nodes[j] = gdb.getNodeById(gnodes[i]);
                    entities[j] = Entity._getEntity(nodes[j]);
                    this.nodes.add(gnodes[i]);
                    ++j;
                }

                for (StitchKey key : keys)
                    update (nodes, key);

                if (values.isEmpty()) {
                    logger.warning("Clique has no defining key span!\n"
                                   +this.nodes);
                }
                tx.success();
            }
            id = Util.sha1(nodes).substring(0, 9);
            this.gdb = gdb;
        }

        void update (Node[] nodes, StitchKey key) {
            final Map<Object, Integer> counts = new HashMap<Object, Integer>();
            for (int i = 0; i < nodes.length; ++i) {
                for (Relationship rel
                         : nodes[i].getRelationships(key, Direction.BOTH)) {
                    for (int j = i+1; j < nodes.length; ++j) {
                        if (nodes[j].equals(rel.getOtherNode(nodes[i]))) {
                            if (rel.hasProperty(VALUE)) {
                                Object val = rel.getProperty(VALUE);
                                Integer c = counts.get(val);
                                counts.put(val, c != null ? c+1:1);
                            }
                        }
                    }
                }
            }

            int total = nodes.length*(nodes.length-1)/2;
            Object value = null;            
            for (Map.Entry<Object, Integer> me : counts.entrySet()) {
                Integer c = me.getValue();
                if (c == total) {
                    value = value == null ? me.getKey()
                        : Util.merge(value, me.getKey());
                }
                else /*if (c > 1)*/ { // multiple values for this stitchkey
                    /*
                    value = value == null
                        ? me.getKey() : Util.merge(value, me.getKey());
                    */
                }
            }

            if (value != null) {
                if (value.getClass().isArray()) {
                    // make sure it's sorted from most frequent to least
                    Object[] sorted = new Object[Array.getLength(value)];
                    for (int i = 0; i < sorted.length; ++i)
                        sorted[i] = Array.get(value, i);
                    
                    Arrays.sort(sorted, new Comparator () {
                            public int compare (Object o1, Object o2) {
                                Integer c1 = counts.get(o1);
                                Integer c2 = counts.get(o2);
                                return c2 - c1;
                            }
                        });
                    
                    for (int i = 0; i < sorted.length; ++i)
                        Array.set(value, i, sorted[i]);
                }

                values.put(key, value);
            }
        }
        
        public Map<StitchKey, Object> values () { return values; }
        
        @Override
        public Clique add (Component clique) {
            CliqueImpl ci = null;           
            List<Entity> ov = ov (clique);
            if (!ov.isEmpty()) {
                ci = new CliqueImpl (this, clique);
                if (clique instanceof Clique) {
                    ci.values.putAll(((Clique)clique).values());
                }
                ci.values.putAll(values);
            }
            return ci;
        }

        @Override
        public Double potential (StitchKey... keys) {
            int priority = 1;
            for (StitchKey sk: values.keySet())
                if (priority < sk.priority)
                    priority = sk.priority;
            return priority * Math.pow(size(), 1. - CLIQUE_WEIGHT)
                * Math.pow(values.size(), CLIQUE_WEIGHT);
        }
    }
    
    /*
     * using the standard Bron-Kerbosch enumeration algorithm
     */
    static class CliqueEnumeration {
        final GraphDatabaseService gdb;
        final Map<BitSet, EnumSet<StitchKey>> cliques =
            new HashMap<BitSet, EnumSet<StitchKey>>();
        final StitchKey[] keys;
        
        CliqueEnumeration (GraphDatabaseService gdb, StitchKey... keys) {
            this.gdb = gdb;
            this.keys = keys;
        }

        public boolean enumerate (long[] nodes, CliqueVisitor visitor) {
            cliques.clear();
            
            for (StitchKey key : keys)
                enumerate (key, nodes);
            
            for (Map.Entry<BitSet, EnumSet<StitchKey>> me
                     : cliques.entrySet()) {
                
                Clique clique = new CliqueImpl
                    (me.getKey(), nodes, me.getValue(), gdb);
                
                // filter out any clique that has multiple values for
                //   a particular stitch key
                Map<StitchKey, Object> values = clique.values();
                /*
                EnumSet<StitchKey> keys = EnumSet.noneOf(StitchKey.class);
                for (Map.Entry<StitchKey, Object> e : values.entrySet()) {
                    if (e.getValue().getClass().isArray())
                        keys.add(e.getKey());
                }
                
                for (StitchKey k : keys)
                    values.remove(k);
                */
                
                if (!values.isEmpty() && !visitor.clique(clique))
                    return false;
            }
            
            return true;
        }
        
        void enumerate (StitchKey key, long[] nodes) {
            BitSet P = new BitSet (nodes.length);
            for (int i = 0; i < nodes.length; ++i) {
                P.set(i);
            }
            BitSet C = new BitSet (nodes.length);
            BitSet S = new BitSet (nodes.length);
            Graph G = new Graph (gdb, key, nodes);

            /*
            { Set<Long> g = new TreeSet<Long>();
                for (int i = 0; i < nodes.length; ++i)
                    g.add(nodes[i]);
                logger.info("Clique enumeration "+key+" G="+g+"...");
            }
            */
            
            bronKerbosch (G, C, P, S);
        }

        boolean bronKerbosch (Graph G, BitSet C, BitSet P, BitSet S) {
            boolean done = false;
            if (P.isEmpty() && S.isEmpty()) {
                //logger.info("@Clique "+C);
                // only consider cliques that are of size >= CLIQUE_MINSIZE
                if (C.cardinality() >= CLIQUE_MINSIZE) {
                    BitSet c = (BitSet)C.clone();
                    
                    EnumSet<StitchKey> keys = cliques.get(c);
                    if (keys == null) {
                        cliques.put(c, EnumSet.of(G.key()));
                        //logger.info("Clique found.."+c);
                    }
                    else
                        keys.add(G.key());
                    
                    done = C.cardinality() == G.size();
                }
            }
            else {
                for (int u = P.nextSetBit(0); u >=0 && !done ;
                     u = P.nextSetBit(u+1)) {
                    P.clear(u);
                    BitSet PP = (BitSet)P.clone();
                    BitSet SS = (BitSet)S.clone();
                    PP.and(G.edges(u));
                    SS.and(G.edges(u));
                    C.set(u);
                    done = bronKerbosch (G, C, PP, SS);
                    C.clear(u);
                    S.set(u);
                }
            }
            
            return done;
        }
    }

    protected final GraphDb graphDb;
    protected final GraphDatabaseService gdb;
    protected final TimelineIndex<Node> timeline;
    protected final DataSourceFactory dsf;
    protected TextIndexer indexer;
    
    public EntityFactory (String dir) throws IOException {
        this (GraphDb.getInstance(dir));
    }
    
    public EntityFactory (File dir) throws IOException {
        this (GraphDb.getInstance(dir));
    }

    public EntityFactory (GraphDatabaseService gdb) {
        this (GraphDb.getInstance(gdb));
    }
    
    public EntityFactory (GraphDb graphDb) {
        if (graphDb == null)
            throw new IllegalArgumentException ("GraphDb instance is null!");

        this.gdb = graphDb.graphDb();   
        try (Transaction tx = gdb.beginTx()) {
            this.timeline = new LuceneTimeline
                (gdb, gdb.index().forNodes(CNode.NODE_TIMELINE));
            tx.success();
        }
        try {
            indexer = graphDb.getTextIndexer(TEXT_INDEXER);
        }
        catch (Exception ex) {
            logger.warning("TextIndexer is not available: "+ex.getMessage());
        }
        this.graphDb = graphDb;
        this.dsf = new DataSourceFactory (graphDb);
    }

    static void dfs (Set<Long> nodes, Set<Relationship> edges,
                     Node n, StitchKey key, Object value) {
        nodes.add(n.getId());
        for (Relationship rel : n.getRelationships(Direction.BOTH)) {
            if (rel.isType(key)
                && (value == null
                    || value.equals(rel.getProperty(VALUE, null))))
                edges.add(rel);
            Node xn = rel.getOtherNode(n);
            if (!nodes.contains(xn.getId()))
                dfs (nodes, edges, xn, key, value); 
        }
    }

    public GraphDb getGraphDb () { return graphDb; }
    public CacheFactory getCache () { return graphDb.getCache(); }
    public void setCache (CacheFactory cache) {
        graphDb.setCache(cache);
    }
    public void setCache (String cache) throws IOException {
        graphDb.setCache(CacheFactory.getInstance(cache));
    }
    public DataSourceFactory getDataSourceFactory () { return dsf; }
    public long getLastUpdated () { return graphDb.getLastUpdated(); }
    
    public GraphMetrics calcGraphMetrics() {
        return calcGraphMetrics (gdb, AuxNodeType.ENTITY,
                                 Entity.TYPES, Entity.KEYS);
    }

    public GraphMetrics calcGraphMetrics (String label) {
        return calcGraphMetrics (Label.label(label));
    }
    
    public GraphMetrics calcGraphMetrics (Label label) {
        return calcGraphMetrics (gdb, label, Entity.TYPES, Entity.KEYS);
    }

    public static GraphMetrics calcGraphMetrics
        (GraphDatabaseService gdb) {
        return calcGraphMetrics
            (gdb, AuxNodeType.ENTITY, Entity.TYPES, Entity.KEYS);
    }

    public static GraphMetrics calcGraphMetrics
        (GraphDatabaseService gdb, EntityType[] types,
         RelationshipType[] keys) {
        return calcGraphMetrics (gdb, AuxNodeType.ENTITY, types, keys);
    }

    public static GraphMetrics calcGraphMetrics (Stream<Node> nodes) {
        return calcGraphMetrics (nodes, Entity.TYPES, Entity.KEYS);
    }
    
    public static GraphMetrics calcGraphMetrics
        (Stream<Node> nodes, EntityType[] types, RelationshipType[] keys) {
        DefaultGraphMetrics metrics = new DefaultGraphMetrics ();
        nodes.forEach(node -> {
                for (EntityType t : types) {
                    if (node.hasLabel(t)) {
                        Integer c = metrics.entityHistogram.get(t);
                        metrics.entityHistogram.put(t, c != null ? c+1:1);
                    }
                }
                int nrel = 0;
                for (Relationship rel
                         : node.getRelationships(Direction.BOTH, keys)) {
                    Node xn = rel.getOtherNode(node);
                    // do we count self reference?
                    if (!xn.equals(node)) {
                        ++metrics.stitchCount;
                    }
                    String key = rel.getType().name();
                    Integer c = metrics.stitchHistogram.get(key);
                    metrics.stitchHistogram.put(key, c != null ? c+1:1);
                    ++nrel;
                }
                Integer c = metrics.entitySizeDistribution.get(nrel);
                metrics.entitySizeDistribution.put(nrel, c!=null ? c+1:1);
                
                if (node.hasLabel(AuxNodeType.COMPONENT)) {
                    Component comp = new ComponentImpl (node);
                    Integer cnt = metrics.connectedComponentHistogram.get
                        (comp.size());
                    metrics.connectedComponentHistogram.put
                        (comp.size(), cnt!= null ? cnt+1:1);
                    if (comp.size() == 1) {
                        ++metrics.singletonCount;
                    }
                    ++metrics.connectedComponentCount;
                    /*
                      if (comp.size() > 5)
                      logger.info("Component "+node.getId()
                      +" has "+comp.size()+" member(s)!");
                    */
                }
                
                ++metrics.entityCount;
            });
        
        // we're double counting, so now we correct the counts
        metrics.stitchCount /= 2;
        for (String k : metrics.stitchHistogram.keySet()) {
            metrics.stitchHistogram.put
                (k, metrics.stitchHistogram.get(k)/2);
        }
        
        return metrics;
    }
    
    public static GraphMetrics calcGraphMetrics
        (GraphDatabaseService gdb, Label label,
         EntityType[] types, RelationshipType[] keys) {

        GraphMetrics metrics = null;
        try (Transaction tx = gdb.beginTx()) {
            metrics = calcGraphMetrics
                (gdb.findNodes(label).stream(), types, keys);
            tx.success();
        }
        return metrics;
    }

    public static GraphMetrics calcGraphMetrics (Component component) {
        return calcGraphMetrics (component.stream().map(e -> e._node()));
    }

    static boolean hasLabel (Node node, Label... labels) {
        for (Label l : labels)
            if (node.hasLabel(l))
                return true;
        return false;
    }

    public Component getComponent (long[] nodes) {
        return new ComponentImpl (gdb, nodes);
    }

    public Component getComponent (Long... nodes) {
        return new ComponentImpl (gdb, nodes);
    }
    
    /*
     * return the top k stitched values for a given stitch key
     */
    public Map<Object, Integer> getStitchedValueDistribution (StitchKey key) {
        return getStitchedValueDistribution (key, (Label[])null);
    }
    
    public Map<Object, Integer> getStitchedValueDistribution
        (StitchKey key, String... labels) {
        Label[] l = null;
        if (labels != null && labels.length > 0) {
            l = new Label[labels.length];
            for (int i = 0; i < labels.length; ++i)
                l[i] = Label.label(labels[i]);
        }
        return getStitchedValueDistribution (key, l);
    }
    
    public Map<Object, Integer> getStitchedValueDistribution
        (StitchKey key, Label... labels) {
        Map<Object, Integer> values = new HashMap<Object, Integer>();
        try (Transaction tx = gdb.beginTx()) {
            for (Relationship rel : gdb.getAllRelationships()) {
                if (rel.isType(key) && rel.hasProperty(Entity.VALUE)
                    && (labels == null
                        || (hasLabel (rel.getStartNode(), labels)
                            && hasLabel (rel.getEndNode(), labels)))) {
                        Object val = rel.getProperty(Entity.VALUE);
                        Integer c = values.get(val);
                        values.put(val, c!=null ? c+1:1);
                }
            }
            tx.success();
        }
        return values;
    }

    public int getStitchedValueCount (StitchKey key, Object value) {
        int count = 0;
        try (Transaction tx = gdb.beginTx()) {
            RelationshipIndex index = gdb.index().forRelationships
                (Entity.relationshipIndexName());
            IndexHits<Relationship> hits = index.get(key.name(), value);
            count = hits.size();
            hits.close();
            tx.success();
        }
        return count;
    }

    public void stitchValues (StitchValueVisitor visitor, StitchKey... keys) {
        List<Long> comps = new ArrayList<>();
        if (components (comps) > 0) {
            for (Long c : comps)
                entity(c).componentStitchValues(visitor, keys);
        }
    }

    /*
     * globally delete the value for a particular stitch key
     */
    public void delete (StitchKey key, Object value) {
        try (Transaction tx = gdb.beginTx()) {
            RelationshipIndex index =
                gdb.index().forRelationships(Entity.relationshipIndexName());
            IndexHits<Relationship> hits = index.get(key.name(), value);
            try {
                for (Relationship rel : hits) {
                    CNode._delete(rel.getStartNode(), key.name(), value);
                    CNode._delete(rel.getEndNode(), key.name(), value);
                    index.remove(rel);
                    rel.delete();
                }
            }
            finally {
                hits.close();
            }
            tx.success();
        }
    }

    public int delete (DataSource source) {
        return delete (source.getKey());
    }
    
    /*
     * delete the entire data source; note that source can either be
     * the name or its key
     */
    public int delete (String source) {
        int count = 0;
        try (Transaction tx = gdb.beginTx()) {
            Index<Node> index = gdb.index().forNodes
                (DataSource.nodeIndexName());

            Label label = Label.label(source);
            Node n = index.get(KEY, source).getSingle();
            if (n == null) {
                source = DataSourceFactory.sourceKey(source);
                n = index.get(KEY, source).getSingle();
            }

            if (n != null) {
                label = Label.label((String)n.getProperty(NAME));
            }
            else {
                logger.warning("Can't find data source: "+source);
            }

            for (Iterator<Node> it = gdb.findNodes(label, SOURCE, source);
                 it.hasNext(); ) {
                Node node = it.next();
                Entity._getEntity(node).delete();
                ++count;
            }
            logger.info(count+" entities deleted for \""+source+"\"");

            count = 0;
            for (Iterator<Relationship> it = gdb.getAllRelationships().iterator(); it.hasNext();) {
                Relationship rel = it.next();
                if (rel.isType(AuxRelType.PAYLOAD)) {
                    String s = (String)rel.getProperty(SOURCE);
                    if (source.equals(s)) {
                        Entity payload = Entity._getEntity(rel.getStartNode());
                        rel.delete();
                        payload._node.delete();
                        ++count;
                    }
                }
            }
            logger.info(count+" payloads deleted for \""+source+"\"");

            tx.success();
        }
        return count;
    }

    static void getStitchValues (Map<StitchKey, Object> values,
                                 Relationship r) {
        StitchKey key = StitchKey.valueOf(r.getType().name());
        Object v = r.getProperty(VALUE);
        Object val = values.get(key);
        if (v == null);
        else if (val == null) values.put(key, v);
        else values.put(key, Util.merge(val, v));
    }

    public void stitches (StitchVisitor visitor, StitchKey... keys) {
        if (keys.length == 0)
            return;
        
        try (Transaction tx = gdb.beginTx()) {
            StringBuilder query = new StringBuilder
                ("match (a:DATA)-[:PAYLOAD]->(n:ENTITY)-[:"+keys[0]);
            for (int i = 1; i < keys.length; ++i)
                query.append("|:"+keys[i]);
            query.append("]-(m:ENTITY)<-[:PAYLOAD]-(b:DATA) "
                         +"where not n:TRANSIENT "
                         +"and not m:TRANSIENT "
                         //+"and not exists(a.deprecated) "
                         //+"and not exists(b.deprecated) "
                         +"return n,m");
            try (Result result = gdb.execute(query.toString())) {
                while (result.hasNext()) {
                    Map<String, Object> row = result.next();
                    Node n = (Node) row.get("n");
                    Node m = (Node) row.get("m");
                    Map<StitchKey, Object> values = new TreeMap<>();
                    for (Relationship r :  n.getRelationships()) {
                        if (r.getOtherNode(n).equals(m)) {
                            getStitchValues (values, r);
                        }
                    }
                    visitor.visit(Entity._getEntity(n), Entity._getEntity(m),
                                  values);
                }
            }
            tx.success();
        }
    }
    
    public Iterator<Component> connectedComponents (Predication predication) {
        return connectedComponents (1, predication);
    }
    
    public Iterator<Component> connectedComponents (int minscore,
                                                    Predication predication) {
        return new StronglyConnectedComponents (gdb, predication, minscore);
    }

    public Collection<Component> components () {
        List<Component> comps = new ArrayList<Component>();
        try (Transaction tx = gdb.beginTx()) {
            gdb.findNodes(AuxNodeType.COMPONENT).stream().forEach(node -> {
                    comps.add(new ComponentLazy (node));
                });
            tx.success();
        }
        
        return comps;
    }

    public int components (Collection<Long> comps) {
        try (Transaction tx = gdb.beginTx()) {
            gdb.findNodes(AuxNodeType.COMPONENT).stream().forEach(node -> {
                    comps.add(node.getId());
                });
            tx.success();
        }
        return comps.size();
    }

    public void components (Consumer<Component> consumer) {
        try (Transaction tx = gdb.beginTx()) {
            gdb.findNodes(AuxNodeType.COMPONENT).stream().forEach(node -> {
                    //consumer.accept(new ComponentLazy (node));
                    consumer.accept(new ComponentImpl (node));
                });
            tx.success();
        }
    }

    public Component component (long id) {
        try (Transaction tx = gdb.beginTx()) {
            Node node = gdb.getNodeById(id);
            if (!node.hasLabel(AuxNodeType.COMPONENT))
                node = CNode.getRoot(node);
            Component comp = new ComponentImpl (node);
            tx.success();
            
            return comp;
        }
    }

    public Component component (long[] nodes) {
        return component (null, nodes);
    }
    
    public Component component (Long root, long[] nodes) {
        try (Transaction tx = gdb.beginTx()) {
            Component comp = new ComponentImpl (gdb, nodes);
            tx.success();
            return comp;
        }
    }

    public Long count (String... labels) {
        return count (Arrays.stream(labels)
                      .map(l -> Label.label(l)).toArray(Label[]::new));
    }
    
    public Long count (Label... labels) {
        StringBuilder q = new StringBuilder ("match(n");
        if (labels != null) {
            for (Label l : labels)
                q.append(":`"+l+"`");
        }
        q.append(") return count(n) as count");
        
        Long count = null;
        try (Transaction tx = gdb.beginTx();
             Result result = gdb.execute(q.toString())) {
            if (result.hasNext()) {
                Map<String, Object> row = result.next();
                count = (Long)row.get("count");
            }
            result.close();
            tx.success();
        }
        return count;
    }
    
    public Entity[] entities (int skip, int top, String... labels) {
        return entities (skip, top, Arrays.stream(labels)
                         .map(l -> Label.label(l)).toArray(Label[]::new));
    }
    
    public Entity[] entities (int skip, int top, Label... labels) {
        List<Entity> page = new ArrayList<Entity>();
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("skip", skip);
        params.put("top", top);

        StringBuilder query = new StringBuilder ("match(n");
        for (Label l : labels) {
            query.append(":`"+l+"`");
        }
        query.append(") return n skip {skip} limit {top}");

        //System.out.println("components: skip="+skip+" top="+top);
        try (Transaction tx = gdb.beginTx();
             Result result = gdb.execute(query.toString(), params)) {
            while (result.hasNext()) {
                Map<String, Object> row = result.next();
                //System.out.println("  rows: "+row);
                Node n = (Node)row.get("n");
                try {
                    page.add(Entity._getEntity(n));
                }
                catch (Exception ex) { // not an entity
                }
            }
            result.close();
            tx.success();
        }
        
        return page.toArray(new Entity[0]);
    }

    public Iterator<Entity> find (StitchKey key, Object value) {
        return find (key.name(), value);
    }

    public boolean cliques (CliqueVisitor visitor) {
        return cliques (visitor, Entity.KEYS);
    }

    public boolean cliques (String label, CliqueVisitor visitor) {
        return label != null ? 
            cliques (Label.label(label), visitor, Entity.KEYS)
            : cliques (visitor, Entity.KEYS);
    }
    
    public boolean cliques (String label, CliqueVisitor visitor,
                            StitchKey... keys) {
        if (keys == null || keys.length == 0)
            keys = Entity.KEYS;
        
        return label != null ?
            cliques (Label.label(label), visitor, keys)
            : cliques (visitor, keys);
    }

    public boolean cliques (Label label, CliqueVisitor visitor,
                            StitchKey[] keys) {
        try (Transaction tx = gdb.beginTx()) {
            
            List<Long> ids = new ArrayList<Long>();
            for (Iterator<Node> it = gdb.findNodes(label); it.hasNext(); ) {
                Node n = it.next();
                ids.add(n.getId());
            }
            
            boolean ret = cliques (Util.toArray(ids), visitor, keys);
            tx.success();
            
            return ret;
        }
    }

    public boolean cliques (CliqueVisitor visitor, StitchKey... keys) {
        /*
        ConnectedComponents cc = new ConnectedComponents (gdb);
        long[][] comps = cc.components();
        for (int i = 0; i < comps.length
                 && comps[i].length >= CLIQUE_MINSIZE; ++i) {
            if (!cliques (keys, comps[i], visitor))
                return false;
        }
        return true;
        */

        boolean ret = true;
        try (Transaction tx = gdb.beginTx()) {
            for (Iterator<Node> it = gdb.findNodes(AuxNodeType.COMPONENT);
                 it.hasNext();) {
                Node node = it.next();
                
                Integer rank = (Integer)node.getProperty(RANK);
                if (rank == null)
                    throw new RuntimeException ("Component node "+node.getId()
                                                +" has no rank!");
                if (rank >= CLIQUE_MINSIZE) {
                    Component comp = new ComponentImpl (node);
                    if (!cliques (comp.nodes(), visitor, keys)) {
                        ret = false;
                        break;
                    }
                }
            }
            tx.success();
        }
        
        return ret;
    }

    public boolean cliques (long[] nodes, CliqueVisitor visitor) {
        return cliques (nodes, visitor, Entity.KEYS);
    }

    public boolean cliques
        (Entity[] entities, CliqueVisitor visitor, StitchKey... keys) {
        long[] nodes = new long[entities.length];
        try (Transaction tx = gdb.beginTx()) {
            for (int i = 0; i < nodes.length; ++i)
                nodes[i] = entities[i].getId();
            
            boolean ret = cliques (nodes, visitor, keys);
            tx.success();
            
            return ret;
        }
    }
    
    public boolean cliques (Entity[] entities, CliqueVisitor visitor) {
        return cliques (entities, visitor, Entity.KEYS);
    }
    
    public boolean cliques (long[] nodes, CliqueVisitor visitor,
                            StitchKey... keys) {
        /*
        { EnumSet<StitchKey> set = EnumSet.noneOf(StitchKey.class);
            for (StitchKey k : keys) set.add(k);

            logger.info("enumerating cliques over "+set+" spanning "
                        +nodes.length+" nodes...");
        }
        */

        if (nodes.length >= CLIQUE_MINSIZE) {
            CliqueEnumeration clique = new CliqueEnumeration (gdb, keys); 
            // enumerate all cliques for this key
            return clique.enumerate(nodes, visitor);
        }
        return false;
    }

    public boolean cliques (CliqueVisitor visitor,
                            StitchKey key, Object value) {
        long[] nodes = nodes (key.name(), value);
        if (nodes.length > 0) {
            CliqueEnumeration clique = new CliqueEnumeration (gdb, Entity.KEYS);
            return clique.enumerate(nodes, visitor);
        }
        return false;
    }

    public void untangle (UntangleAbstract untangler) {
        untangle (untangler, null);
    }
    
    /**
     * Untangles {@link UntangleAbstract} and registers the {@link Stitch} into
     * the datasource. If postProcess is specified, perform that operation on
     * the stitch as well.
     * 
     * 
     * @param untangler UntangleAbstract object.
     * @param consumer Consumer object of the Stitch type.
     */
    public void untangle (UntangleAbstract untangler,
                          Consumer<Stitch> consumer) {
        untangler.untangle(this, (root, member) -> {
                ComponentImpl comp = new ComponentImpl (gdb, member);
                if (root != null)
                    comp.setRoot(root);
                DataSource dsource = untangler.getDataSource();
                Stitch stitch = createStitch (dsource, comp);
                if (consumer != null)
                    consumer.accept(stitch);
            });
    }

    public int stitchCount (StitchKey key, Object value) {
        return stitchCount (key.name(), value);
    }
    
    public int stitchCount (StitchKey key, Object value, Label... labels) {
        return stitchCount (key.name(), value, labels);
    }
    
    public int stitchCount (String key, Object value) {
        return stitchCount (gdb, key, value, AuxNodeType.ENTITY);
    }

    public int stitchCount (String key, Object value, Label... labels) {
        return stitchCount (gdb, key, value, labels);
    }

    public static int stitchCount (GraphDatabaseService gdb,
                                   String key, Object value,
                                   Label... labels) {
        try (Transaction tx = gdb.beginTx();
             IndexHits<Relationship> hits = gdb.index()
             .forRelationships(Entity.relationshipIndexName())
             .get(key, value)) {
            int size = 0;
            if (labels == null || labels.length == 0) {
                size = hits.size();
            }
            else {
                for (Relationship rel : hits) {
                    Node n = rel.getStartNode();
                    Node m = rel.getEndNode();
                    int nl = 0, ml = 0;
                    for (Label l : labels) {
                        if (n.hasLabel(l)) ++nl;
                        if (m.hasLabel(l)) ++ml;
                        if (nl > 0 && ml > 0)
                            break;
                    }
                    
                    if (nl > 0 && ml > 0)
                        ++size;
                }
            }
            tx.success();
            return size;
        }
    }
    
    public List<String> testUntangle (UntangleAbstract untangler) {
        List<String> newStitches = new ArrayList();
        untangler.untangle(this, (root, member) -> {
            String comp = "root:"+root.toString()+" members:";
            for (long l: member)
                comp = comp + l + ",";
            newStitches.add(comp);
        });
        return newStitches;
    }

    public static Iterator<Entity> find (GraphDatabaseService gdb,
                                         String key, Object value) {
        return find (gdb, key, value, Stitchable.ANY);
    }

    public static Iterator<Entity> find (GraphDatabaseService gdb,
                                         String key, Object value, 
                                         int inclusion) {
        Iterator<Entity> iterator = null;
        try (Transaction tx = gdb.beginTx()) {
            Index<Node> index = gdb.index().forNodes(Entity.nodeIndexName());
            if (value.getClass().isArray() && inclusion != Stitchable.SINGLE) {
                int len = Array.getLength(value);
                if (inclusion == Stitchable.ANY) {
                    IndexHits<Node> best = null;
                    for (int i = 0; i < len; ++i) {
                        Object v = Array.get(value, i);
                        IndexHits<Node> hits = index.get(key, v);
                        // return on the first non-empty hits
                        if (best == null || hits.size() > best.size()) {
                            if (best != null)
                                best.close();
                            best = hits;
                        }
                        else
                            hits.close();
                    }
                
                    if (best != null) {
                        iterator = new EntityIterator (gdb, best.iterator());
                    }       
                }
                else if (inclusion == Stitchable.ALL) {
                    Set<Node> nodes = null;
                    for (int i = 0; i < len; ++i) {
                        Object v = Array.get(value, i);
                        IndexHits<Node> hits = index.get(key, v);
                        Set<Node> nn = new HashSet<>();
                        for (Node n : hits)
                            nn.add(n);
                        if (nodes == null)
                            nodes = nn;
                        else
                            nodes.retainAll(nn);

                        hits.close();
                    }

                    if (nodes != null) {
                        iterator = nodes.stream()
                            .map(n -> Entity.getEntity(n)).iterator();
                    }
                }
                else {
                    logger.warning("Unknown inclusion value: "+inclusion);
                }
            }

            if (iterator == null)
                iterator = new EntityIterator
                    (gdb, index.get(key, value).iterator());
            
            tx.success();
        }
        
        return iterator;
    }

    public Entity[] filter (String key, Object value, String... labels) {
        return filter (key, value, 0, 10, labels);
    }
    
    public Entity[] filter (String key, Object value,
                            int skip, int top, String... labels) {
        return filter (key, value, skip, top, Arrays.stream(labels)
                       .map(l -> Label.label(l)).toArray(Label[]::new));
    }

    public Entity[] filter (String key, Object value,
                            int skip, int top, Label... labels) {
        if (key == null)
            throw new IllegalArgumentException
                ("Can't filter with key is null!");
        
        StringBuilder query = new StringBuilder
            ("START n=node:`"+Entity.nodeIndexName()+"`(`"
             +key+"`="+value+") with n MATCH(n");
        if (labels != null) {
            for (Label l : labels)
                query.append(":`"+l+"`");
        }
        query.append(") return n");
        
        Map<String, Object> params = new HashMap<>();
        if (top > 0) {
            query.append(" skip {skip} limit {top}");
            params.put("skip", skip);
            params.put("top", top);
        }

        logger.info("QUERY: "+query);
        
        try (Transaction tx = gdb.beginTx();
             Result result = gdb.execute(query.toString(), params)) {
            List<Entity> entities = new ArrayList<>();
            while (result.hasNext()) {
                try {
                    Map<String, Object> row = result.next();
                    //logger.info(">>> "+row);
                    entities.add(Entity._getEntity((Node)row.get("n")));
                }
                catch (Exception ex) {
                }
            }
            result.close();
            
            Entity[] ents = entities.toArray(new Entity[0]);
            tx.success();
            
            return ents;
        }
    }

    public Iterator<Entity> find (String key, Object value) {
        return find (gdb, key, value);
    }

    public long[] nodes (String key, Object value) {
        return nodes (gdb, key, value);
    }
    
    public static long[] nodes (GraphDatabaseService gdb,
                                String key, Object value) {
        Set<Long> nodes = new TreeSet<>();
        for (Iterator<Entity> it = find (gdb, key, value); it.hasNext(); ) {
            nodes.add(it.next().getId());
        }
        return Util.toArray(nodes);
    }

    public String[] labels () {
        try (Transaction tx = gdb.beginTx()) {
            String[] labels = gdb.getAllLabelsInUse().stream()
                .map(l -> l.name())
                .toArray(String[]::new);
            tx.success();
            
            return labels;
        }
    }

    public String[] relationships () {
        try (Transaction tx = gdb.beginTx()) {
            String[] rels = gdb.getAllRelationshipTypes().stream()
                .map(l -> l.name())
                .toArray(String[]::new);
            tx.success();
            return rels;
        }
    }

    public String[] properties () {
        try (Transaction tx = gdb.beginTx()) {
            String[] props = gdb.getAllPropertyKeys()
                .stream().toArray(String[]::new);
            tx.success();
            return props;
        }
    }

    /*
     * traverse over each connected component in turn
     */
    public void traverse (EntityVisitor visitor, StitchKey... keys) {
        try (Transaction tx = gdb.beginTx()) {
            gdb.findNodes(AuxNodeType.COMPONENT).stream().forEach(node -> {
                    logger.info("############### COMPONENT "
                                +node.getId()+" ("+ node.getProperty(RANK)
                                +") ################");
                    Entity._getEntity(node)._traverse(visitor, keys);
                });
            tx.success();
        }
    }
    
    /*
     * iterate over entities of a particular data source
     */
    public Iterator<Entity> entities (DataSource source) {
        return entities (source.getName());
    }

    public Iterator<Entity> entities (String label) {
        try (Transaction tx = gdb.beginTx()) {
            Iterator<Entity> iter = new EntityIterator
                (gdb, gdb.findNodes(Label.label(label)));
            tx.success();
            return iter;
        }
    }
    
    public void entities (DataSource source, Consumer<Entity> consumer) {
        entities(source.getName(), consumer);
    }
    
    public void entities (String label, Consumer<Entity> consumer) {
        Objects.requireNonNull(consumer);
        try (Transaction tx = gdb.beginTx();
             ResourceIterator<Node> iter = gdb.findNodes(Label.label(label))) {
            while(iter.hasNext()) {
                consumer.accept(Entity.getEntity(iter.next()));
            }
            tx.success();
        }
    }

    public void entities (Consumer<Entity> consumer) {
        Objects.requireNonNull(consumer);
        try (Transaction tx = gdb.beginTx();
             ResourceIterator<Node> iter = gdb.findNodes(AuxNodeType.ENTITY)) {

            while(iter.hasNext()) {
                consumer.accept(Entity.getEntity(iter.next()));
            }
            tx.success();

        }
    }

    /**
     * perform a query to search for all entities with
     * the given labels and for each one, call the given consumer.
     * @param func the consumer function to call on each entity.
     * @param labels the node labels to search for.
     * @return returns the value of the query result counter.
     */
    public int maps (Consumer<Entity> func, String... labels) {
        StringBuilder query = new StringBuilder ("match(n");
        for (String l : labels) {
            query.append(":`"+l+"`");
        }
        query.append(") return n skip {skip} limit {top}");

        int count = 0, top = 100, skip = 0;
        Map<String, Object> params = new HashMap<>();
        params.put("top", top);
        do {
            params.put("skip", skip);
            try (Transaction tx = gdb.beginTx();
                 Result result = gdb.execute(query.toString(), params)) {
                count = 0;
                while (result.hasNext()) {
                    Map<String, Object> row = result.next();
                    Entity e = Entity._getEntity((Node)row.get("n"));
                    func.accept(e);
                    ++count;
                }
                result.close();
                tx.success();
            }
            skip += count;
        }
        while (count == top);
        
        return skip+count;
    }

    public int maps (Consumer<Entity> func, long[] ids) {
        int count = 0;
        try (Transaction tx = gdb.beginTx()) {
             Entity[] ents = _entities (ids);
             for (Entity entity: ents) {
                try {
                    func.accept(entity);
                    ++count;
                }
                catch (Exception ex) {
                    // not a valid entity
                    ex.printStackTrace();
                }
            }
            tx.success();
        }

        return count;
    }

    public Entity[] entities (long[] ids) {
        try (Transaction tx = gdb.beginTx()) {
            Entity[] ents = _entities (ids);
            tx.success();
            return ents;
        }
    }

    public Entity[] _entities (long[] ids) {
        Entity[] entities = new Entity[ids.length];
        for (int i = 0; i < ids.length; ++i)
            entities[i] = _entity (ids[i]);
        return entities;
    }   

    public Entity entity (Integer ver, String id) {
        Entity[] entities = filter("id", "'"+id+"'", 0, 0, "S_STITCH_V"+ver);
        if (entities.length > 0) {
            int index = 0;
            if (entities.length > 1) {
                play.Logger.warn(id + " yields " + entities.length
                        + " matches!");
                int highestrank = 0; // make which stitchnode is returned to be more deterministic
                for (int i = 0; i < entities.length; i++) {
                    Entity ent = entities[i];
                    Map props = ent.properties();
                    int rank = 0;
                    if (props.containsKey("rank"))
                        rank = (Integer) props.get("rank");
                    if (rank > highestrank) {
                        highestrank = rank;
                        index = i;
                    } else if (rank == highestrank && ent.getId() < entities[index].getId()) {
                        index = i;
                    }
                }
            }
            return entities[index];
        }
        return null;
    }

    public Entity entity (long id) {
        try (Transaction tx = gdb.beginTx()) {
            Entity ent = _entity (id);
            tx.success();
            return ent;
        }
    }

    /**
     * get Entity as-is
     */
    public Entity _getEntity (long id) {
        try {
            return Entity._getEntity(gdb.getNodeById(id));
        }
        catch (NotFoundException ex) {
            logger.warning("Unknown entity "+id);
        }
        return null;
    }

    public Entity getEntity (long id) {
         try (Transaction tx = gdb.beginTx()) {
             Entity e = _getEntity (id);
             tx.success();
             return e;
         }
    }

    Node _getEntityNode (long id) {
        try {
            Node n = gdb.getNodeById(id);
            if (n.hasLabel(AuxNodeType.DATA)) {
                // this is the payload, so return the corresponding entity
                Relationship rel = n.getSingleRelationship
                    (AuxRelType.PAYLOAD, Direction.BOTH);
                n = rel.getOtherNode(n);
            }
            return n;
        }
        catch (NotFoundException ex) {
            logger.warning("Unknown entity "+id);
        }
        return null;
    }
    
    /**
     * get Entity conceptually
     */
    public Entity _entity (long id) {
        Node n = _getEntityNode (id);
        return n != null ? Entity._getEntity(n) : null;
    }

    public Entity[] search (String query, int max) {
        Map<String, Object> result = new HashMap<>();
        search (result, query, 0, max);
        return (Entity[])result.get("entities");
    }

    public int search (Map<String, Object> out,
                       String query, int skip, int top) {
        int total = 0;
        try (Transaction tx = gdb.beginTx()) {
            TextIndexer.SearchResult result = indexer.search(query, skip, top);
            List<Entity> results = new ArrayList<>();
            Set<Long> unique = new HashSet<>();
            for (TextIndexer.Result r : result.matches) {
                Node u = r.node;
                Relationship rel = u.getSingleRelationship
                    (AuxRelType.PAYLOAD, Direction.OUTGOING);
                if (rel != null) {
                    Node v = rel.getOtherNode(u);
                    if (!unique.contains(v.getId())) {
                        Entity e = Entity._getEntity(v);
                        results.add(e);
                        unique.add(v.getId());
                    }
                }
            }
            //total = unique.size();
            total = result.total;
            out.put("total", total);
            out.put("entities", results.toArray(new Entity[0]));
            out.put("count", results.size());
            tx.success();
        }
        catch (Exception ex) {
            logger.log(Level.SEVERE, "Search \""+query+"\" failed!", ex);
        }
        return total;
    }
    
    static void dfs (Set<Long> nodes, Node n, StitchValue sv) {
        nodes.add(n.getId());
        for (Relationship rel :
                 n.getRelationships(Direction.BOTH, sv.getKey())) {
            Object relval = rel.getProperty(sv.getName(), null);
            if (sv != null && sv.getValue().equals(relval)) {
                Node xn = rel.getOtherNode(n);
                if (!nodes.contains(xn.getId()))
                    dfs (nodes, xn, sv);
            }
        }
    }

    public Map<StitchValue, long[]> expand (long id) {
        try (Transaction tx = gdb.beginTx()) {
            Map<StitchValue,long[]> ret = _expand (id);
            tx.success();
            return ret;
        }
    }
    
    public Map<StitchValue, long[]> _expand (long id) {
        Map<StitchValue, long[]> expander = new TreeMap<>();
        try {
            Node anchor = gdb.getNodeById(id);
            for (Relationship rel : anchor.getRelationships(Direction.BOTH)) {
                try {
                    StitchValue sv = new StitchValue
                        (StitchKey.valueOf(rel.getType().name()), VALUE,
                         rel.getProperty(VALUE, null));
                    Set<Long> nodes = new TreeSet<>();
                    dfs (nodes, anchor, sv);
                    expander.put(sv, Util.toArray(nodes));
                }
                catch (IllegalArgumentException ex) {
                    // not a StitchKey relationship
                }
            }
        }
        catch (NotFoundException ex) {
            logger.warning("Node not found: "+id);
        }
        
        return expander;
    }

    public long[] _expand (long id, StitchKey key, Object value) {
        Set<Long> nodes = new TreeSet<>();
        Node anchor = gdb.getNodeById(id);
        dfs (nodes, anchor, new StitchValue (key, VALUE, value));
        return Util.toArray(nodes);
    }

    public long[] expand (long id, StitchKey key, Object value) {
        try (Transaction tx = gdb.beginTx()) {
            long[] ret = _expand (id, key, value);
            tx.success();
            return ret;
        }
    }

    public long[] _neighbors (long id, StitchKey... keys) {
        Set<Long> nb = new TreeSet<>();
        try {
            if (keys == null || keys.length == 0)
                keys = Entity.KEYS;
            
            Node node = gdb.getNodeById(id);
            for (Relationship rel :
                     node.getRelationships(Direction.BOTH, keys)) {
                Node xn = rel.getOtherNode(node);
                nb.add(xn.getId());
            }
            
            if (!nb.isEmpty())
                nb.add(id);
            
            return Util.toArray(nb);
        }
        catch (NotFoundException ex) {
            logger.warning("Node not found: "+id);
        }
        return null;
    }

    public long[] neighbors (long id, StitchKey... keys) {
        try (Transaction tx = gdb.beginTx()) {
            long[] nb = _neighbors (id, keys);
            tx.success();
            return nb;
        }
    }
    
    /*
     * iterate over entities regardless of data source
     */
    public Iterator<Entity> entities () {
        try (Transaction tx = gdb.beginTx()) {
            Iterator<Entity> iter = new EntityIterator
                (gdb, gdb.findNodes(AuxNodeType.ENTITY));
            tx.success();
            return iter;
        }
    }

    protected void traversal (Node node, LinkedList<Node> path,
                              List<Node[]> paths, Set<Node> seen,
                              Direction dir, StitchKey... keys) {
        if (seen.contains(node))
            return;
        path.push(node);
        for (Relationship rel : node.getRelationships(dir, keys)) {
            Set sources = Util.toSet(rel.getProperty(SOURCE, null));
            Node xn = rel.getOtherNode(node);            
            if (sources.contains(node.getProperty(SOURCE))
                && sources.contains(xn.getProperty(SOURCE))
                && path.indexOf(xn) < 0) {
                traversal (xn, path, paths, seen, dir, keys);
            }
        }

        Node last = path.peek();
        if (!seen.contains(last)) {
            Set<Node> branches = new HashSet<>();
            for (Relationship rel : last.getRelationships(dir, keys)) {
                Set sources = Util.toSet(rel.getProperty(SOURCE, null));
                Node xn = rel.getOtherNode(last);
                if (sources.contains(last.getProperty(SOURCE))
                    && sources.contains(xn.getProperty(SOURCE)))
                    branches.add(xn);
            }
            
            if (!branches.isEmpty()) {
                //logger.info(last.getId()+" has branches "+branches);
                List<Node[]> newpaths = new ArrayList<>();
                for (Node[] p : paths) {
                    for (int i = 0; i < p.length && !branches.isEmpty(); ++i) {
                        if (branches.remove(p[i])) {
                            for (int j = i; j >= 0; --j)
                                path.push(p[j]);
                            newpaths.add(path.toArray(new Node[0]));
                            seen.addAll(path);
                            for (int j = i; j >= 0; --j)
                                path.pop();
                        }
                    }
                    
                    if (branches.isEmpty())
                        break;
                }
                paths.addAll(newpaths);
            }
            else {
                seen.addAll(path);                
                paths.add(path.toArray(new Node[0]));
            }
        }
        path.pop();
    }

    List<Entity[]> toEntityPath (List<Node[]> paths) {
        return paths.stream()
            .map(p -> Arrays.stream(p).map(n -> Entity.getEntity(n))
                 .collect(Collectors.toList()).toArray(new Entity[0]))
            .collect(Collectors.toList());
    }
    
    /*
     * return paths starting at the specified node
     */
    List<Node[]> paths (Node node, Direction dir, StitchKey... keys) {
        List<Node[]> paths = new ArrayList<>();
        traversal (node, new LinkedList<Node>(), paths,
                   new HashSet<Node>(), dir, keys);
        return paths;
    }
    
    List<Node[]> paths (long id, Direction dir, StitchKey... keys) {
        try {
            Node node = _getEntityNode (id);
            return paths (node, dir, keys);
        }
        catch (NotFoundException ex) {
            logger.warning("Node not found: "+id);
        }
        return Collections.emptyList();
    }
    
    public List<Entity[]> _parents (long id, StitchKey... keys) {
        return toEntityPath (paths (id, Direction.OUTGOING, keys));
    }

    public List<Entity[]> parents (long id, StitchKey... keys) {
        try (Transaction tx = gdb.beginTx()) {
            List<Entity[]> paths = _parents (id, keys);
            tx.success();
            return paths;
        }
    }

    public List<Entity[]> _children (long id, StitchKey... keys) {
        return toEntityPath (paths (id, Direction.INCOMING, keys));
    }

    public List<Entity[]> children (long id, StitchKey... keys) {
        try (Transaction tx = gdb.beginTx()) {
            List<Entity[]> paths = _children (id, keys);
            tx.success();
            return paths;
        }
    }

    /**
     * return a tree based containing the specified node by traversing
     * the directed stitch key provided
     */
    public EntityTree _tree (String name, StitchKey... keys) {
        EntityTree tree = null;
        try {
            long id = Long.parseLong(name);
            tree = _tree (_getEntityNode (id), keys);
        }
        catch (NumberFormatException ex) {
            try {
                TextIndexer.SearchResult result = indexer.search(name, 0, 5);
                if (result.total > 1) {
                    logger.warning("** "+name+" resolves to "+result.total
                                   +" nodes!");
                }
                
                Node node = null;
                for (TextIndexer.Result r : result.matches) {
                    Node n = r.node;
                    // find a property that matches the name
                    Map<String, Object> props = n.getAllProperties();
                    for (Map.Entry<String, Object> p : props.entrySet()) {
                        String key = p.getKey();
                        if (p.getValue().equals(name)
                            // sigh...
                            && ("id".equalsIgnoreCase(key)
                                || "notation".equalsIgnoreCase(key)
                                || "P207".equalsIgnoreCase(key))) {
                            return _tree (_getEntityNode (n.getId()), keys);
                        }
                    }
                    
                    if (node == null)
                        node = n;
                }
                
                // no definitive match
                if (node != null)
                    tree = _tree (_getEntityNode (node.getId()), keys);
            }
            catch (Exception exx) {
                logger.log(Level.SEVERE, "Can't execute search for "+name, exx);
            }
        }
        catch (NotFoundException ex) {
            logger.warning("Node not found: "+name);
        }
        return tree;
    }
    
    EntityTree _tree (Node node, StitchKey... keys) {
        EntityTree tree = null;
        List<Node[]> parents = paths (node, Direction.OUTGOING, keys);
        for (Node[] p : parents) {
            if (tree == null)
                tree = new EntityTree (p);
            else {
                tree.add(p);
            }
        }
        
        List<Node[]> children = paths (node, Direction.INCOMING, keys);
        for (Node[] p : children) {
            for (int i = 0, j = p.length-1; i < j; ++i, --j) {
                Node t = p[i];
                p[i] = p[j];
                p[j] = t;
            }
            
            if (tree == null)
                tree = new EntityTree (p);
            else {
                tree.add(p);
            }
        }
        return tree;
    }

    public EntityTree tree (String id, StitchKey... keys) {
        try (Transaction tx = gdb.beginTx()) {
            EntityTree tree = _tree (id, keys);
            tx.success();
            return tree;
        }
    }

    public void shutdown () {
        graphDb.shutdown();
    }

    public void close () throws Exception {
        shutdown ();
    }

    public void execute (Runnable r) {
        execute (r, true);
    }
    
    public void execute (Runnable r, boolean commit) {
        try (Transaction tx = gdb.beginTx()) {
            r.run();
            if (commit)
                tx.success();
        }
    }
 
    public <V> V execute (Callable<V> c) {
        return execute (c, true);
    }
    
    public <V> V execute (Callable<V> c, boolean commit) {
        V result = null;
        try (Transaction tx = gdb.beginTx()) {
            result = c.call();
            if (commit)
                tx.success();
        }
        catch (Exception ex) {
            logger.log(Level.SEVERE, "Can't execute callable", ex);
        }
        return result;  
    }

    public void cypher (Function<Map<String, Object>, Boolean> func,
                        String cypher) {
        try (Transaction tx = gdb.beginTx();
             Result result = gdb.execute(cypher)) {
            long rows = 0;
            while (result.hasNext()) {
                Map<String, Object> row = result.next();
                try {
                    if (!func.apply(row)) {
                        break;
                    }
                }
                catch (Throwable t) {
                    logger.log(Level.SEVERE,
                               "Exception encountered during transaction!", t);
                }
                if (++rows % 1000l == 0l)
                    tx.success();
            }
            tx.success();
        }
        catch (Exception ex) {
            logger.log(Level.SEVERE, "Can't execute cypher query: "+cypher, ex);
        }
    }

    public Stitch createStitch (DataSource source, Component component) {
        try (Transaction tx = gdb.beginTx()) {
            Stitch ent = _createStitch (source, component);
            tx.success();
            return ent;
        }
    }

    public Stitch createStitch (DataSource source, long[] component) {
        Component comp = new ComponentImpl (gdb, component);
        try (Transaction tx = gdb.beginTx()) {
            Stitch ent = _createStitch (source, comp);
            tx.success();
            return ent;
        }
    }
    
    public Stitch _createStitch (DataSource source, Component component) {
        final String key = source.getKey()+component.getId();
         Node stitch = getNode (ID, key, () -> {
                Node node = gdb.createNode(AuxNodeType.SGROUP,
                                           source.getLabel());
                node.setProperty(ID, key);
                node.setProperty(SOURCE, source._getKey());
                node.setProperty(RANK, component.size());
                Index<Node> index = 
                    gdb.index().forNodes(Entity.nodeIndexName());
                for (Entity e : component) {
                    Node n = e._node();
                    String s = (String)n.getProperty(SOURCE);
                    DataSource ds = dsf._getDataSourceByKey(s);
                    try {
                        // link to the source's payload
                        Relationship rel = null;
                        for (Relationship r : n.getRelationships
                                 (Direction.INCOMING, AuxRelType.PAYLOAD)) {
                            if (s.equals(r.getProperty(SOURCE))) {
                                rel = r;
                                break;
                            }
                        }
                        
                        if (rel != null) {
                            Node p = rel.getOtherNode(n);
                            
                            rel = node.createRelationshipTo
                                (p, AuxRelType.STITCH);
                            if (e.equals(component.root())) {
                                rel.setProperty(KIND, "PARENT");
                                node.setProperty(PARENT, p.getId());
                            }
                            
                            if (ds != null) {
                                String name =
                                    (String)ds._node().getProperty(NAME, null);
                                node.addLabel(Label.label(name));
                                rel.setProperty(SOURCE, name);
                            }
                            else {
                                logger.warning("Bogus data source ("
                                               +s+") referenced by node "
                                               +n.getId());
                            }
                            
                            // index all payload properties for this stitch
                            Util.index(index, node, p);
                        }
                        else {
                            logger.warning("Entity "+n.getId()+" doesn't have "
                                           +"matching payload!");
                        }
                    }
                    catch (Exception ex) {
                        logger.warning("Entity "+n.getId()
                                       +" has multiple payload: "
                                       +ex.getMessage());
                    }
                }
                
                Integer count = (Integer) source.get(INSTANCES);
                source.set(INSTANCES, count == null ? 1 : count+1);

                logger.info("## New stitch node "+node.getId()+" created: "
                            +component);
                return node;
            });

         return new Stitch (stitch);
    }

    protected Node getNode (String key, Object value, Supplier<Node> supplier) {
        Index<Node> index = gdb.index().forNodes(Entity.nodeIndexName());
        try (IndexHits<Node> hits = index.get(key, value)) {
            Node node = hits.getSingle();
            
            if (node == null) {
                node = supplier.get();
                Util.index(index, node, node);
            }
            
            return node;
        }
    }

    public Entity _createEntityIfAbsent
        (String key, Object value, Supplier<Node> supplier) {
        return Entity._getEntity(getNode (key, value, supplier));
    }
    
    // return the last k updated entities
    public Entity[] getLastUpdatedEntities (int k) {
        try (Transaction tx = gdb.beginTx()) {
            Entity[] ret = _getLastUpdatedEntities (k);
            tx.success();
            return ret;
        }
    }
    
    public Entity[] _getLastUpdatedEntities (int k) {
        IndexHits<Node> hits = timeline.getBetween
            (null, System.currentTimeMillis(), true);
        try {
            int max = Math.min(k, hits.size());
            Entity[] entities = new Entity[max];
            k = 0;
            for (Node n : hits) {
                try {
                    entities[k] = Entity._getEntity(n);
                    if (++k == entities.length)
                        break;
                }
                catch (IllegalArgumentException ex) {
                    // not an Entity node
                }
            }
            return entities;
        }
        finally {
            hits.close();
        }
    }

    public Entity getLastUpdatedEntity () {
        Entity[] ent = getLastUpdatedEntities (1);
        return ent != null && ent.length > 0 ? ent[0] : null;
    }

    public void export (OutputStream os, Long... components) {
        export (os, v -> v, e -> e, components);
    }

    public void export (OutputStream os, Function<String, String> vertex, 
                        Function<String, String> edge, Long... components) {
        PrintStream ps = new PrintStream (os);
        Set<Component> comps = new HashSet<>();

        // do a first pass to make sure we have unique components
        for (Long c : components)
            comps.add(component (c));

        // now count
        int nv = 0;
        Set<String> edges = new TreeSet<>();
        for (Component c : comps) {
            nv += c.size();
            c.stitches((a,b,v) -> {
                    if (a.getId() > b.getId()) {
                        Entity e = a;
                        a = b;
                        b = e;
                    }
                    String id = a.getId()+"."+b.getId();
                    edges.add(id);
                }, Entity.KEYS);
        }
        int ne = edges.size();
        ps.println(nv+" "+ne);

        // do vertex
        Set<Entity> vertices = new TreeSet<>();
        for (Component c : comps)
            for (Entity e : c.entities())
                vertices.add(e);

        for (Entity e: vertices) {
            ps.print(e.getId());
            for (String l : e.labels()) {
                String v = vertex.apply(l);
                if (v != null)
                    ps.print(" "+v);
            }
            ps.println();
        }

        // do edge
        for (Component c : comps) {
            c.stitches((a,b,s) -> {
                    if (a.getId() > b.getId()) {
                        Entity e = a;
                        a = b;
                        b = e;
                    }

                    String id = a.getId()+"."+b.getId();
                    if (edges.contains(id)) {
                        Set<String> labels = new TreeSet<>();
                        for (Map.Entry<StitchKey, Object> me : s.entrySet()) {
                            Object v = me.getValue();

                            String sv = edge.apply(me.getKey().name());
                            if (sv != null)
                                labels.add(sv);

                            if (v.getClass().isArray()) {
                                for (int i = 0; i < Array.getLength(v); ++i) {
                                    sv = edge.apply((String) Array.get(v, i));
                                    if (sv != null)
                                        labels.add(sv);
                                }
                            }
                            else {
                                sv = edge.apply((String)v);
                                if (sv != null)
                                    labels.add(sv);
                            }
                        }

                        ps.print(a.getId() +" "+b.getId());
                        for (String l : labels)
                            ps.print(" "+l);
                        ps.println();
                        edges.remove(id);
                    }
                }, Entity.KEYS);
        }
    }
}
