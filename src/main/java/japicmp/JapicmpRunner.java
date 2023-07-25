package japicmp;

import japicmp.cli.JApiCli;
import japicmp.cmp.JApiCmpArchive;
import japicmp.cmp.JarArchiveComparator;
import japicmp.cmp.JarArchiveComparatorOptions;
import japicmp.config.Options;
import japicmp.exception.JApiCmpException;
import japicmp.model.AccessModifier;
import japicmp.model.JApiClass;
import japicmp.output.OutputFilter;
import japicmp.output.semver.SemverOut;
import japicmp.output.xml.XmlOutput;
import japicmp.output.xml.XmlOutputGenerator;
import japicmp.output.xml.XmlOutputGeneratorOptions;
import japicmp.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import revapi.RevapiRunner;

import java.io.File;
import java.io.FileFilter;
import java.nio.file.Path;
import java.util.List;

public class JapicmpRunner {
    private static final Logger log = LoggerFactory.getLogger(JapicmpRunner.class);


    private static Options getDefaultOptions() {
        Options defaultOptions = Options.newDefault();
        defaultOptions.setAccessModifier(AccessModifier.PROTECTED);
        defaultOptions.setOutputOnlyModifications(true);
        defaultOptions.setXmlOutputFile(Optional.of("output.xml"));
        defaultOptions.setClassPathMode(JApiCli.ClassPathMode.TWO_SEPARATE_CLASSPATHS);
        defaultOptions.setIgnoreMissingClasses(true);
        defaultOptions.setReportOnlyFilename(true);
        String[] excl = {"(*.)?tests(.*)?", "(*.)?test(.*)?", "@org.junit.After", "@org.junit.AfterClass", "@org.junit.Before", "@org.junit.BeforeClass", "@org.junit.Ignore", "@org.junit.Test", "@org.junit.runner.RunWith"};

        for (String e : excl) {
            defaultOptions.addExcludeFromArgument(Optional.of(e), false);
        }

        return defaultOptions;
    }

    public void readALlJars(Path path) {
        File[] list = new File(path.toUri()).listFiles();
        FileFilter fileFilter = file -> file.getName().endsWith(".jar");

        //read all files and check if they are folders
        if (list != null) {
            for (File file : list) {
                if (file.isDirectory()) {
                    //if they are folders, read all jars in the folder and almost two are jars
                    File[] listFiles = file.listFiles(fileFilter);
                    if (listFiles != null && listFiles.length == 2) {
                        analyzeJars(listFiles);
                    }
                }
            }
        } else {
            throw new RuntimeException("The path is not a directory");
        }
    }

    public void analyzeJars(File[] list1) {
        int[] version = new int[2];
        String[] file1 = list1[0].getName().split("__");
        String[] file2 = list1[1].getName().split("__");

        var version1 = file1[2];
        var version2 = file2[2];

        if (version1.compareTo(version2) > 0) {
            compareJars(list1[0], list1[1], version1, version2);
        } else {
            compareJars(list1[1], list1[0], version2, version1);
        }
    }

    private static void compareJars(File newJar, File oldJar, String newVersion, String oldVersion) {
        log.info("Comparing {} with {}", newJar.getName(), oldJar.getName());

        Options defaultOptions = getDefaultOptions();
        JarArchiveComparatorOptions comparatorOptions = JarArchiveComparatorOptions.of(defaultOptions);

        JarArchiveComparator jarArchiveComparator = new JarArchiveComparator(comparatorOptions);
        JApiCmpArchive newF = new JApiCmpArchive(newJar, newVersion);
        JApiCmpArchive old = new JApiCmpArchive(oldJar, oldVersion);

        List<JApiClass> jApiClasses = jarArchiveComparator.compare(old, newF);
        OutputFilter filter = new OutputFilter(defaultOptions);
        filter.filter(jApiClasses);

        String path = newJar.getParent() + "/japicmp__" + newJar.getName() + "__" + oldJar.getName() + ".xml";

        defaultOptions.setXmlOutputFile(Optional.of(path));

        SemverOut semverOut = new SemverOut(defaultOptions, jApiClasses);
        XmlOutputGeneratorOptions xmlOutputGeneratorOptions = new XmlOutputGeneratorOptions();
        xmlOutputGeneratorOptions.setCreateSchemaFile(true);
        xmlOutputGeneratorOptions.setSemanticVersioningInformation(semverOut.generate());
        XmlOutputGenerator xmlGenerator = new XmlOutputGenerator(jApiClasses, defaultOptions, xmlOutputGeneratorOptions);
        try (XmlOutput xmlOutput = xmlGenerator.generate()) {
            XmlOutputGenerator.writeToFiles(defaultOptions, xmlOutput);
        } catch (Exception e) {
            throw new JApiCmpException(JApiCmpException.Reason.IoException, "Could not close output streams: " + e.getMessage(), e);
        }

    }
}