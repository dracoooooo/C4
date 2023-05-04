package loader;

import history.History;

public interface HistoryLoader<VarType, ValType> {
    History<VarType, ValType> loadHistory();
}
