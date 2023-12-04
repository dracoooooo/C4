package graph;

import lombok.Data;

@Data
public class Edge<VarType> {
    public enum Type {
        WR, SO, CO, AO
    }
    private final Type type;
    private final VarType variable;
}
