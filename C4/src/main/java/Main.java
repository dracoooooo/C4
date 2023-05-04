import alg.AlgType;
import alg.C4;
import alg.C4List;
import loader.ElleHistoryLoader;
import loader.TextHistoryLoader;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.util.concurrent.Callable;

@Command(name = "C4", mixinStandardHelpOptions = true, version = "C4 1.0", description = "Check if the history satisfies transactional causal consistency.\n")
public class Main implements Callable<Integer> {

    @Parameters(index = "0", description = "Input file")
    private File file;

    @Option(names = "-t", description = "Candidates: ${COMPLETION-CANDIDATES}")
    private AlgType algType;


    @Override
    public Integer call()  {
        if (algType.equals(AlgType.C4_LIST)) {
            var historyLoader = new ElleHistoryLoader(file);
            var history = historyLoader.loadHistory();
            var c4 = new C4List<>(algType, history);
            c4.validate();
            System.out.println(c4.getBadPatterns());
        } else {
            var historyLoader = new TextHistoryLoader(file);
            var history = historyLoader.loadHistory();
            var c4 = new C4<>(algType, history);
            c4.validate();
            System.out.println(c4.getBadPatterns());
        }
        return 0;
    }

    public static void main(String... args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

}