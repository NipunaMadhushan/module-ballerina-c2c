/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 *
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.c2c.utils;

import io.ballerina.c2c.exceptions.KubernetesPluginException;
import io.ballerina.c2c.models.DeploymentModel;
import io.ballerina.c2c.models.EnvVarValueModel;
import io.ballerina.c2c.models.JobModel;
import io.ballerina.c2c.models.KubernetesContext;
import io.ballerina.c2c.models.KubernetesDataHolder;
import io.ballerina.projects.Package;
import io.ballerina.projects.Project;
import io.ballerina.toml.api.Toml;
import io.fabric8.kubernetes.api.model.ConfigMapKeySelector;
import io.fabric8.kubernetes.api.model.ConfigMapKeySelectorBuilder;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.EnvVarSource;
import io.fabric8.kubernetes.api.model.EnvVarSourceBuilder;
import io.fabric8.kubernetes.api.model.ObjectFieldSelector;
import io.fabric8.kubernetes.api.model.ObjectFieldSelectorBuilder;
import io.fabric8.kubernetes.api.model.ResourceFieldSelector;
import io.fabric8.kubernetes.api.model.ResourceFieldSelectorBuilder;
import io.fabric8.kubernetes.api.model.SecretKeySelector;
import io.fabric8.kubernetes.api.model.SecretKeySelectorBuilder;
import org.apache.commons.io.FileUtils;
import org.ballerinalang.model.elements.PackageID;
import org.ballerinalang.model.tree.AnnotationAttachmentNode;
import org.ballerinalang.model.tree.IdentifierNode;
import org.ballerinalang.model.tree.NodeKind;
import org.ballerinax.docker.generator.exceptions.DockerGenException;
import org.ballerinax.docker.generator.models.CopyFileModel;
import org.ballerinax.docker.generator.models.DockerModel;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BConstantSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.types.BFiniteType;
import org.wso2.ballerinalang.compiler.tree.BLangAnnotationAttachment;
import org.wso2.ballerinalang.compiler.tree.BLangIdentifier;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangExpression;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangLiteral;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangRecordLiteral;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangSimpleVarRef;
import org.wso2.ballerinalang.compiler.util.Name;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static io.ballerina.c2c.KubernetesConstants.DEPLOYMENT_POSTFIX;
import static io.ballerina.c2c.KubernetesConstants.EXECUTABLE_JAR;
import static io.ballerina.c2c.KubernetesConstants.YAML;
import static org.ballerinax.docker.generator.utils.DockerGenUtils.extractJarName;

/**
 * Util methods used for artifact generation.
 */
public class KubernetesUtils {

    private static final PrintStream ERR = System.err;
    private static final PrintStream OUT = System.out;

    /**
     * Write content to a File. Create the required directories if they don't not exists.
     *
     * @param context        context of the file
     * @param outputFileName target file path
     * @throws IOException If an error occurs when writing to a file
     */
    public static void writeToFile(String context, String outputFileName) throws IOException {
        KubernetesDataHolder dataHolder = KubernetesContext.getInstance().getDataHolder();
        writeToFile(dataHolder.getK8sArtifactOutputPath(), context, outputFileName);
    }

