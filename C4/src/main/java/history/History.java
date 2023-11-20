package history;

import javafx.util.Pair;
import lombok.Data;

import java.util.*;
import java.util.stream.Collectors;

@Data
public class History<VarType, ValType> {
    private final Map<Long, Session<VarType, ValType>> sessions = new HashMap<>();
    private final Map<Long, Transaction<VarType, ValType>> transactions = new HashMap<>();

    private final Set<Pair<VarType, ValType>> abortedWrites = new HashSet<>();

    private int sessionSize;

    public Session<VarType, ValType> getSession(long id) {
        return sessions.get(id);
    }

    public Transaction<VarType, ValType> getTransaction(long id) {
        return transactions.get(id);
    }

    public Session<VarType, ValType> addSession(long id) {
        var session = new Session<VarType, ValType>(id);
        sessions.put(id, session);
        return session;
    }

    public Transaction<VarType, ValType> addTransaction(Session<VarType, ValType> session, long id) {
        var txn = new Transaction<>(id, session);
        transactions.put(id, txn);
        session.getTransactions().add(txn);
        return txn;
    }

    public Operation<VarType, ValType> addOperation(Transaction<VarType, ValType> transaction, Operation.Type type, VarType variable, ValType value) {
        var operation = new Operation<>(type, variable, value, transaction, transaction.getOps().size());
        transaction.getOps().add(operation);
        return operation;
    }

    public void addAbortedWrite(VarType variable, ValType value) {
        abortedWrites.add(new Pair<>(variable, value));
    }

    public List<Operation<VarType, ValType>> getOperations() {
        return transactions.values().stream().flatMap(txn -> txn.ops.stream()).collect(Collectors.toList());
    }

    public List<Transaction<VarType, ValType>> getFlatTransactions() {
        int maxLength = sessions.values().stream()
                .map(Session::getTransactions)
                .map(List::size)
                .max(Integer::compareTo)
                .orElse(0);
        var result = new LinkedList<Transaction<VarType, ValType>>();
        for (int i = 0; i < maxLength; ++i) {
            for (Session<VarType, ValType> session : sessions.values()) {
                if (session.getTransactions().size() <= i) {
                    continue;
                }
                result.add(session.getTransactions().get(i));
            }
        }
        return result;
    }
}
