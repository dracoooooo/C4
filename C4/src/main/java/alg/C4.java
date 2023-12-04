package alg;

import taps.TAP;
import graph.*;
import history.History;
import history.Operation;
import history.Transaction;
import javafx.util.Pair;
import lombok.Data;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static guru.nidi.graphviz.engine.Graphviz.fromGraph;
import static guru.nidi.graphviz.model.Factory.graph;
import static guru.nidi.graphviz.model.Factory.node;

@Data
public class C4<VarType, ValType> {
    protected final AlgType type;
    protected final History<VarType, ValType> history;
    protected final IsolationLevel isolationLevel;

    protected final Set<TAP> badPatterns = new HashSet<>();
    protected final Map<String, Integer> badPatternCount = new HashMap<>();
    protected final Graph<VarType, ValType> graph = new Graph<>();

    protected final Map<Pair<VarType, ValType>, Operation<VarType, ValType>> writes = new HashMap<>();
    protected final Map<Pair<VarType, ValType>, List<Operation<VarType, ValType>>> reads = new HashMap<>();
    protected final Map<Pair<VarType, ValType>, List<Operation<VarType, ValType>>> readsWithoutWrites = new HashMap<>();
    protected final Map<VarType, Set<Node<VarType, ValType>>> writeNodes = new HashMap<>();
    protected final Map<VarType, Set<Pair<Node<VarType, ValType>, Node<VarType, ValType>>>> WREdges = new HashMap<>();
    protected final Map<Pair<Node<VarType, ValType>, Node<VarType, ValType>>, List<Pair<Operation<VarType, ValType>, Operation<VarType, ValType>>>> WRNodesToOp = new HashMap<>();
    protected final Map<Operation<VarType, ValType>, Node<VarType, ValType>> op2node = new HashMap<>();
    protected final Set<Operation<VarType, ValType>> internalWrites = new HashSet<>();

    protected Object ZERO = 0L;
    protected static final Map<IsolationLevel, Set<TAP>> PROHIBITED_TAPS = new HashMap<>();
    static {
        Set<TAP> RCTAPs = new HashSet<>(List.of(new TAP[]{
                TAP.ThinAirRead,
                TAP.AbortedRead,
                TAP.FutureRead,
                TAP.NotMyOwnWrite,
                TAP.NotMyLastWrite,
                TAP.IntermediateRead,
                TAP.CyclicCO,
                TAP.NonMonoReadCO,
                TAP.NonMonoReadAO,
        }));
        Set<TAP> RATAPs = new HashSet<>(RCTAPs);
        RATAPs.addAll(List.of(new TAP[]{
                TAP.NonRepeatableRead,
                TAP.FracturedReadCO,
                TAP.FracturedReadAO
        }));
        Set<TAP> TCCTAPs = new HashSet<>(RATAPs);
        TCCTAPs.addAll(List.of(new TAP[]{
                TAP.COConflictAO,
                TAP.ConflictAO
        }));
        PROHIBITED_TAPS.put(IsolationLevel.RC, RCTAPs);
        PROHIBITED_TAPS.put(IsolationLevel.RA, RATAPs);
        PROHIBITED_TAPS.put(IsolationLevel.TCC, TCCTAPs);
    }


    public void validate() {
        buildCO();
        checkCOTAP();
        if (isolationLevel == IsolationLevel.RC) {
            System.out.println(badPatternCount);
            return;
        }
        syncClock();
        buildAO();
        if (!hasCircle(Edge.Type.AO)) {
            System.out.println(badPatternCount);
            return;
        }
        checkAOTAP();
        System.out.println(badPatternCount);
    }

