package com.mk.kube.debug

import cn.hutool.core.io.FileUtil
import cn.hutool.core.io.file.FileNameUtil
import cn.hutool.core.net.NetUtil
import cn.hutool.core.util.StrUtil
import com.mk.kube.debug.config.DeployConfig
import com.mk.kube.debug.config.K8sConfig
import com.mk.kube.debug.config.UploadConfig
import io.fabric8.kubernetes.api.model.*
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.ContainerResource
import io.fabric8.kubernetes.client.dsl.CopyOrReadable
import io.fabric8.kubernetes.client.dsl.ExecWatch
import io.fabric8.kubernetes.client.dsl.LogWatch
import io.fabric8.kubernetes.client.internal.SerializationUtils
import org.gradle.api.*
import org.gradle.api.tasks.TaskAction

import javax.inject.Inject
import java.util.concurrent.TimeUnit

class KubeDebugTask extends DefaultTask {
    boolean restore = false
    boolean uploadFilesOnly = false

    NamedDomainObjectContainer<UploadConfig> uploads
    K8sConfig k8s
    DeployConfig deployment

    private static final String JAVA_OPTIONS = '_JAVA_OPTIONS'
    private static final File BACKUP_DIR = new File(FileUtil.getUserHomeDir(), ".cms/backup/")
    private static KubernetesClient k8sClient

    @Inject
    KubeDebugTask(Project project) {
        k8s = project.objects.newInstance(K8sConfig)
        deployment = project.objects.newInstance(DeployConfig)
        uploads = project.container(UploadConfig)
    }

    void k8s(Action<? super K8sConfig> action) {
        action.execute(k8s)
    }

    void deployment(Action<? super DeployConfig> action) {
        action.execute(deployment)
    }

    void uploads(Action<? super NamedDomainObjectContainer<UploadConfig>> action) {
        action.execute(uploads)
    }

    @TaskAction
    def run() {
        validate()
        initK8sClient()
        def deploy

        if (uploadFilesOnly) {
            uploadFiles()
            return
        } else {
            deploy = k8sClient.apps().deployments().withName(deployment.name).get()
            if (deploy == null) {
                throw new GradleException("deployment ${deployment.name} not exist")
            }
            if (restore) {
                restoreDeployment(deploy)
                return
            }
            uploadFiles()
        }

        def serviceName = "${deployment.name}-debug"
        def debugService = k8sClient.services().withName(serviceName).get()
        def debugEnabled = debugService == null

        def port = debugEnabled ? getUsablePort() : debugService.getSpec().getPorts().get(0).getNodePort()
        if (!debugEnabled) {
            createDebugService(serviceName, deployment.name, port)
        }

        def envs = deploy.getSpec().getTemplate().getSpec().getContainers()
                .get(0).getEnv().findAll({ it.name == JAVA_OPTIONS })

        if (envs.isEmpty()) {
            backupResource(deploy)
        }

        if (envs.isEmpty() || !envs.get(0).getValue().contains(String.valueOf(port))) {
            editDeployment(deploy, port)
        }
    }

    private def validate() {
        if (StrUtil.isBlank(k8s.host)) {
            throw new GradleException("should specify k8s ${k8s.host}")
        }
        if (StrUtil.isBlank(deployment.name)) {
            throw new GradleException("should specify debug deploy name")
        }
    }

    private def initK8sClient() {
        k8sClient = new DefaultKubernetesClient(new ConfigBuilder()
                .withMasterUrl("https://${k8s.host}:${k8s.port}")
                .withNamespace(k8s.namespace)
                .withTrustCerts(true)
                .withPassword(k8s.passwd)
                .withUsername(k8s.user)
                .build())
        logger.lifecycle("connected to k8s master node via: https://${k8s.host}:${k8s.port}")
    }

