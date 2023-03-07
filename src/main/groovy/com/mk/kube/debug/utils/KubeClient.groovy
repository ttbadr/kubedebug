package com.mk.kube.debug.utils

import cn.hutool.core.util.StrUtil
import com.mk.kube.debug.PodExecListener
import com.mk.kube.debug.config.DeployConfig
import com.mk.kube.debug.config.UploadConfig
import io.fabric8.kubernetes.api.model.*
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.dsl.ContainerResource
import io.fabric8.kubernetes.client.dsl.CopyOrReadable
import io.fabric8.kubernetes.client.dsl.ExecWatch
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class KubeClient {
    private static final Logger logger = Logging.getLogger(KubeClient)
    private KubernetesClient k8sClient

    static final String JAVA_OPTIONS = '_JAVA_OPTIONS'

    KubeClient(String kubeConfig, String nameSpace) {
        def config = io.fabric8.kubernetes.client.Config.fromKubeconfig(kubeConfig)
        config.setNamespace(nameSpace)
        k8sClient = KubernetesClientBuilder.newInstance().withConfig(config).build()
        logger.lifecycle("connected to k8s master node via: ${config.masterUrl}, namespace is $nameSpace")
    }

    Deployment updateDeployment(Deployment deploy, DeployConfig deployment, boolean debug, int debugPort) {
        deploy.metadata.labels.putIfAbsent("kubeDebug", "true")
        //if debug enabled, then remove readiness and liveness
        if (debug || !deployment.healthCheck) {
            deploy.getSpec().getTemplate().getSpec().getContainers().get(0).setLivenessProbe(null)
            deploy.getSpec().getTemplate().getSpec().getContainers().get(0).setReadinessProbe(null)
        }

        def container = new DeploymentBuilder(deploy).editSpec()
                .withReplicas(0)
                .editTemplate()
                .editSpec()
                .editFirstContainer()

        if (debug) {
            def value = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=$debugPort"
            if (container.hasMatchingEnv({ it.getName() == JAVA_OPTIONS })) {
                container.editMatchingEnv({ it.getName() == JAVA_OPTIONS }).withValue(value)
            } else {
                container.addToEnv(new EnvVarBuilder()
                        .withName(JAVA_OPTIONS)
                        .withValue(value)
                        .build())
            }
        }

        if (deployment.runAsRoot) {
            container.editSecurityContext().withRunAsUser(0).withRunAsNonRoot(false).endSecurityContext()
        }

        if (!deployment.readOnly) {
            container.editSecurityContext().withReadOnlyRootFilesystem(false).endSecurityContext()
        }

        if (deployment.commands != null && deployment.commands.size() > 0) {
            container.withCommand(deployment.commands).withArgs(null)
        }

        def newDeployment = k8sClient.apps().deployments().replace(container.endContainer().endSpec().endTemplate().endSpec().build())
        logger.lifecycle("deployment ${deployment.name} updated")
        return newDeployment
    }

    def createDebugService(String serviceName, String appName, int port) {
        k8sClient.services().create(new ServiceBuilder()
                .withNewMetadata()
                .withName(serviceName)
                .endMetadata()
                .withNewSpec()
                .addToSelector("app", appName)
                .withType("NodePort")
                .addToPorts(new ServicePortBuilder()
                        .withName("debug")
                        .withProtocol("TCP")
                        .withNodePort(port)
                        .withTargetPort(new IntOrStringBuilder()
                                .withValue(port)
                                .build())
                        .withPort(port).build())
                .endSpec().build())
        logger.lifecycle("node service $serviceName created, port: $port")
    }

    def restore(File backupFile) {
        def deploy = k8sClient.apps().deployments().load(backupFile).get()
        k8sClient.apps().deployments().replace(deploy)
    }

    def copy(Pod pod, UploadConfig uploadFile) {
        def local = new File(uploadFile.localPath)
        boolean dir = local.isDirectory()

        ContainerResource container = k8sClient.pods().withName(pod.metadata.name)
                .inContainer(getContainerName(uploadFile.containerName, pod))

        def podPath = PathUtil.genDestPath(uploadFile.localPath, uploadFile.remotePath)
        CopyOrReadable selector
        if (dir) {
            selector = container.dir(podPath)
        } else {
            selector = container.file(podPath)
        }
        logger.lifecycle("start to upload $uploadFile.localPath to $pod.metadata.name:$podPath")
        if (selector.upload(local.toPath())) {
            logger.lifecycle("upload success")
        } else {
            throw new GradleException("fail to upload")
        }
    }

    String exec(String podName, String containerName, int timeout, String cmd) {
        CountDownLatch execLatch = new CountDownLatch(1)
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        ByteArrayOutputStream error = new ByteArrayOutputStream()
        try {
            ExecWatch execWatch = k8sClient.pods().withName(podName)
                    .inContainer(containerName)
                    .writingOutput(out)
                    .writingError(error)
                    .usingListener(new PodExecListener(execLatch, logger))
                    .exec("/bin/sh", "-c", cmd)

            boolean latchTerminationStatus = execLatch.await(timeout, TimeUnit.SECONDS)
            if (!latchTerminationStatus) {
                logger.error(String.format("Execute command timeout after %d seconds. cmd: %s", timeout, cmd))
            }
            execWatch.close()
            if (error.size() > 0) {
                throw new IllegalStateException(String.format("exec cmd failed: %s \nreason: %s", cmd, error))
            }
            return StrUtil.removeSuffix(out.toString(), "\n")
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(String.format("exec cmd failed: %s", cmd), e)
        } finally {
            out?.close()
            error?.close()
        }
    }

    Deployment getDeploy(String name) {
        return k8sClient.apps().deployments().withName(name).get()
    }

    Pod getPod(String name) {
        return k8sClient.pods().withName(name).get()
    }

    Pod getRunningPodByLabel(String name, String value) {
        return k8sClient.pods().withLabel(name, value).list().getItems().find { it.getStatus().getPhase() == 'Running' }
    }

    Service getService(String name) {
        return k8sClient.services().withName(name).get()
    }

    def scale(String name, int replica) {
        k8sClient.apps().deployments().withName(name).scale(replica)
    }

    static String getContainerName(String name, Pod pod) {
        return StrUtil.isNotBlank(name) ? name : pod.getSpec().getContainers().get(0).getName()
    }
}
