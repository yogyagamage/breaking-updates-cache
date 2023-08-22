package japicmp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Path;


public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        int exitCode = new CommandLine(new CLIEntryPoint()).execute(args);
        System.exit(exitCode);
    }


    @CommandLine.Command(subcommands = {RunJapicmp.class}, mixinStandardHelpOptions = true, version = "0.1")
    public static class CLIEntryPoint implements Runnable {
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }
    }

    @CommandLine.Command(name = "run-japicmp", mixinStandardHelpOptions = true, version = "0.1")
    private static class RunJapicmp implements Runnable {

        @CommandLine.Option(
                names = {"-j", "--jar-dir"},
                paramLabel = "JAR-DIR",
                description = "The directory where the jar files to be compared are stored.",
                required = true
        )
        Path jarDir;

        @CommandLine.Option(
                names = {"-o", "--old-jar"},
                paramLabel = "OLD-JAR",
                description = "The path of the old jar file. If not provided, japicmp will be run on all " +
                        "the breaking updates in the jar-dir."
        )
        String oldJar;

        @CommandLine.Option(
                names = {"-n", "--new-jar"},
                paramLabel = "NEW-JAR",
                description = "The path of the new jar file. If not provided, japicmp will be run on all " +
                        "the breaking updates in the jar-dir."
        )
        String newJar;

        @Override
        public void run() {
            JapicmpRunner japicmpRunner = new JapicmpRunner();
            if (oldJar != null && newJar != null) {
                File[] list = {Path.of(oldJar).toFile(),Path.of(newJar).toFile()};
                japicmpRunner.analyzeJars(list);
            } else {
                File[] breakingUpdateFolders = jarDir.toFile().listFiles();
                if (breakingUpdateFolders != null) {
                        japicmpRunner.readALlJars(jarDir);
                }else{
                    log.error("No breaking update folders found in {}", jarDir);
                    throw new RuntimeException("No breaking update folders found in " + jarDir);
                }

            }
        }
    }
}