    private def editDeployment(Deployment deploy, int debugPort) {
        if (!deployment.healthCheck) {
            deploy.getSpec().getTemplate().getSpec().getContainers().get(0).setLivenessProbe(null)
            deploy.getSpec().getTemplate().getSpec().getContainers().get(0).setReadinessProbe(null)
        }

        def container = new DeploymentBuilder(deploy).editSpec()
                .withReplicas(deployment.replicas)
                .editTemplate()
                .editSpec()
                .editFirstContainer()

        def value = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=$debugPort"
        if (container.hasMatchingEnv({ it.getName() == JAVA_OPTIONS })) {
            container.editMatchingEnv({ it.getName() == JAVA_OPTIONS }).withValue(value)
        } else {
            container.addToEnv(new EnvVarBuilder()
                    .withName(JAVA_OPTIONS)
                    .withValue(value)
                    .build())
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

        k8sClient.apps().deployments().replace(container.endContainer().endSpec().endTemplate().endSpec().build())
        logger.lifecycle("deployment ${deployment.name} updated")
    }

    private def createDebugService(String serviceName, String appName, int port) {
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
                                .withIntVal(port)
                                .build())
                        .withPort(port).build())
                .endSpec().build())
        logger.lifecycle("node service $serviceName created, port: $port")
    }

    private int getUsablePort() {
        int port = 30055
        while (NetUtil.isOpen(new InetSocketAddress(k8s.host, port), TimeUnit.SECONDS.toMillis(3).intValue())) {
            logger.lifecycle("testing port $port on ${k8s.host}")
            port++
        }
        return port
    }

    private void backupResource(HasMetadata resource) {
        def name = "${k8s.host}-$resource.metadata.name"
        try {
            def yaml = SerializationUtils.dumpWithoutRuntimeStateAsYaml(resource)
            def file = new File(BACKUP_DIR, name)
            FileUtil.writeUtf8String(yaml, file)
            logger.lifecycle("backup deployment ${deployment.name} to $file.canonicalPath")
        } catch (Exception e) {
            throw new GradleException("backup resource $name error", e)
        }
    }

    private void restoreDeployment(HasMetadata resource) {
        def name = "$host-$resource.metadata.name"
        def backupFile = new File(BACKUP_DIR, name)
        if (!backupFile.exists()) {
            throw new GradleException("no backup file to reset $name")
        }
        def deploy = k8sClient.apps().deployments().load(backupFile).get()
        k8sClient.apps().deployments().createOrReplace(deploy)
        logger.lifecycle("success to restore $deployName")
    }

    private void uploadFiles() {
        if (uploads == null && uploads.isEmpty()) {
            return
        }
        for (file in uploads) {
            upload(file)
        }
    }

    private void upload(UploadConfig uploadFile) {
        uploadFile.validate()
        def pod
        if (StrUtil.isNotBlank(uploadFile.deployName)) {
            pod = k8sClient.pods().withLabel("app", uploadFile.deployName).list().getItems().find { it.getStatus().getPhase() == 'Running' }
        } else if (StrUtil.isNotBlank(uploadFile.podName)) {
            pod = k8sClient.pods().withName(uploadFile.podName).get()
        } else {
            throw new GradleException("deployName or podName not define for the upload file $name")
        }

        def local = new File(uploadFile.localPath)
        boolean dir = local.isDirectory()

        ContainerResource<LogWatch, InputStream, PipedOutputStream, OutputStream, PipedInputStream, String, ExecWatch, Boolean, InputStream, Boolean> container =
                k8sClient.pods().withName(pod.metadata.name).inContainer(getContainerName(uploadFile.containerName, pod))

        CopyOrReadable<Boolean, InputStream, Boolean> selector
        def podPath = genDestPath(uploadFile.localPath, uploadFile.remotePath)
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

    private static String getContainerName(String name, Pod pod) {
        return StrUtil.isNotBlank(name) ? name : pod.getSpec().getContainers().get(0).getName()
    }

    private static String genDestPath(String src, String dest) {
        String separator = "/"
        boolean dir = FileNameUtil.extName(src).isEmpty()
        if (dir) {
            // if src is a dir and dest is a dir with '/'
            if (dest.endsWith(separator)) {
                String dirName = FileNameUtil.getName(StrUtil.removeSuffix(src, separator))
                dest += dirName
            }
        } else {
            // if src is a file and dest is a dir
            if (FileNameUtil.extName(dest).isEmpty()) {
                dest = StrUtil.removeSuffix(dest, separator) + separator + FileNameUtil.getName(src)
            }
        }
        return dest
    }
}
