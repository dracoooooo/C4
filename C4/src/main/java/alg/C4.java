package alg;

import badPattern.BadPatternType;
import graph.*;
import guru.nidi.graphviz.engine.Format;
import history.History;
import history.Operation;
import history.Transaction;
import javafx.util.Pair;
import lombok.Data;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static guru.nidi.graphviz.engine.Graphviz.fromGraph;
import static guru.nidi.graphviz.model.Factory.graph;
import static guru.nidi.graphviz.model.Factory.node;

@Data
public class C4<VarType, ValType> {
    private final AlgType type;

    private final History<VarType, ValType> history;

    private final IsolationLevel isolationLevel;

    private final Set<BadPatternType> badPatterns = new HashSet<>();
    private final Map<String, Integer> badPatternCount = new HashMap<>();
    private final Graph<VarType, ValType> graph = new Graph<>();

    private final Map<Pair<VarType, ValType>, Operation<VarType, ValType>> writes = new HashMap<>();
    private final Map<Pair<VarType, ValType>, List<Operation<VarType, ValType>>> reads = new HashMap<>();
    private final Map<Pair<VarType, ValType>, List<Operation<VarType, ValType>>> readsWithoutWrites = new HashMap<>();
    private final Map<VarType, Set<Node<VarType, ValType>>> writeNodes = new HashMap<>();
    private final Map<VarType, Set<Pair<Node<VarType, ValType>, Node<VarType, ValType>>>> WREdges = new HashMap<>();
    private final Map<Operation<VarType, ValType>, Node<VarType, ValType>> op2node = new HashMap<>();
    private final Set<Operation<VarType, ValType>> internalWrites = new HashSet<>();

    private static final Long ZERO = 0L;

    public void validate() {
        buildCO();
        checkCOBP();
        if (isolationLevel == IsolationLevel.RC) {
            System.out.println(badPatternCount);
            return;
        }
        syncClock();
        buildVO();
        if (!hasCircle(Edge.Type.VO)) {
            System.out.println(badPatternCount);
            return;
        }
        checkVOBP();
        System.out.println(badPatternCount);
    }