    protected void buildCO() {
        var hist = history.getFlatTransactions();
        Map<Long, Node<VarType, ValType>> prevNodes = new HashMap<>();

        for (var txn: hist) {

            // update node with prev node
            var prev = prevNodes.get(txn.getSession().getId());
            var node = constructNode(txn, prev);
            graph.addVertex(node);
            prevNodes.put(txn.getSession().getId(), node);
            if (prev != null) {
                graph.addEdge(prev, node, new Edge<>(Edge.Type.SO, null));
            }

            var nearestRW = new HashMap<VarType, Operation<VarType, ValType>>();
            var writesInTxn = new HashMap<VarType, Operation<VarType, ValType>>();

            for (var op: txn.getOps()) {
                var key = new Pair<>(op.getVariable(), op.getValue());
                op2node.put(op, node);

                // if op is a read
                if (op.getType() == Operation.Type.READ) {

                    // check NonRepeatableRead and NotMyOwnWrite
                    var prevRW = nearestRW.get(op.getVariable());
                    if (prevRW != null && !op.getValue().equals(prevRW.getValue())) {
                        if (prevRW.getType() == Operation.Type.READ) {
                            findTAP(TAP.NonRepeatableRead);
                        } else {
                            boolean findNotMyLastWrite = false;
                            for (var prevOp: txn.getOps()) {
                                if (prevOp.getId() < prevRW.getId() &&
                                prevOp.getType() == Operation.Type.WRITE &&
                                prevOp.getVariable().equals(op.getVariable()) &&
                                prevOp.getValue().equals(op.getValue())
                                ) {
                                    findNotMyLastWrite = true;
                                    findTAP(TAP.NotMyLastWrite);
                                }
                            }
                            if (!findNotMyLastWrite) {
                                findTAP(TAP.NotMyOwnWrite);
                            }
                        }
                    }
                    nearestRW.put(op.getVariable(), op);

                    var write = writes.get(key);
                    if (write != null) {
                        // if write -> op
                        // add op to reads
                        reads.computeIfAbsent(key, k -> new ArrayList<>()).add(op);

                        var writeNode = op2node.get(write);
                        if (!writeNode.equals(node)) {
                            if (!writeNode.canReachByCO(node)) {
                                node.updateCOReachability(writeNode);
                                graph.addEdge(writeNode, node, new Edge<>(Edge.Type.WR, op.getVariable()));
                            }
                            WREdges.computeIfAbsent(op.getVariable(), k -> new HashSet<>()).add(new Pair<>(writeNode, node));
                            WRNodesToOp.computeIfAbsent(new Pair<>(writeNode, node), wr -> new ArrayList<>()).add(new Pair<>(write, op));
                        }
                    } else if (op.getValue().equals(ZERO)) {
                        // if no write -> op, but op reads zero
                        reads.computeIfAbsent(key, k -> new ArrayList<>()).add(op);
                    } else {
                        readsWithoutWrites.computeIfAbsent(key, k -> new ArrayList<>()).add(op);
                    }
                } else {
                    // if op is a write
                    if (op.getValue().equals(ZERO)) {
                        // ignore write 0
                        continue;
                    }
                    writes.put(key, op);
                    writeNodes.computeIfAbsent(op.getVariable(), k -> new HashSet<>()).add(node);

                    nearestRW.put(op.getVariable(), op);

                    // check internal write
                    var internalWrite = writesInTxn.get(op.getVariable());
                    if (internalWrite != null) {
                        internalWrites.add(internalWrite);
                    }
                    writesInTxn.put(op.getVariable(), op);

                    var pendingReads = readsWithoutWrites.get(key);
                    if (pendingReads != null) {
                        reads.computeIfAbsent(key, k -> new ArrayList<>()).addAll(pendingReads);
                        for (var pendingRead: pendingReads) {
                            var pendingReadNode = op2node.get(pendingRead);
                            if (!node.equals(pendingReadNode)) {
                                graph.addEdge(node, pendingReadNode, new Edge<>(Edge.Type.WR, op.getVariable()));
                                WREdges.computeIfAbsent(op.getVariable(), k -> new HashSet<>()).add(new Pair<>(node, pendingReadNode));
                                WRNodesToOp.computeIfAbsent(new Pair<>(node, pendingReadNode), wr -> new ArrayList<>()).add(new Pair<>(op, pendingRead));
                            }
                        }
                    }
                    readsWithoutWrites.remove(key);
                }
            }
            updateVec(new HashSet<>(), node, node, Edge.Type.CO);
        }
    }

