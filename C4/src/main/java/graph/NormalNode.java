package graph;

import history.Transaction;

import java.util.HashSet;
import java.util.Set;


public class NormalNode<VarType, ValType> extends Node<VarType, ValType>{
    public NormalNode(Graph<VarType, ValType> graph, Transaction<VarType, ValType> transaction) {
        super(graph, transaction);
    }

    private boolean canReachByDFS(Set<Node<VarType, ValType>> reachable, Node<VarType, ValType> cur, Node<VarType, ValType> target) {
        if (cur.equals(target)) {
            return true;
        }
        reachable.add(cur);
        for (var next : getGraph().get(cur)) {
            if (reachable.contains(next)) {
                continue;
            }
            if (canReachByDFS(reachable, next, target)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean canReachByCO(Node<VarType, ValType> other) {
        return canReachByDFS(new HashSet<>(), this, other);
    }

    @Override
    public boolean canReachByAO(Node<VarType, ValType> other) {
        return canReachByDFS(new HashSet<>(), this, other);
    }

    @Override
    public void updateCOReachability(Node<VarType, ValType> other) {

    }

    @Override
    public void updateAOReachability(Node<VarType, ValType> other) {

    }

    @Override
    public void syncCOAO() {

    }
}
