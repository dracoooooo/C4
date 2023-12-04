package taps;


import lombok.Getter;

@Getter
public enum TAP {
    ThinAirRead("TAP-a"),
    AbortedRead("TAP-b"),
    FutureRead("TAP-c"),
    NotMyOwnWrite("TAP-d"),
    NotMyLastWrite("TAP-e"),
    IntermediateRead("TAP-f"),
    CyclicCO("TAP-g"),
    NonMonoReadCO("TAP-h"),
    NonMonoReadAO("TAP-i"),
    NonRepeatableRead("TAP-j"),
    FracturedReadCO("TAP-k"),
    FracturedReadAO("TAP-l"),
    COConflictAO("TAP-m"),
    ConflictAO("TAP-n");

    private final String code;

    TAP(String code) {
        this.code = code;
    }
}