    protected void checkCOTAP() {
        // check aborted read and thin air
        if (readsWithoutWrites.size() > 0) {
            AtomicInteger count = new AtomicInteger();
            readsWithoutWrites.keySet().forEach((key) -> {
                if (history.getAbortedWrites().contains(key)) {
                    // find aborted read
                    findTAP(TAP.AbortedRead);
                    count.addAndGet(1);
                }
            });
            if (count.get() != readsWithoutWrites.size()) {
                // find thin air read
                findTAP(TAP.ThinAirRead);
            }
        }

        // for each read
        reads.values().forEach((readList) -> {
            readList.forEach((read) -> {
                var key = new Pair<>(read.getVariable(), read.getValue());
                var node = op2node.get(read);

                // read(x, 0)
                if (read.getValue().equals(ZERO)) {
                    var writeRelNodes = writeNodes.get(read.getVariable());

                    // no write(x, k)
                    if (writeRelNodes == null) {
                        return;
                    }

                    // check if write(x, k) co-> read
                    writeRelNodes.forEach((writeNode) -> {
                        if (writeNode.equals(node)) {
                            return;
                        }
                        if (writeNode.canReachByCO(node)) {
                            // there are 3 cases: initReadMono initReadWR or writeCOInitRead
                            boolean findSubTap = false;
                            for (var writeY : writeNode.getTransaction().getOps()) {
                                for (var readY : node.getTransaction().getOps()) {
                                    if (!writeY.getVariable().equals(read.getVariable()) &&
                                            writeY.getType().equals(Operation.Type.WRITE) &&
                                            readY.getType().equals(Operation.Type.READ) &&
                                            writeY.getVariable().equals(readY.getVariable()) &&
                                            writeY.getValue().equals(readY.getValue())) {
                                        // find w(y, v_y) wr-> r(y, v_y)
                                        findSubTap = true;
                                        if (readY.getId() < read.getId()) {
                                            // find nonMonoReadCO  if read y precedes read x
//                                            findTAP(TAP.InitReadMono);
                                            findTAP(TAP.NonMonoReadCO);
                                        } else {
                                            // find initReadWR
//                                            findTAP(TAP.InitReadWR);
                                            findTAP(TAP.FracturedReadCO);
                                        }
                                    }
                                }
                            }
                            if (!findSubTap) {
                                // find initReadCO if not InitReadMono or InitReadWR
//                                findTAP(TAP.InitReadCO);
                                findTAP(TAP.COConflictAO);
                            }
                        }
                    });
                    return;
                }

                // write wr-> read
                var write = writes.get(key);
                var writeNode = op2node.get(write);

                if (!writeNode.equals(node)) {
                    // in different txn
                    if (internalWrites.contains(write)) {
                        // find intermediate write
                        findTAP(TAP.IntermediateRead);
                    }
                } else {
                    // in same txn
                    if (write.getId() > read.getId()) {
                        // find future read
                        findTAP(TAP.FutureRead);
                    }
                }
            });
        });

        // check CyclicCO
        // iter wr edge (t1 wr-> t2)
        WREdges.forEach((varX, edgesX) -> {
            edgesX.forEach((edge) -> {
                var t1 = edge.getKey();
                var t2 = edge.getValue();
                if (t1.canReachByCO(t2) && t2.canReachByCO(t1)) {
                    // find cyclicCO
                    findTAP(TAP.CyclicCO);
                    print2TxnBp(t1.getTransaction(), t2.getTransaction());
                }
            });
        });
    }

    protected void buildAO() {
        var pendingNodes = new HashSet<Node<VarType, ValType>>();

        WREdges.forEach((variable, edges) -> {
            edges.forEach((edge) -> {
                var t1 = edge.getKey();
                var t2 = edge.getValue();
                writeNodes.get(variable).forEach((t) -> {
                    if (!t.equals(t1) && !(t.equals(t2)) && t.canReachByCO(t2)) {
                        // build ao edge
                        if (t.canReachByCO(t1)) {
                            return;
                        }
                        graph.addEdge(t, t1, new Edge<>(Edge.Type.AO, null));
                        pendingNodes.add(t);
                    }
                });
            });
        });

        // update downstream nodes
        pendingNodes.forEach((node) -> {
            updateVec(new HashSet<>(), node, node, Edge.Type.AO);
        });
    }

    protected void checkAOTAP() {
        // iter wr edge (t1 wr-> t3)
        WRNodesToOp.forEach((WRNodePair, WROpPairList) -> {
            var t1 = WRNodePair.getKey();
            var t3 = WRNodePair.getValue();
            WROpPairList.forEach((WROpPair) -> {
                var varX = WROpPair.getKey().getVariable();
                writeNodes.get(varX).forEach((t2) -> {
                    if (!t2.equals(t1) && !t2.equals(t3) && t2.canReachByCO(t3) && t1.canReachByCO(t2)) {
                        // find tap triangle
                        boolean findSubTAP = false;
                        if (WRNodesToOp.containsKey(new Pair<>(t2, t3))) {
                            findSubTAP = true;
                            var WRYOpPairList = WRNodesToOp.get(new Pair<>(t2, t3));
                            for (var WRYOpPair : WRYOpPairList) {
                                var readY = WRYOpPair.getValue();
                                var varY = readY.getVariable();
                                if (varY == varX) {
                                    continue;
                                }
                                if (readY.getId() < WROpPair.getValue().getId()) {
                                    // find NonMonoReadCO
                                    findTAP(TAP.NonMonoReadCO);
                                } else {
                                    // find FracturedReadCO
                                    findTAP(TAP.FracturedReadCO);
                                }
                            }
                        }
                        if (!findSubTAP) {
                            // find COConflictAO
                            findTAP(TAP.COConflictAO);
                        }
                    }
                    if (!t2.equals(t1) && !t2.equals(t3) && t2.canReachByCO(t3) && !t1.canReachByCO(t2) && t1.canReachByAO(t2)) {
                        // find tap triangle
                        boolean findSubTAP = false;
                        if (WRNodesToOp.containsKey(new Pair<>(t2, t3))) {
                            findSubTAP = true;
                            var WRYOpPairList = WRNodesToOp.get(new Pair<>(t2, t3));
                            for (var WRYOpPair : WRYOpPairList) {
                                var readY = WRYOpPair.getValue();
                                if (readY.getId() < WROpPair.getValue().getId()) {
                                    // find NonMonoReadAO
                                    findTAP(TAP.NonMonoReadAO);
                                } else {
                                    // find FracturedReadAO
                                    findTAP(TAP.FracturedReadAO);
                                }
                            }
                        }
                        if (!findSubTAP) {
                            // find ConflictAO
                            findTAP(TAP.ConflictAO);
                        }
                    }
                });
            });
        });
    }