    private void buildCO() {
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
                            findBadPattern(BadPatternType.NonRepeatableRead);
                        } else {
                            findBadPattern(BadPatternType.NotMyOwnWrite);
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
                        }
                    } else if (op.getValue().equals(ZERO)) {
                        // if no write -> op, but op reads zero
                        reads.computeIfAbsent(key, k -> new ArrayList<>()).add(op);
                    } else {
                        readsWithoutWrites.computeIfAbsent(key, k -> new ArrayList<>()).add(op);
                    }
                } else {
                    // if op is a write
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
                            }
                        }
                    }
                    readsWithoutWrites.remove(key);
                }
            }
            updateVec(new HashSet<>(), node, node, Edge.Type.CO);
        }
    }

    private void checkCOBP() {
        // check aborted read and thin air
        if (readsWithoutWrites.size() > 0) {
            AtomicInteger count = new AtomicInteger();
            readsWithoutWrites.keySet().forEach((key) -> {
                if (history.getAbortedWrites().contains(key)) {
                    // find aborted read
                    findBadPattern(BadPatternType.AbortedRead);
                    count.addAndGet(1);
                }
            });
            if (count.get() != readsWithoutWrites.size()) {
                // find thin air read
                findBadPattern(BadPatternType.ThinAirRead);
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

                    // check InitialRead if check TCC
                    if (isolationLevel == IsolationLevel.TCC) {
                        // check if write(x, k) co-> read
                        writeRelNodes.forEach((writeNode) -> {
                            if (writeNode.equals(node)) {
                                return;
                            }
                            if (writeNode.canReachByCO(node)) {
                                // find writeCOInitRead
                                findBadPattern(BadPatternType.WriteCOInitRead);
                            }
                        });
                    }
                    return;
                }

                // write wr-> read
                var write = writes.get(key);
                var writeNode = op2node.get(write);

                if (!writeNode.equals(node)) {
                    // in different txn
                    if (internalWrites.contains(write)) {
                        // find intermediate write
                        findBadPattern(BadPatternType.IntermediateRead);
                    }
                } else {
                    // in same txn
                    if (write.getId() > read.getId()) {
                        // find future read
                        findBadPattern(BadPatternType.FutureRead);
                    }
                }
            });
        });

        // check CyclicCO if check TCC
        if (isolationLevel == IsolationLevel.TCC) {
            // iter wr edge (t1 wr-> t2)
            WREdges.forEach((varX, edgesX) -> {
                edgesX.forEach((edge) -> {
                    var t1 = edge.getKey();
                    var t2 = edge.getValue();
                    if (t1.canReachByCO(t2) && t2.canReachByCO(t1)) {
                        // find cyclicCO
                        findBadPattern(BadPatternType.CyclicCO);
                        print2TxnBp(t1.getTransaction(), t2.getTransaction());
                    }
                });
            });
        }
    }

    private void buildVO() {
        var pendingNodes = new HashSet<Node<VarType, ValType>>();

        WREdges.forEach((variable, edges) -> {
            edges.forEach((edge) -> {
                var t1 = edge.getKey();
                var t2 = edge.getValue();
                writeNodes.get(variable).forEach((t) -> {
                    if (!t.equals(t1) && !(t.equals(t2)) && t.canReachByCO(t2)) {
                        // build vo edge
                        if (t.canReachByCO(t1)) {
                            return;
                        }
                        graph.addEdge(t, t1, new Edge<>(Edge.Type.VO, null));
                        pendingNodes.add(t);
                    }
                });
            });
        });

        // update downstream nodes
        pendingNodes.forEach((node) -> {
            updateVec(new HashSet<>(), node, node, Edge.Type.VO);
        });
    }

    private void checkVOBP() {
        // iter wr edge (t2 wr-> t3)
        WREdges.forEach((varX, edgesX) -> {
            edgesX.forEach((edge) -> {
                var t2 = edge.getKey();
                var t3 = edge.getValue();

                writeNodes.get(varX).forEach((t1) -> {
                    if (!t1.equals(t2) && !t1.equals(t3) && t1.canReachByCO(t3) && t2.canReachByCO(t1)) {
                        // find bp triangle
                        AtomicBoolean isRA = new AtomicBoolean(false);
                        WREdges.forEach((varY, edgesY) -> {
                            if (!varX.equals(varY) && edgesY.contains(new Pair<>(t1, t3))) {
                                isRA.set(true);
                                // find fractured read co
                                findBadPattern(BadPatternType.FracturedReadCO);
                                print3TxnBp(t2.getTransaction(), t1.getTransaction(), t3.getTransaction());
                            }
                        });
                        // continue if is RA bp
                        if (isRA.get()) {
                            return;
                        }
                        // check COConflictVO if check TCC, continue if check RA
                        if (isolationLevel == IsolationLevel.TCC) {
                            // find co conflict vo
                            findBadPattern(BadPatternType.COConflictVO);
                            print3TxnBp(t2.getTransaction(), t1.getTransaction(), t3.getTransaction());
                        }
                    }
                });
            });
        });

        // iter wr edge (t2 wr-> t3)
        WREdges.forEach((varX, edgesX) -> {
            edgesX.forEach((edge) -> {
                var t2 = edge.getKey();
                var t3 = edge.getValue();

                writeNodes.get(varX).forEach((t1) -> {
                    if (!t1.equals(t2) && !t1.equals(t3) && t1.canReachByCO(t3) && !t2.canReachByCO(t1) && t2.canReachByVO(t1)) {
                        // find bp triangle
                        AtomicBoolean isRA = new AtomicBoolean(false);
                        WREdges.forEach((varY, edgesY) -> {
                            if (!varX.equals(varY) && edgesY.contains(new Pair<>(t1, t3))) {
                                isRA.set(true);
                                // find fractured read vo
                                findBadPattern(BadPatternType.FracturedReadVO);
                                print3TxnBp(t2.getTransaction(), t1.getTransaction(), t3.getTransaction());
                            }
                        });
                        // continue if is RA bp
                        if (isRA.get()) {
                            return;
                        }
                        // check ConflictVO if check TCC, continue if check RA
                        if (isolationLevel == IsolationLevel.TCC) {
                            // find conflict vo
                            findBadPattern(BadPatternType.ConflictVO);
                            print3TxnBp(t2.getTransaction(), t1.getTransaction(), t3.getTransaction());
                        }
                    }
                });
            });
        });
    }

    private void updateVec(Set<Node<VarType, ValType>> visited, Node<VarType, ValType> cur, Node<VarType, ValType> upNode, Edge.Type edgeType) {
        visited.add(cur);

        var nextNodes = graph.get(cur);
        for (var next: nextNodes) {
            if (edgeType == Edge.Type.CO) {
                if (visited.contains(next) || upNode.canReachByCO(next)) {
                    continue;
                }
                next.updateCOReachability(upNode);
                updateVec(visited, next, upNode, edgeType);
            } else if (edgeType == Edge.Type.VO) {
                if (visited.contains(next) || upNode.canReachByVO(next)) {
                    continue;
                }
                next.updateVOReachability(upNode);
                updateVec(visited, next, upNode, edgeType);
            }
        }
    }

    private void findBadPattern(BadPatternType badPattern) {
//        if (badPatterns.contains(badPattern)) {
//            return;
//        }
//        System.err.printf("Find Bad Pattern: %s %s\n", badPattern.getCode(), badPattern.name());
        badPatterns.add(badPattern);
        badPatternCount.merge(badPattern.getCode(), 1, Integer::sum);
    }

    private Node<VarType, ValType> constructNode(Transaction<VarType, ValType> transaction, Node<VarType, ValType> prev) {
        short tid = (short) transaction.getSession().getId();
        int dim = history.getSessionSize();
        switch (type) {
            case C4:
                return new TCNode<>(graph, transaction, tid, dim, prev);
            case C4_WITHOUT_TC:
                return new VCNode<>(graph, transaction, tid, dim, prev);
            case C4_WITHOUT_VEC:
                return new NormalNode<>(graph, transaction);
            default:
                throw new RuntimeException();
        }
    }

    private void syncClock() {
        graph.getAdjMap().keySet().forEach(Node::syncCOVO);
    }

