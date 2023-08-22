package revapi;

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

    @CommandLine.Command(subcommands = {RunRevapi.class, RunRevapiWithJar.class}, mixinStandardHelpOptions = true, version = "0.1")
    public static class CLIEntryPoint implements Runnable {
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }
    }

    @CommandLine.Command(name = "run-revapi", mixinStandardHelpOptions = true, version = "0.1")
    private static class RunRevapi implements Runnable {

        @CommandLine.Option(
                names = {"-j", "--jar-dir"},
                paramLabel = "JAR-DIR",
                description = "The directory where the jar files to be compared are stored.",
                required = true
        )
        Path jarDir;

        @CommandLine.Option(
                names = {"-c", "--config-file"},
                paramLabel = "CONFIG-FILE",
                description = "The file with the configurations for revapi.",
                required = true
        )
        String configFile;

        @CommandLine.Option(
                names = {"-o", "--old-jar"},
                paramLabel = "OLD-JAR",
                description = "The maven coordinates of the old jar file. If not provided, revapi will be run on all " +
                        "the breaking updates in the jar-dir."
        )
        String oldJar;

        @CommandLine.Option(
                names = {"-n", "--new-jar"},
                paramLabel = "NEW-JAR",
                description = "The maven coordinates of the new jar file. If not provided, revapi will be run on all " +
                        "the breaking updates in the jar-dir."
        )
        String newJar;

        @CommandLine.Option(
                names = {"-f", "--output-folder"},
                paramLabel = "OUTPUT-FOLDER",
                description = "The directory where output should be written when the maven coordinates of the " +
                        "jar files are provided. If not provided, output file will be saved in the data directory.",
                defaultValue = "data"
        )
        String outputPath;

        @Override
        public void run() {
            RevapiRunner revapiRunner = new RevapiRunner();
            if (oldJar != null && newJar != null) {
                revapiRunner.analyzeWithMavenCoordinates(new String[]{oldJar.replace("__", ":")},
                        new String[]{newJar.replace("__", ":")}, configFile, outputPath);
            } else {
                File[] breakingUpdateFolders = jarDir.toFile().listFiles();
                if (breakingUpdateFolders != null) {
                    for (File breakingUpdateFolder : breakingUpdateFolders) {
                        String oldJarMavenCoordinates = null;
                        String newJarMavenCoordinates = null;
                        if (breakingUpdateFolder.isDirectory()) {
                            File[] jars = breakingUpdateFolder.listFiles();
                            if (jars != null) {
                                for (File jar : jars) {
                                    if (jar.isFile() && jar.getName().endsWith(".jar")) {
                                        if (jar.getName().contains("prev")) {
                                            oldJarMavenCoordinates = jar.getName().split("___")[0].replace("__", ":");
                                        } else {
                                            newJarMavenCoordinates = jar.getName().split("___")[0].replace("__", ":");
                                        }
                                    }
                                }
                            }
                        }
                        try {
                            if (oldJarMavenCoordinates != null && newJarMavenCoordinates != null) {
                                revapiRunner.analyzeWithMavenCoordinates(new String[]{oldJarMavenCoordinates},
                                        new String[]{newJarMavenCoordinates}, configFile, breakingUpdateFolder.getPath());
                            }
                        } catch (Exception e) {
                            log.error("Failure when running revapi for {}", breakingUpdateFolder.getName(), e);
                        }
                    }
                }
            }
        }
    }

    @CommandLine.Command(name = "run-revapi-with-jar", mixinStandardHelpOptions = true, version = "0.1")
    private static class RunRevapiWithJar implements Runnable {

        @CommandLine.Option(
                names = {"-j", "--jar-dir"},
                paramLabel = "JAR-DIR",
                description = "The directory where the jar files to be compared are stored.",
                required = true
        )
        Path jarDir;

        @CommandLine.Option(
                names = {"-c", "--config-file"},
                paramLabel = "CONFIG-FILE",
                description = "The file with the configurations for revapi.",
                required = true
        )
        String configFile;

        @CommandLine.Option(
                names = {"-o", "--old-jar"},
                paramLabel = "OLD-JAR",
                description = "The path of the old jar file. If not provided, revapi will be run on all " +
                        "the breaking updates in the jar-dir."
        )
        String oldJar;

        @CommandLine.Option(
                names = {"-n", "--new-jar"},
                paramLabel = "NEW-JAR",
                description = "The path of the new jar file. If not provided, revapi will be run on all " +
                        "the breaking updates in the jar-dir."
        )
        String newJar;

        @Override
        public void run() {
            RevapiRunner revapiRunner = new RevapiRunner();
            if (oldJar != null && newJar != null) {
                revapiRunner.analyzeWithJars(oldJar, newJar, configFile, Path.of(newJar).getParent().toString());
            } else {
                File[] breakingUpdateFolders = jarDir.toFile().listFiles();
                if (breakingUpdateFolders != null) {
                    for (File breakingUpdateFolder : breakingUpdateFolders) {
                        String oldJarPath = null;
                        String newJarPath = null;
                        if (breakingUpdateFolder.isDirectory()) {
                            File[] jars = breakingUpdateFolder.listFiles();
                            if (jars != null) {
                                for (File jar : jars) {
                                    if (jar.isFile() && jar.getName().endsWith(".jar")) {
                                        if (jar.getName().contains("prev")) {
                                            oldJarPath = jar.getPath();
                                        } else {
                                            newJarPath = jar.getPath();
                                        }
                                    }
                                }
                            }
                        }
                        try {
                            if (oldJarPath != null && newJarPath != null) {
                                revapiRunner.analyzeWithJars(oldJarPath, newJarPath, configFile,
                                        breakingUpdateFolder.getPath());
                            }
                        } catch (Exception e) {
                            log.error("Failure when running revapi for {}", breakingUpdateFolder.getName(), e);
                        }
                    }
                }
            }
        }
    }
}