    protected void updateVec(Set<Node<VarType, ValType>> visited, Node<VarType, ValType> cur, Node<VarType, ValType> upNode, Edge.Type edgeType) {
        visited.add(cur);

        var nextNodes = graph.get(cur);
        for (var next: nextNodes) {
            if (edgeType == Edge.Type.CO) {
                if (visited.contains(next) || upNode.canReachByCO(next)) {
                    continue;
                }
                next.updateCOReachability(upNode);
                updateVec(visited, next, upNode, edgeType);
            } else if (edgeType == Edge.Type.AO) {
                if (visited.contains(next) || upNode.canReachByAO(next)) {
                    continue;
                }
                next.updateAOReachability(upNode);
                updateVec(visited, next, upNode, edgeType);
            }
        }
    }

    protected void findTAP(TAP tap) {
        if (PROHIBITED_TAPS.get(isolationLevel).contains(tap)) {
            badPatterns.add(tap);
            badPatternCount.merge(tap.getCode(), 1, Integer::sum);
        }
    }

    protected Node<VarType, ValType> constructNode(Transaction<VarType, ValType> transaction, Node<VarType, ValType> prev) {
        short tid = (short) transaction.getSession().getId();
        int dim = history.getSessionSize();
        switch (type) {
            case C4:
            case C4_LIST:
                return new TCNode<>(graph, transaction, tid, dim, prev);
            case C4_WITHOUT_TC:
                return new VCNode<>(graph, transaction, tid, dim, prev);
            case C4_WITHOUT_VEC:
                return new NormalNode<>(graph, transaction);
            default:
                throw new RuntimeException();
        }
    }

    protected void syncClock() {
        graph.getAdjMap().keySet().forEach(Node::syncCOAO);
    }

    protected boolean hasCircle(Edge.Type edgeType) {
        return graph.getAdjMap().entrySet().stream().anyMatch((entry) -> {
            var from = entry.getKey();
            var toNodes = entry.getValue();
            return toNodes.stream().anyMatch((node) -> (edgeType == Edge.Type.CO && node.canReachByCO(from)) ||
                    (edgeType == Edge.Type.AO && node.canReachByAO(from)));
        });
    }

    protected void print3TxnBp(Transaction<VarType, ValType> t1, Transaction<VarType, ValType> t2, Transaction<VarType, ValType> t3) {
        return;
//        var node1 = node("t" + t1.getId());
//        var node2 = node("t" + t2.getId());
//        var node3 = node("t" + t3.getId());
//        var g = graph().directed()
//                .linkAttr().with("class", "link-class")
//                .with(
//                        node1.link(node2),
//                        node2.link(node3),
//                        node2.link(node1),
//                        node1.link(node3)
//                );
//        try {
//            fromGraph(g).height(500).render(Format.PNG).toFile(new File(String.format("graphviz/%s-%s-%s.png", t1.getId(), t2.getId(), t3.getId())));
//        } catch (IOException e) {
//            throw new RuntimeException(e);
//        }
    }

    protected void print2TxnBp(Transaction<VarType, ValType> t1, Transaction<VarType, ValType> t2) {
        return;
//        var node1 = node("t" + t1.getId());
//        var node2 = node("t" + t2.getId());
//        var g = graph().directed()
//                .linkAttr().with("class", "link-class")
//                .with(
//                        node1.link(node2),
//                        node2.link(node1)
//                );
//        try {
//            fromGraph(g).height(500).render(Format.PNG).toFile(new File(String.format("graphviz/%s-%s.png", t1.getId(), t2.getId())));
//        } catch (IOException e) {
//            throw new RuntimeException(e);
//        }
    }
}
