package history;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

@Data
@ToString(onlyExplicitlyIncluded = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Transaction <VarType, ValType> {

    @EqualsAndHashCode.Include
    @ToString.Include
    private final long id;

    @EqualsAndHashCode.Include
    private final Session<VarType, ValType> session;

    List<Operation<VarType, ValType>> ops = new ArrayList<>();

}
