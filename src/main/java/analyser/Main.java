package analyser;

import picocli.CommandLine;

import java.nio.file.Path;

public class Main {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new CLIEntryPoint()).execute(args);
        System.exit(exitCode);
    }

    @CommandLine.Command(subcommands = {RunAnalyser.class}, mixinStandardHelpOptions = true, version = "0.1")
    public static class CLIEntryPoint implements Runnable {
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }
    }

    @CommandLine.Command(name = "run-analyser", mixinStandardHelpOptions = true, version = "0.1")
    private static class RunAnalyser implements Runnable {

        @CommandLine.Option(
                names = {"-b", "--benchmark-dir"},
                paramLabel = "BENCHMARK-DIR",
                description = "The directory where the breaking update files are stored.",
                required = true
        )
        Path benchmarkDir;

        @CommandLine.Option(
                names = {"-l", "--log-dir"},
                paramLabel = "LOG-DIR",
                description = "The directory where the log files are stored.",
                required = true
        )
        Path logDir;

        @Override
        public void run() {
            CompileErrorExtractor compileErrorExtractorRunner = new CompileErrorExtractor();
            compileErrorExtractorRunner.runAnalyser(benchmarkDir, logDir);
        }
    }
}