//    private boolean hasCircle(Edge.Type edgeType) {
//        return WREdges.values().stream().anyMatch((pairs) -> pairs.stream().anyMatch((pair) -> {
//            var from = pair.getKey();
//            var to = pair.getValue();
//            return (edgeType == Edge.Type.CO && to.canReachByCO(from)) ||
//                    (edgeType == Edge.Type.VO && to.canReachByVO(from));
//        }));
//    }
    private boolean hasCircle(Edge.Type edgeType) {
        return graph.getAdjMap().entrySet().stream().anyMatch((entry) -> {
            var from = entry.getKey();
            var toNodes = entry.getValue();
            return toNodes.stream().anyMatch((node) -> (edgeType == Edge.Type.CO && node.canReachByCO(from)) ||
                    (edgeType == Edge.Type.VO && node.canReachByVO(from)));
        });
    }

    private void print3TxnBp(Transaction<VarType, ValType> t1, Transaction<VarType, ValType> t2, Transaction<VarType, ValType> t3) {
        var node1 = node("t" + t1.getId());
        var node2 = node("t" + t2.getId());
        var node3 = node("t" + t3.getId());
        var g = graph().directed()
                .linkAttr().with("class", "link-class")
                .with(
                        node1.link(node2),
                        node2.link(node3),
                        node2.link(node1),
                        node1.link(node3)
                );
        try {
            fromGraph(g).height(500).render(Format.PNG).toFile(new File(String.format("graphviz/%s-%s-%s.png", t1.getId(), t2.getId(), t3.getId())));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void print2TxnBp(Transaction<VarType, ValType> t1, Transaction<VarType, ValType> t2) {
        var node1 = node("t" + t1.getId());
        var node2 = node("t" + t2.getId());
        var g = graph().directed()
                .linkAttr().with("class", "link-class")
                .with(
                        node1.link(node2),
                        node2.link(node1)
                );
        try {
            fromGraph(g).height(500).render(Format.PNG).toFile(new File(String.format("graphviz/%s-%s.png", t1.getId(), t2.getId())));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