    /**
     * Write content to a File. Create the required directories if they don't not exists.
     *
     * @param outputDir  Artifact output path.
     * @param context    Context of the file
     * @param fileSuffix Suffix for artifact.
     * @throws IOException If an error occurs when writing to a file
     */
    public static void writeToFile(Path outputDir, String context, String fileSuffix) throws IOException {
        KubernetesDataHolder dataHolder = KubernetesContext.getInstance().getDataHolder();
        Path artifactFileName = outputDir.resolve(extractJarName(dataHolder.getJarPath()) + fileSuffix);
        DeploymentModel deploymentModel = dataHolder.getDeploymentModel();
        JobModel jobModel = dataHolder.getJobModel();
        // Priority given for job, then deployment.
        if (jobModel != null && jobModel.isSingleYAML()) {
            artifactFileName = outputDir.resolve(extractJarName(dataHolder.getJarPath()) + YAML);
        } else if (jobModel == null && deploymentModel != null && deploymentModel.isSingleYAML()) {
            artifactFileName = outputDir.resolve(extractJarName(dataHolder.getJarPath()) + YAML);
        }

        File newFile = artifactFileName.toFile();
        // append if file exists
        if (newFile.exists()) {
            Files.write(artifactFileName, context.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND);
            return;
        }
        //create required directories
        if (newFile.getParentFile().mkdirs()) {
            Files.write(artifactFileName, context.getBytes(StandardCharsets.UTF_8));
            return;
        }
        Files.write(artifactFileName, context.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Read contents of a File.
     *
     * @param targetFilePath target file path
     * @throws KubernetesPluginException If an error occurs when reading file
     */
    public static byte[] readFileContent(Path targetFilePath) throws KubernetesPluginException {
        File file = targetFilePath.toFile();
        // append if file exists
        if (file.exists() && !file.isDirectory()) {
            try {
                return Files.readAllBytes(targetFilePath);
            } catch (IOException e) {
                throw new KubernetesPluginException("unable to read contents of the file " + targetFilePath);
            }
        }
        throw new KubernetesPluginException("unable to read contents of the file " + targetFilePath);
    }

    /**
     * Copy file or directory.
     *
     * @param source      source file/directory path
     * @param destination destination file/directory path
     */
    public static void copyFileOrDirectory(String source, String destination) throws KubernetesPluginException {
        File src = new File(source);
        File dst = new File(destination);
        try {
            // if source is file
            if (Files.isRegularFile(Paths.get(source))) {
                if (Files.isDirectory(dst.toPath())) {
                    // if destination is directory
                    FileUtils.copyFileToDirectory(src, dst);
                } else {
                    // if destination is file
                    FileUtils.copyFile(src, dst);
                }
            } else if (Files.isDirectory(Paths.get(source))) {
                FileUtils.copyDirectory(src, dst);
            }
        } catch (IOException e) {
            throw new KubernetesPluginException("error while copying file", e);
        }
    }

    /**
     * Prints an Error message.
     *
     * @param msg message to be printed
     */
    public static void printError(String msg) {
        ERR.println("error [k8s plugin]: " + msg);
    }

    /**
     * Prints an Instruction message.
     *
     * @param msg message to be printed
     */
    public static void printInstruction(String msg) {
        OUT.println(msg);
    }

    /**
     * Deletes a given directory.
     *
     * @param path path to directory
     * @throws KubernetesPluginException if an error occurs while deleting
     */
    public static void deleteDirectory(Path path) throws KubernetesPluginException {
        Path pathToBeDeleted = path.toAbsolutePath();
        if (!Files.exists(pathToBeDeleted)) {
            return;
        }
        try {
            Files.walk(pathToBeDeleted)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException e) {
            throw new KubernetesPluginException("unable to delete directory: " + path, e);
        }

    }

    /* Checks if a String is empty ("") or null.
     *
     * @param str the String to check, may be null
     * @return true if the String is empty or null
     */
    public static boolean isBlank(String str) {
        int strLen;
        if (str != null && (strLen = str.length()) != 0) {
            for (int i = 0; i < strLen; ++i) {
                if (!Character.isWhitespace(str.charAt(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Get a map from a ballerina expression.
     *
     * @param expr Ballerina record value.
     * @return Map of key values.
     * @throws KubernetesPluginException When the expression cannot be parsed.
     */
    public static Map<String, String> getMap(BLangExpression expr) throws KubernetesPluginException {
        if (expr.getKind() != NodeKind.RECORD_LITERAL_EXPR) {
            throw new KubernetesPluginException("unable to parse value: " + expr.toString());
        } else {
            BLangRecordLiteral fields = (BLangRecordLiteral) expr;
            Map<String, String> map = new LinkedHashMap<>();
            for (BLangRecordLiteral.BLangRecordKeyValueField keyValue : convertRecordFields(fields.getFields())) {
                map.put(keyValue.getKey().toString(), getStringValue(keyValue.getValue()));
            }
            return map;
        }
    }

    /**
     * Get the boolean value from a ballerina expression.
     *
     * @param expr Ballerina boolean value.
     * @return Parsed boolean value.
     * @throws KubernetesPluginException When the expression cannot be parsed.
     */
    public static boolean getBooleanValue(BLangExpression expr) throws KubernetesPluginException {
        return Boolean.parseBoolean(getStringValue(expr));
    }

    /**
     * Get the integer value from a ballerina expression.
     *
     * @param expr Ballerina integer value.
     * @return Parsed integer value.
     * @throws KubernetesPluginException When the expression cannot be parsed.
     */
    public static int getIntValue(BLangExpression expr) throws KubernetesPluginException {
        return Integer.parseInt(getStringValue(expr));
    }

    /**
     * Get the string value from a ballerina expression.
     *
     * @param expr Ballerina string value.
     * @return Parsed string value.
     * @throws KubernetesPluginException When the expression cannot be parsed.
     */
    public static String getStringValue(BLangExpression expr) throws KubernetesPluginException {
        if (expr instanceof BLangSimpleVarRef) {
            BLangSimpleVarRef varRef = (BLangSimpleVarRef) expr;
            if (varRef.symbol instanceof BConstantSymbol) {
                BConstantSymbol constantSymbol = (BConstantSymbol) varRef.symbol;
                if (constantSymbol.type instanceof BFiniteType) {
                    // Parse compile time constant
                    BFiniteType compileConst = (BFiniteType) constantSymbol.type;
                    if (compileConst.getValueSpace().size() > 0) {
                        return compileConst.getValueSpace().iterator().next().toString();
                    }
                }
            }
        } else if (expr instanceof BLangLiteral) {
            return expr.toString();
        }
        throw new KubernetesPluginException("unable to parse value: " + expr.toString());
    }

    /**
     * Returns valid kubernetes name.
     *
     * @param name actual value
     * @return valid name
     */
    public static String getValidName(String name) {
        if (name.startsWith("/")) {
            name = name.substring(1);
        }
        name = name.toLowerCase(Locale.getDefault()).replaceAll("[_.]", "-")
                .replaceAll("[$]", "").replaceAll("/", "-");
        name = name.substring(0, Math.min(name.length(), 15));
        if (name.endsWith("-")) {
            return name.substring(0, name.length() - 1);
        }
        return name;
    }

    /**
     * Get a list of environment variables.
     *
     * @param envMap Map of Environment variables
     * @return List of env vars
     */
    public static List<EnvVar> populateEnvVar(Map<String, EnvVarValueModel> envMap) {
        List<EnvVar> envVars = new ArrayList<>();
        if (envMap == null) {
            return envVars;
        }
        envMap.forEach((k, v) -> {
            EnvVar envVar = null;
            if (v.getValue() != null) {
                envVar = new EnvVarBuilder().withName(k).withValue(v.getValue()).build();
            } else if (v.getValueFrom() instanceof EnvVarValueModel.FieldRef) {
                EnvVarValueModel.FieldRef fieldRefModel = (EnvVarValueModel.FieldRef) v.getValueFrom();

                ObjectFieldSelector fieldRef =
                        new ObjectFieldSelectorBuilder().withFieldPath(fieldRefModel.getFieldPath()).build();
                EnvVarSource envVarSource = new EnvVarSourceBuilder().withFieldRef(fieldRef).build();
                envVar = new EnvVarBuilder().withName(k).withValueFrom(envVarSource).build();
            } else if (v.getValueFrom() instanceof EnvVarValueModel.SecretKeyRef) {
                EnvVarValueModel.SecretKeyRef secretKeyRefModel = (EnvVarValueModel.SecretKeyRef) v.getValueFrom();

                SecretKeySelector secretRef = new SecretKeySelectorBuilder()
                        .withName(secretKeyRefModel.getName())
                        .withKey(secretKeyRefModel.getKey())
                        .build();
                EnvVarSource envVarSource = new EnvVarSourceBuilder().withSecretKeyRef(secretRef).build();
                envVar = new EnvVarBuilder().withName(k).withValueFrom(envVarSource).build();
            } else if (v.getValueFrom() instanceof EnvVarValueModel.ResourceFieldRef) {
                EnvVarValueModel.ResourceFieldRef resourceFieldRefModel =
                        (EnvVarValueModel.ResourceFieldRef) v.getValueFrom();

                ResourceFieldSelector resourceFieldRef = new ResourceFieldSelectorBuilder()
                        .withContainerName(resourceFieldRefModel.getContainerName())
                        .withResource(resourceFieldRefModel.getResource())
                        .build();
                EnvVarSource envVarSource = new EnvVarSourceBuilder().withResourceFieldRef(resourceFieldRef).build();
                envVar = new EnvVarBuilder().withName(k).withValueFrom(envVarSource).build();
            } else if (v.getValueFrom() instanceof EnvVarValueModel.ConfigMapKeyValue) {
                EnvVarValueModel.ConfigMapKeyValue configMapKeyValue =
                        (EnvVarValueModel.ConfigMapKeyValue) v.getValueFrom();

                ConfigMapKeySelector configMapKey = new ConfigMapKeySelectorBuilder()
                        .withKey(configMapKeyValue.getKey())
                        .withName(configMapKeyValue.getName())
                        .build();
                EnvVarSource envVarSource = new EnvVarSourceBuilder().withConfigMapKeyRef(configMapKey).build();
                envVar = new EnvVarBuilder().withName(k).withValueFrom(envVarSource).build();
            }

            if (envVar != null) {
                envVars.add(envVar);
            }
        });
        return envVars;
    }

    public static List<BLangRecordLiteral.BLangRecordKeyValueField> convertRecordFields(
            List<BLangRecordLiteral.RecordField> fields) {
        return fields.stream().map(f -> (BLangRecordLiteral.BLangRecordKeyValueField) f).collect(Collectors.toList());
    }

    /**
     * Create an annotation node.
     *
     * @param annotationName Name of the annotation node.
     * @return The created node.
     */
    public static AnnotationAttachmentNode createAnnotation(String annotationName) {
        AnnotationAttachmentNode configAnnotation = new BLangAnnotationAttachment();
        IdentifierNode configIdentifier = new BLangIdentifier();
        configIdentifier.setValue(annotationName);
        configAnnotation.setAnnotationName(configIdentifier);
        configAnnotation.setExpression(new BLangRecordLiteral());
        return configAnnotation;
    }

    public static PackageID getProjectID(Project project) {
        Package currentPackage = project.currentPackage();
        return new PackageID(new Name(currentPackage.packageOrg().value()),
                new Name(currentPackage.packageName().value()),
                new Name(currentPackage.packageVersion().value().toString()));
    }

    public static void resolveDockerToml(KubernetesDataHolder dataHolder, DeploymentModel deploymentModel)
            throws KubernetesPluginException {
        final String containerImage = "container.image";
        Toml toml = dataHolder.getBallerinaCloud();
        if (toml != null) {
            DockerModel dockerModel = dataHolder.getDockerModel();
            dockerModel.setName(TomlHelper.getString(toml, containerImage + ".name",
                    deploymentModel.getName().replace(DEPLOYMENT_POSTFIX, "")));
            dockerModel
                    .setRegistry(TomlHelper.getString(toml, containerImage + ".repository", null));
            dockerModel.setTag(TomlHelper.getString(toml, containerImage + ".tag", dockerModel.getTag()));
            dockerModel.setBaseImage(TomlHelper.getString(toml, containerImage + ".base", dockerModel.getBaseImage()));
            String imageName = isBlank(dockerModel.getRegistry()) ? dockerModel.getName() + ":" + dockerModel.getTag() :
                    dockerModel.getRegistry() + "/" + dockerModel.getName() + ":" + dockerModel.getTag();
            deploymentModel.setImage(imageName);
            Set<CopyFileModel> copyFiles = new HashSet<>();
            for (Toml entry : toml.getTables("container.copy.files")) {
                CopyFileModel copyFileModel = new CopyFileModel();
                copyFileModel.setSource(TomlHelper.getString(entry, "sourceFile"));
                copyFileModel.setTarget(TomlHelper.getString(entry, "target"));
                copyFiles.add(copyFileModel);
            }
            try {
                dockerModel.setCopyFiles(copyFiles);
            } catch (DockerGenException e) {
                throw new KubernetesPluginException(e.getMessage());
            }
        }
    }

    /**
     * Creates docker model from Deployment Model object.
     *
     * @param deploymentModel Deployment model
     */
    public static DockerModel getDockerModel(DeploymentModel deploymentModel) {
        KubernetesDataHolder dataHolder = KubernetesContext.getInstance().getDataHolder();
        DockerModel dockerModel = dataHolder.getDockerModel();
        String dockerImage = deploymentModel.getImage();
        String imageTag = "latest";
        if (dockerImage.contains(":")) {
            imageTag = dockerImage.substring(dockerImage.lastIndexOf(":") + 1);
            dockerImage = dockerImage.substring(0, dockerImage.lastIndexOf(":"));
        }

        dockerModel.setPkgId(dataHolder.getPackageID());
        dockerModel.setBaseImage(deploymentModel.getBaseImage());
        dockerModel.setRegistry(deploymentModel.getRegistry());
        dockerModel.setName(dockerImage);
        dockerModel.setTag(imageTag);
        dockerModel.setEnableDebug(false);
        dockerModel.setUsername(deploymentModel.getUsername());
        dockerModel.setPassword(deploymentModel.getPassword());
        dockerModel.setPush(deploymentModel.isPush());
        dockerModel.setDockerConfig(deploymentModel.getDockerConfigPath());
        dockerModel.setCmd(deploymentModel.getCmd());
        dockerModel.setJarFileName(extractJarName(dataHolder.getJarPath()) + EXECUTABLE_JAR);
        dockerModel.setPorts(deploymentModel.getPorts().stream()
                .map(ContainerPort::getContainerPort)
                .collect(Collectors.toSet()));
        dockerModel.setUberJar(deploymentModel.isUberJar());
        dockerModel.setService(true);
        dockerModel.setDockerHost(deploymentModel.getDockerHost());
        dockerModel.setDockerCertPath(deploymentModel.getDockerCertPath());
        dockerModel.setBuildImage(deploymentModel.isBuildImage());
        dockerModel.addCommandArg(deploymentModel.getCommandArgs());
        return dockerModel;
    }
}
