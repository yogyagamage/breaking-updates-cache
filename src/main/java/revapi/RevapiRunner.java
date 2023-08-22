package revapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositoryException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.revapi.API;
import org.revapi.AnalysisContext;
import org.revapi.AnalysisResult;
import org.revapi.Revapi;
import org.revapi.base.FileArchive;
import org.revapi.maven.utils.ArtifactResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pw.krejci.modules.maven.MavenBootstrap;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.revapi.maven.utils.ArtifactResolver.getRevapiDependencySelector;
import static org.revapi.maven.utils.ArtifactResolver.getRevapiDependencyTraverser;

/**
 * The RevapiRunner class attempts to analyze two jar files using the Revapi.
 */
public class RevapiRunner {

    private static final Logger log = LoggerFactory.getLogger(RevapiRunner.class);
    private static final String DEFAULT_REPOSITORY_URL = "https://repo.maven.apache.org/maven2/";

    /**
     * Analyze two given jar files using Revapi.
     */
    public void analyzeWithJars(String oldJarPath, String newJarPath, String configFilePath, String parentFolderPath) {
        Revapi revapi = Revapi.builder().withAllExtensionsFromThreadContextClassLoader().build();
        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode configs = mapper.readTree((new File(configFilePath)));
            JsonNode nodeParent = configs.get(0).get("configuration");
            ((ObjectNode) nodeParent).put("output", parentFolderPath + "/" + "revapi-with-jars-comparison-result.json");
            AnalysisContext analysisContext = AnalysisContext.builder()
                    .withOldAPI(API.of(new FileArchive(new File(oldJarPath))).build())
                    .withNewAPI(API.of(new FileArchive(new File(newJarPath))).build())
                    .withConfiguration(configs).build();
            log.info("Starting analysis");
            long time = System.currentTimeMillis();
            try (AnalysisResult result = revapi.analyze(analysisContext)) {
                if (!result.isSuccess()) {
                    throw result.getFailure();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                log.info("Analysis took " + (System.currentTimeMillis() - time) + "ms.");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Analyze two given artifacts using Revapi when their maven coordinates are given.
     */
    public void analyzeWithMavenCoordinates(String[] oldGavs, String[] newGavs, String configFilePath, String parentFolderPath) {
        List<FileArchive> oldArchives;
        List<FileArchive> newArchives;
        List<FileArchive> oldSupplementaryArchives;
        List<FileArchive> newSupplementaryArchives;
        ArchivesAndSupplementaryArchives oldRes = convertGavs(oldGavs, "Old API Maven artifact");
        oldArchives = oldRes.archives;
        oldSupplementaryArchives = oldRes.supplementaryArchives;
        ArchivesAndSupplementaryArchives newRes = convertGavs(newGavs, "New API Maven artifact");
        newArchives = newRes.archives;
        newSupplementaryArchives = newRes.supplementaryArchives;
        Revapi revapi = Revapi.builder().withAllExtensionsFromThreadContextClassLoader().build();
        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode configs = mapper.readTree((new File(configFilePath)));
            JsonNode nodeParent = configs.get(0).get("configuration");
            ((ObjectNode) nodeParent).put("output", parentFolderPath + "/" + oldGavs[0].replace(":", "__") + "-" +
                    newGavs[0].split(":")[2] + "-revapi-comparison-result.json");
            AnalysisContext analysisContext = AnalysisContext.builder()
                    .withOldAPI(API.of(oldArchives).supportedBy(oldSupplementaryArchives).build())
                    .withNewAPI(API.of(newArchives).supportedBy(newSupplementaryArchives).build())
                    .withConfiguration(configs).build();

            log.info("Starting analysis");
            long time = System.currentTimeMillis();

            try (AnalysisResult result = revapi.analyze(analysisContext)) {
                if (!result.isSuccess()) {
                    throw result.getFailure();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                log.info("Analysis took " + (System.currentTimeMillis() - time) + "ms.");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * This method is based on <a href="https://github.com/revapi/revapi/blob/main/revapi-standalone/src/main/java/org/revapi/standalone/Main.java">...</a>.
     * It finds the archives from maven-central when the Maven coordinates are given.
     */
    private static ArchivesAndSupplementaryArchives convertGavs(String[] gavs, String errorMessagePrefix) {
        File localRepo = new File("cache");
        final List<RemoteRepository> remoteRepositories = Collections.unmodifiableList(remoteRepositories(new String[]{
                "https://repo1.maven.org/maven2/", "https://packages.atlassian.com/mvn/maven-atlassian-external/",
                "https://oss.sonatype.org/content/repositories/releases/", "https://repo.jenkins-ci.org",
                "https://plugins.jenkins.io/", "https://repo.spring.io/plugins-release/", "https://repo.spring.io/libs-milestone/"
        }));
        RepositorySystem repositorySystem = MavenBootstrap.newRepositorySystem();
        DefaultRepositorySystemSession session = MavenBootstrap.newRepositorySystemSession(repositorySystem,
                new LocalRepository(localRepo));

        session.setDependencySelector(getRevapiDependencySelector(true, false));
        session.setDependencyTraverser(getRevapiDependencyTraverser(true, false));

        ArtifactResolver resolver = new ArtifactResolver(repositorySystem, session, remoteRepositories);

        List<FileArchive> archives = new ArrayList<>();
        List<FileArchive> supplementaryArchives = new ArrayList<>();

        for (String gav : gavs) {
            try {
                File f = resolver.resolveArtifact(gav).getFile();
                if (f == null) {
                    // throw an exception if we fail to resolve one of the primary API archives but just warn if we fail
                    // to resolve one of its deps below.
                    throw new IllegalArgumentException(
                            "The gav '" + gav + "' did not resolve into a file-backed archive.");
                }

                archives.add(new FileArchive(resolver.resolveArtifact(gav).getFile()));
                ArtifactResolver.CollectionResult res = resolver.collectTransitiveDeps(gav);

                res.getResolvedArtifacts().forEach(a -> {
                    File af = a.getFile();
                    if (af == null) {
                        res.getFailures()
                                .add(new IllegalArgumentException("The gav '" + a.getGroupId() + ":" + a.getArtifactId()
                                        + ":" + a.getVersion()
                                        + "'  did not resolve into a file-backed archive and is therefore ignored."));
                    } else {
                        supplementaryArchives.add(new FileArchive(af));
                    }
                });
                if (!res.getFailures().isEmpty()) {
                    log.warn("The analysis may be skewed. Failed to resolve some transitive dependencies of '" + gav
                            + "': " + res.getFailures());
                }
            } catch (RepositoryException e) {
                throw new IllegalArgumentException(errorMessagePrefix + " " + e.getMessage());
            }
        }

        return new ArchivesAndSupplementaryArchives(archives, supplementaryArchives);
    }

    private static List<RemoteRepository> remoteRepositories(String[] customRepositoryUrls) {
        List<RemoteRepository> remoteRepositories = new ArrayList<>();

        for (int i = 0; i < customRepositoryUrls.length; i++) {
            String repositoryId = "custom-remote-repository-" + i;
            remoteRepositories
                    .add(new RemoteRepository.Builder(repositoryId, "default", customRepositoryUrls[i]).build());
        }

        if (remoteRepositories.isEmpty()) {
            remoteRepositories
                    .add(new RemoteRepository.Builder("maven-central", "default", DEFAULT_REPOSITORY_URL).build());
        }

        File localMaven = new File(new File(System.getProperties().getProperty("user.home"), ".m2"), "repository");

        RemoteRepository mavenCache = new RemoteRepository.Builder("~/.m2/repository", "default",
                localMaven.toURI().toString())
                .setPolicy(new RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_NEVER,
                        RepositoryPolicy.CHECKSUM_POLICY_IGNORE))
                .build();

        remoteRepositories.add(mavenCache);

        return remoteRepositories;
    }

    private record ArchivesAndSupplementaryArchives(List<FileArchive> archives,
                                                    List<FileArchive> supplementaryArchives) {
    }
}
