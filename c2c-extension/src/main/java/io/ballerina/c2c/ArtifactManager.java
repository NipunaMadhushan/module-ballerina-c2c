/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
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

package io.ballerina.c2c;

import io.ballerina.c2c.exceptions.KubernetesPluginException;
import io.ballerina.c2c.handlers.DeploymentHandler;
import io.ballerina.c2c.handlers.DockerHandler;
import io.ballerina.c2c.handlers.HPAHandler;
import io.ballerina.c2c.handlers.JobHandler;
import io.ballerina.c2c.handlers.PersistentVolumeClaimHandler;
import io.ballerina.c2c.handlers.ServiceHandler;
import io.ballerina.c2c.models.DeploymentModel;
import io.ballerina.c2c.models.KubernetesContext;
import io.ballerina.c2c.models.KubernetesDataHolder;
import io.ballerina.c2c.models.ServiceModel;
import io.ballerina.c2c.utils.KubernetesUtils;
import io.ballerina.c2c.utils.TomlHelper;
import io.ballerina.toml.api.Toml;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import org.ballerinax.docker.generator.models.DockerModel;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.ballerinax.docker.generator.utils.DockerGenUtils.extractJarName;

/**
 * Generate and write artifacts to files.
 */
public class ArtifactManager {

    private static final Map<String, String> instructions = new LinkedHashMap<>();
    private static final PrintStream OUT = System.out;
    private KubernetesDataHolder kubernetesDataHolder;

    ArtifactManager() {
        this.kubernetesDataHolder = KubernetesContext.getInstance().getDataHolder();
    }

    /**
     * Returns print instructions.
     *
     * @return instructions.
     */
    public static Map<String, String> getInstructions() {
        return instructions;
    }

    /**
     * Generates artifacts according to the cloud parameter in build options.
     *
     * @param cloudType Value of cloud field in build option.
     * @throws KubernetesPluginException if an error occurs while generating artifacts
     */
    public void createArtifacts(String cloudType) throws KubernetesPluginException {
        if (cloudType.equals("k8s")) {
            createKubernetesArtifacts();
        } else if (cloudType.equals("docker")) {
            createDockerArtifacts();
        } else {
            throw new KubernetesPluginException("Unsupported cloud option");
        }
    }

    /**
     * Generate kubernetes artifacts.
     *
     * @throws KubernetesPluginException if an error occurs while generating artifacts
     */
    public void createKubernetesArtifacts() throws KubernetesPluginException {
        // add default kubernetes instructions.
        setDefaultKubernetesInstructions();
        OUT.println("\nGenerating artifacts...");
        if (kubernetesDataHolder.getJobModel() != null) {
            new JobHandler().createArtifacts();
            new DockerHandler().createArtifacts();
        } else {
            new ServiceHandler().createArtifacts();
            new PersistentVolumeClaimHandler().createArtifacts();
            new DeploymentHandler().createArtifacts();
            new HPAHandler().createArtifacts();
            new DockerHandler().createArtifacts();
        }

        printInstructions();
    }

    public void createDockerArtifacts() throws KubernetesPluginException {
        OUT.println("\nGenerating artifacts...");
        List<ServiceModel> serviceModels = kubernetesDataHolder.getServiceModelList();
        DeploymentModel deploymentModel = kubernetesDataHolder.getDeploymentModel();
        for (ServiceModel serviceModel : serviceModels) {
            ContainerPort containerPort = new ContainerPortBuilder()
                    .withName(serviceModel.getPortName())
                    .withContainerPort(serviceModel.getTargetPort())
                    .withProtocol(KubernetesConstants.KUBERNETES_SVC_PROTOCOL)
                    .build();
            deploymentModel.addPort(containerPort);
        }
        KubernetesUtils.resolveDockerToml(kubernetesDataHolder, kubernetesDataHolder.getDeploymentModel());
        DockerModel dockerModel = KubernetesUtils.getDockerModel(deploymentModel);
        kubernetesDataHolder.setDockerModel(dockerModel);
        modifyDockerModelWithToml();
        new DockerHandler().createArtifacts();

        instructions.put("\tExecute the below command to run the generated docker image: ",
                "\tdocker run -d " + generatePortInstruction(dockerModel.getPorts()) + dockerModel.getName());
        printInstructions();
    }

    private String generatePortInstruction(Set<Integer> ports) {
        if (ports.size() == 0) {
            return "";
        }
        StringBuilder output = new StringBuilder();
        for (Integer port : ports) {
            output.append("-p ").append(port).append(":").append(port).append(" ");
        }
        return output.toString();
    }

    private void modifyDockerModelWithToml() {
        final String containerImage = "container.image";
        Toml toml = kubernetesDataHolder.getBallerinaCloud();
        if (toml != null) {
            DockerModel dockerModel = kubernetesDataHolder.getDockerModel();
            dockerModel.setName(TomlHelper.getString(toml, containerImage + ".name", dockerModel.getName()));
            dockerModel
                    .setRegistry(TomlHelper.getString(toml, containerImage + ".repository", null));
            dockerModel.setTag(TomlHelper.getString(toml, containerImage + ".tag", dockerModel.getTag()));
            dockerModel.setBaseImage(TomlHelper.getString(toml, containerImage + ".base", dockerModel.getBaseImage()));
        }
    }

    private void printInstructions() {
        KubernetesUtils.printInstruction("");
        KubernetesUtils.printInstruction("");
        for (Map.Entry<String, String> instruction : instructions.entrySet()) {
            KubernetesUtils.printInstruction(instruction.getKey());
            KubernetesUtils.printInstruction(instruction.getValue());
            KubernetesUtils.printInstruction("");
        }
    }

    public void populateDeploymentModel() {
        DeploymentModel deploymentModel = kubernetesDataHolder.getDeploymentModel();
        kubernetesDataHolder.setDeploymentModel(deploymentModel);
        String balxFileName = extractJarName(kubernetesDataHolder.getJarPath());
        if (KubernetesUtils.isBlank(deploymentModel.getName())) {
            if (balxFileName != null) {
                deploymentModel.setName(KubernetesUtils.getValidName(balxFileName)
                        + KubernetesConstants.DEPLOYMENT_POSTFIX);
            }
        }
        if (KubernetesUtils.isBlank(deploymentModel.getImage())) {
            deploymentModel.setImage(balxFileName + KubernetesConstants.DOCKER_LATEST_TAG);
        }
        deploymentModel.addLabel(KubernetesConstants.KUBERNETES_SELECTOR_KEY, balxFileName);
        kubernetesDataHolder.setDeploymentModel(deploymentModel);
    }

    /**
     * Set instructions for kubernetes and helm artifacts.
     */
    private void setDefaultKubernetesInstructions() {
        instructions.put("\tExecute the below command to deploy the Kubernetes artifacts: ",
                "\tkubectl apply -f " + this.kubernetesDataHolder.getK8sArtifactOutputPath().toAbsolutePath());

        instructions.put("\tExecute the below command to access service via NodePort: ",
                "\tkubectl expose deployment " + this.kubernetesDataHolder.getDeploymentModel().getName() + " --type" +
                        "=NodePort --name=" + kubernetesDataHolder.getDeploymentModel().getName()
                        .replace(KubernetesConstants.DEPLOYMENT_POSTFIX, "-svc-local"));
    }
}
