package analyser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.okhttp.OkDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Map.entry;

public class CompileErrorExtractor {

    private static final String BREAKING_UPDATE_CONTAINER_TAG = "-breaking";
    private static final String REGISTRY = "ghcr.io/chains-project/breaking-updates";
    private static final Logger log = LoggerFactory.getLogger(CompileErrorExtractor.class);
    private static DockerClient dockerClient;

    public void runAnalyser(Path benchmarkDir, Path logDir) {
        File[] breakingUpdates = benchmarkDir.toFile().listFiles();
        createDockerClient();
        MapType jsonType = JsonUtils.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
        Path analyserResultsFilePath = Path.of("src/main/java/analyser/analyser-results" +
                JsonUtils.JSON_FILE_ENDING);
        if (Files.notExists(analyserResultsFilePath)) {
            try {
                Files.createFile(analyserResultsFilePath);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        Map<String, Object> analyserResults = JsonUtils.readFromNullableFile(analyserResultsFilePath, jsonType);
        if (analyserResults == null) {
            analyserResults = new HashMap<>();
        }
        int temp = 0;
        if (breakingUpdates != null) {
            for (File breakingUpdate : breakingUpdates) {
                Map<String, Object> compileErrorCauses = new HashMap<>();
                Map<String, Object> bu = JsonUtils.readFromFile(breakingUpdate.toPath(), jsonType);
                if (bu.get("failureCategory").equals("COMPILATION_FAILURE") && !analyserResults.containsKey((String) bu.get("breakingCommit"))) {
                    Map<String, Set<Integer>> lines =
                            extractLineNumbersWithPaths(logDir + "/" + bu.get("breakingCommit") + ".log");
                    String image = REGISTRY + ":" + bu.get("breakingCommit") + BREAKING_UPDATE_CONTAINER_TAG;
                    Path projectPath = copyProject(image, (String) bu.get("project"));
                    if (projectPath == null)
                        continue;
                    AbstractMap<Set<String>, Set<String>> spoonedElements = new HashMap<>();
                    for (Map.Entry<String, Set<Integer>> entry : lines.entrySet()) {
                        spoonedElements.putAll(applySpoon(projectPath + entry.getKey(), entry.getValue(),
                                ((Map<String, String>) bu.get("updatedDependency")).get("dependencyGroupID")
                                        .replace("-", ".")));
                    }
                    Map<String, Set<String>> revapiResults = new HashMap<>();
                    Map<String, Set<String>> japicmpResults = new HashMap<>();
                    Set<String> allCtElements = new HashSet<>();
                    for (Map.Entry<Set<String>, Set<String>> entry : spoonedElements.entrySet()) {
                        revapiResults.putAll(extractResult((String) bu.get("breakingCommit"), entry.getKey(), true));
                        japicmpResults.putAll(extractResult((String) bu.get("breakingCommit"), entry.getKey(), false));
                        allCtElements.addAll(entry.getValue());
                    }
                    compileErrorCauses.put("revapiResult", revapiResults);
                    compileErrorCauses.put("japicmpResult", japicmpResults);
                    compileErrorCauses.put("allPotentialBreakingElements", allCtElements);
                    analyserResults.put((String) bu.get("breakingCommit"), compileErrorCauses);
                    removeProject(image, projectPath);
                    temp += 1;
                    if (temp > 5) {
                        JsonUtils.writeToFile(analyserResultsFilePath, analyserResults);
                        temp = 0;
                    }
                }
            }
        }
        log.info("Analysis Results: {}", analyserResults);
        JsonUtils.writeToFile(analyserResultsFilePath, analyserResults);
    }

    private Map<String, Set<Integer>> extractLineNumbersWithPaths(String logFilePath) {
        Map<String, Set<Integer>> lineNumbersWithPaths = new HashMap<>();
        try {
            FileInputStream fileInputStream = new FileInputStream(logFilePath);
            InputStreamReader inputStreamReader = new InputStreamReader(fileInputStream, StandardCharsets.ISO_8859_1);
            BufferedReader reader = new BufferedReader(inputStreamReader);
            String line;
            String currentPath = null;
            Pattern errorPattern = Pattern.compile("\\[ERROR\\] .*:\\[(\\d+),\\d+\\]");
            Pattern pathPattern = Pattern.compile("/[^:/]+(/[^\\[\\]:]+)");

            while ((line = reader.readLine()) != null) {
                Matcher matcher = errorPattern.matcher(line);
                if (matcher.find()) {
                    Integer lineNumber = Integer.valueOf(matcher.group(1));
                    Matcher pathMatcher = pathPattern.matcher(line);
                    if (pathMatcher.find()) {
                        currentPath = pathMatcher.group();
                    }
                    if (currentPath != null) {
                        if (lineNumbersWithPaths.containsKey(currentPath))
                            lineNumbersWithPaths.get(currentPath).add(lineNumber);
                        else {
                            Set<Integer> lineNumbers = new HashSet<>();
                            lineNumbers.add(lineNumber);
                            lineNumbersWithPaths.put(currentPath, lineNumbers);
                        }
                        log.info("Error Line Number: {} path: {}", lineNumber, currentPath);
                    }
                }
            }
            reader.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
        return lineNumbersWithPaths;
    }

    private Path copyProject(String image, String project) {
        try {
            dockerClient.inspectImageCmd(image).exec();
        } catch (NotFoundException e) {
            try {
                dockerClient.pullImageCmd(image)
                        .exec(new PullImageResultCallback())
                        .awaitCompletion();
            } catch (InterruptedException ex) {
                return null;
            }
        }
        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(image);
        CreateContainerResponse container = containerCmd.exec();

        String containerId = container.getId();
        dockerClient.startContainerCmd(containerId).exec();

        try (InputStream dependencyStream = dockerClient.copyArchiveFromContainerCmd
                (containerId, "/" + project).exec()) {
            Path dir = Files.createDirectories(Path.of("tempProject")
                    .resolve(image.split(":")[1]));
            try (TarArchiveInputStream tarStream = new TarArchiveInputStream(dependencyStream)) {
                TarArchiveEntry entry;
                while ((entry = tarStream.getNextTarEntry()) != null) {
                    if (!entry.isDirectory()) {
                        Path filePath = dir.resolve(entry.getName());

                        if (!Files.exists(filePath)) {
                            Files.createDirectories(filePath.getParent());
                            Files.createFile(filePath);

                            byte[] fileContent = tarStream.readAllBytes();
                            Files.write(filePath, fileContent, StandardOpenOption.WRITE);
                        }
                    }
                }
            }
            return dir;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private Map<Set<String>, Set<String>> applySpoon
            (String projectFilePath, Set<Integer> lineNumbers, String depGrpID) {
        Launcher spoon = new Launcher();
        spoon.addInputResource(projectFilePath);
        spoon.buildModel();

        return getElementFromSourcePosition(spoon.getModel(), lineNumbers, depGrpID);
    }

    private Map<Set<String>, Set<String>> getElementFromSourcePosition
            (CtModel model, Set<Integer> startLines, String depGrpId) {
        Set<String> elements = new HashSet<>();
        Set<String> elementStrings = new HashSet<>();
        CtType<?> clazz = model.getAllTypes().iterator().next();
        for (CtElement e : clazz.getElements(new TypeFilter<>(CtElement.class))) {
            if (!e.isImplicit() && e.getPosition().isValidPosition() && startLines.contains(e.getPosition().getLine())) {
                if (e instanceof CtInvocation<?>) {
                    elements.add(String.valueOf(((CtInvocation<?>) e).getExecutable()));
                    elementStrings.add(parseProject(((CtInvocation<?>) e).getExecutable(), depGrpId));
                }
                if (e instanceof CtConstructorCall<?>) {
                    elements.add(String.valueOf(((CtConstructorCall<?>) e).getExecutable()));
                    elementStrings.add(parseProject(((CtConstructorCall<?>) e).getExecutable(), depGrpId));
                }
            }
        }
        return Map.ofEntries(entry(elementStrings, elements));
    }

    private String parseProject(CtElement e, String dependencyGrpID) {
        CtElement parent = e.getParent(new TypeFilter<>(CtClass.class));
        while (parent != null) {
            if (String.valueOf(parent).contains(dependencyGrpID)) {
                int openingParenthesisIndex = String.valueOf(e).indexOf('(');
                if (openingParenthesisIndex != -1) {
                    return String.valueOf(e).substring(0, openingParenthesisIndex);
                }
                return String.valueOf(e);
            }
            parent = parent.getParent(new TypeFilter<>(CtClass.class));
        }
        return null;
    }

    private Map<String, Set<String>> extractResult(String buCommit, Set<String> spoonedElements, boolean isRevapi) {
        String dataFolder = "data";
        String subfolderPath = dataFolder + File.separator + buCommit;
        ObjectMapper objectMapper = new ObjectMapper();
        if (isRevapi) {
            return readJsonFilesFromSubfolder(subfolderPath, objectMapper, spoonedElements);
        } else {
            return readXMLFilesFromSubfolder(subfolderPath, spoonedElements);
        }
    }

    private Map<String, Set<String>> readXMLFilesFromSubfolder(String subfolderPath,
                                                               Set<String> spoonedElements) {
        Map<String, Set<String>> japicmpResult = new HashMap<>();
        File subfolder = new File(subfolderPath);
        if (subfolder.exists() && subfolder.isDirectory()) {
            File[] xmlFiles = subfolder.listFiles((dir, name) -> name.endsWith(".xml"));
            if (xmlFiles != null && xmlFiles.length == 1) {
                File xmlFile = xmlFiles[0];
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                DocumentBuilder builder;
                try {
                    builder = factory.newDocumentBuilder();
                } catch (ParserConfigurationException e) {
                    e.printStackTrace();
                    return japicmpResult;
                }
                Document document;
                try {
                    document = builder.parse(xmlFile);
                } catch (SAXException | IOException e) {
                    e.printStackTrace();
                    return japicmpResult;
                }

                Element root = document.getDocumentElement();
                Set<String> uniqueCodeValues = new HashSet<>();
                for (String element : spoonedElements) {
                    if (element != null) {
                        uniqueCodeValues.addAll(searchAndExtractXML(root, element));
                        japicmpResult.put(element, uniqueCodeValues);
                    }
                }
            }
        }
        return japicmpResult;
    }

    private Set<String> searchAndExtractXML(Element root, String searchWord) {
        NodeList classNodes = root.getElementsByTagName("*");
        Set<String> compatibilityChanges = new HashSet<>();
        for (int i = 0; i < classNodes.getLength(); i++) {
            Element classElement = (Element) classNodes.item(i);
            String fullyQualifiedName = classElement.getAttribute("fullyQualifiedName");
            String name = classElement.getAttribute("name");

            if (fullyQualifiedName.contains(searchWord) || name.contains(searchWord)) {
                NodeList compatibilityChangeNodes = classElement.getElementsByTagName("compatibilityChange");
                for (int j = 0; j < compatibilityChangeNodes.getLength(); j++) {
                    String compatibilityChangeValue = compatibilityChangeNodes.item(j).getTextContent();
                    compatibilityChanges.add(compatibilityChangeValue);
                }
            }
        }
        return compatibilityChanges;
    }

    private Map<String, Set<String>> readJsonFilesFromSubfolder(String subfolderPath, ObjectMapper objectMapper,
                                                                Set<String> spoonedElements) {
        Map<String, Set<String>> revapiResult = new HashMap<>();
        File subfolder = new File(subfolderPath);
        if (subfolder.exists() && subfolder.isDirectory()) {
            File[] jsonFiles = subfolder.listFiles((dir, name) -> name.endsWith(".json"));
            if (jsonFiles != null && jsonFiles.length == 1) {
                JsonNode rootNode;
                try {
                    rootNode = objectMapper.readTree(jsonFiles[0]);
                } catch (IOException e) {
                    e.printStackTrace();
                    return revapiResult;
                }
                Set<String> uniqueCodeValues = new HashSet<>();
                for (String element : spoonedElements) {
                    if (element != null) {
                        uniqueCodeValues.addAll(searchAndExtractJson(rootNode, element, null));
                        revapiResult.put(element, uniqueCodeValues);
                    }
                }
            }
        }
        return revapiResult;
    }

    private Set<String> searchAndExtractJson(JsonNode node, String searchWord, JsonNode code) {
        Set<String> uniqueCodeValues = new HashSet<>();
        if (node.isArray()) {
            for (JsonNode childNode : node) {
                uniqueCodeValues.addAll(searchAndExtractJson(childNode, searchWord, code));
            }
        }
        if (node.isObject()) {
            if (node.get("code") != null) {
                code = node.get("code");
            }
            for (JsonNode childNode : node) {
                if (childNode.isTextual()) {
                    String fieldValue = childNode.asText();
                    if (fieldValue.contains(searchWord)) {
                        if (code != null) {
                            String codeValue = code.asText();
                            uniqueCodeValues.add(codeValue);
                        }
                    }
                }
                if (childNode.isArray()) {
                    for (JsonNode grandChildNode : node) {
                        uniqueCodeValues.addAll(searchAndExtractJson(grandChildNode, searchWord, code));
                    }
                }
            }
        }
        return uniqueCodeValues;
    }

    private void removeProject(String image, Path projectPath) {
        List<Container> containers = dockerClient.listContainersCmd().exec();
        for (Container container : containers) {
            dockerClient.stopContainerCmd(container.getId()).exec();
            dockerClient.removeContainerCmd(container.getId()).exec();
        }
        dockerClient.removeImageCmd(image).withForce(true).exec();
        try {
            FileUtils.forceDelete(new File(projectPath.toUri()));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void createDockerClient() {
        DockerClientConfig clientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withRegistryUrl("https://hub.docker.com")
                .build();
        DockerHttpClient httpClient = new OkDockerHttpClient.Builder()
                .dockerHost(clientConfig.getDockerHost())
                .sslConfig(clientConfig.getSSLConfig())
                .connectTimeout(30)
                .build();
        dockerClient = DockerClientImpl.getInstance(clientConfig, httpClient);
    }
}
