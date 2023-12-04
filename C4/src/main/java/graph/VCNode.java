package graph;

import history.Transaction;
import util.VectorClock;

public class VCNode<VarType, ValType> extends Node<VarType, ValType>{
    private final VectorClock clock;
    private VectorClock clockVO;

    public VCNode(Graph<VarType, ValType> graph, Transaction<VarType, ValType> transaction, short tid, int dim, Node<VarType, ValType> prev) {
        super(graph, transaction);
        this.clock = new VectorClock(tid, dim);
        if (prev != null) {
            this.clock.join(((VCNode<VarType, ValType>) prev).clock);
        }
        this.clock.incrementBy(1);
    }
    @Override
    public boolean canReachByCO(Node<VarType, ValType> other) {
        if (!(other instanceof VCNode)) {
            throw new RuntimeException("Type mismatch");
        }
        return this.clock.isLessThanOrEqual(((VCNode<VarType, ValType>) other).clock);
    }

    @Override
    public boolean canReachByAO(Node<VarType, ValType> other) {
        if (!(other instanceof VCNode)) {
            throw new RuntimeException("Type mismatch");
        }
        return this.clockVO.isLessThanOrEqual(((VCNode<VarType, ValType>) other).clockVO);    }

    @Override
    public void updateCOReachability(Node<VarType, ValType> other) {
        if (!(other instanceof VCNode)) {
            throw new RuntimeException("Type mismatch");
        }
        this.clock.join(((VCNode<VarType, ValType>) other).clock);
    }

    @Override
    public void updateAOReachability(Node<VarType, ValType> other) {
        if (!(other instanceof VCNode)) {
            throw new RuntimeException("Type mismatch");
        }
        this.clockVO.join(((VCNode<VarType, ValType>) other).clockVO);
    }

    @Override
    public void syncCOAO() {
        clockVO = new VectorClock(clock);
    }
}
