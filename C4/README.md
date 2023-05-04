# C4

C4 is a tool that helps you check the transactional causal consistency of a distributed database.

## Installation

To use C4, you will need to have java11 and maven installed on your system.

```bash
$ sudo apt update
$ sudo apt install openjdk-11-jdk maven
```

Then clone the project repository and build it.

```bash
$ git clone https://github.com/pamaxex958/C4.git
$ cd C4
$ mvn clean package
```

After building, there will be a product at `target/C4-1.0-SNAPSHOT-shaded.jar`.

## Usage

```
Usage: C4 [-hV] [-t=<algType>] <file>
Check if the history satisfies transactional causal consistency.

      <file>      Input file
  -h, --help      Show this help message and exit.
  -t=<algType>    Candidates: C4, C4_WITHOUT_TC, C4_WITHOUT_VEC, C4_LIST
  -V, --version   Print version information and exit.
```

