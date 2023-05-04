package history;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Session<VarType, ValType> {
    @EqualsAndHashCode.Include
    final long id;

    private final List<Transaction<VarType, ValType>> transactions = new ArrayList<>();
}
