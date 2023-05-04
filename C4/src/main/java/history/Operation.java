package history;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Operation<VarType, ValType> {
    public enum Type {
        READ,
        WRITE
    }

    @EqualsAndHashCode.Include
    private final Type type;

    @EqualsAndHashCode.Include
    private final VarType variable;

    @EqualsAndHashCode.Include
    private final ValType value;

    @EqualsAndHashCode.Include
    private final Transaction<VarType, ValType> transaction;

    @EqualsAndHashCode.Include
    private final Integer id;
}
