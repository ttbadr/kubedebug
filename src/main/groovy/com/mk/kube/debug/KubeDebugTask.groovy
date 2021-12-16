package com.mk.kube.debug

import cn.hutool.core.io.FileUtil
import cn.hutool.core.io.file.FileNameUtil
import cn.hutool.core.net.NetUtil
import cn.hutool.core.util.StrUtil
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
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.TaskAction

import java.util.concurrent.TimeUnit

class KubeDebugTask extends DefaultTask {
    boolean restore = false
    boolean uploadFilesOnly = false
    String host
    int port = 6443
    String user = 'mdt-admin'
    String passwd = 'youmustchangeme'
    String namespace = 'mkcms'
    String deployName
    boolean readOnly = true
    boolean runAsRoot = false
    int replicas = 1
    boolean healthCheck = false
    List<String> commands
    UploadConfig[] uploads

    def JAVA_OPTIONS = '_JAVA_OPTIONS'
    def BACKUP_DIR = new File(FileUtil.getUserHomeDir(), ".cms/backup/")
    KubernetesClient k8sClient

    @TaskAction
    def run() {
        validate()
        initK8sClient()
        def deployment

        if (uploadFilesOnly) {
            uploadFiles()
            return
        } else {
            deployment = k8sClient.apps().deployments().withName(deployName).get()
            if (deployment == null) {
                throw new GradleException("deployment $deployName not exist")
            }
            if (restore) {
                restoreDeployment(deployment)
                return
            }
            uploadFiles()
        }

        def serviceName = "$deployName-debug"
        def debugService = k8sClient.services().withName(serviceName).get()
        def debugEnabled = debugService == null

        def port = debugEnabled ? getUsablePort() : debugService.getSpec().getPorts().get(0).getNodePort()
        if (!debugEnabled) {
            createDebugService(serviceName, deployName, port)
        }

        def envs = deployment.getSpec().getTemplate().getSpec().getContainers()
                .get(0).getEnv().findAll({ it.name == JAVA_OPTIONS })

        if (envs.isEmpty()) {
            backupResource(deployment)
        }

        if (envs.isEmpty() || !envs.get(0).getValue().contains(String.valueOf(port))) {
            editDeployment(deployment, port)
        }
    }

    def validate() {
        if (StrUtil.isBlank(host)) {
            throw new GradleException("should specify k8s host")
        }
        if (StrUtil.isBlank(deployName)) {
            throw new GradleException("should specify debug deploy name")
        }
    }

    def initK8sClient() {
        k8sClient = new DefaultKubernetesClient(new ConfigBuilder()
                .withMasterUrl("https://$host:$port")
                .withNamespace(namespace)
                .withTrustCerts(true)
                .withPassword(passwd)
                .withUsername(user)
                .build())
        logger.lifecycle("connected to k8s master node via: https://$host:$port")
    }

    def editDeployment(Deployment deployment, int debugPort) {
        if (!healthCheck) {
            deployment.getSpec().getTemplate().getSpec().getContainers().get(0).setLivenessProbe(null)
            deployment.getSpec().getTemplate().getSpec().getContainers().get(0).setReadinessProbe(null)
        }

        def container = new DeploymentBuilder(deployment).editSpec()
                .withReplicas(replicas)
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


        if (runAsRoot) {
            container.editSecurityContext().withRunAsUser(0).withRunAsNonRoot(false).endSecurityContext()
        }

        if (!readOnly) {
            container.editSecurityContext().withReadOnlyRootFilesystem(false).endSecurityContext()
        }

        if (commands != null && commands.size() > 0) {
            container.withCommand(commands).withArgs(null)
        }

        k8sClient.apps().deployments().replace(container.endContainer().endSpec().endTemplate().endSpec().build())
        logger.lifecycle("deployment $deployName updated")
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
                                .withIntVal(port)
                                .build())
                        .withPort(port).build())
                .endSpec().build())
        logger.lifecycle("node service $serviceName created, port: $port")
    }

    int getUsablePort() {
        int port = 30055
        while (NetUtil.isOpen(new InetSocketAddress(host, port), TimeUnit.SECONDS.toMillis(3).intValue())) {
            logger.lifecycle("testing port $port on $host")
            port++
        }
        return port
    }

    def backupResource(HasMetadata resource) {
        def name = "$host-$resource.metadata.name"
        try {
            def yaml = SerializationUtils.dumpWithoutRuntimeStateAsYaml(resource)
            def file = new File(BACKUP_DIR, name)
            FileUtil.writeUtf8String(yaml, file)
            logger.lifecycle("backup deployment $deployName to $file.canonicalPath")
        } catch (Exception e) {
            throw new GradleException("backup resource $name error", e)
        }
    }

    def restoreDeployment(HasMetadata resource) {
        def name = "$host-$resource.metadata.name"
        def backupFile = new File(BACKUP_DIR, name)
        if (!backupFile.exists()) {
            throw new GradleException("no backup file to reset $name")
        }
        def deploy = k8sClient.apps().deployments().load(backupFile).get()
        k8sClient.apps().deployments().createOrReplace(deploy)
        logger.lifecycle("success to restore $deployName")
    }

    def uploadFiles() {
        if (uploads == null && uploadFiles().size() == 0) {
            return
        }
        uploads.each {
            upload(it)
        }
    }

    def upload(UploadConfig uploadFile) {
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
        try {
            selector.upload(local.toPath())
            logger.lifecycle("finish to upload $uploadFile.localPath to $pod.metadata.name:$podPath")
        } catch (Exception e) {
            throw new GradleException("fail to upload $uploadFile.localPath to $pod.metadata.name:$podPath", e)
        }
    }

    static def getContainerName(String name, Pod pod) {
        return StrUtil.isNotBlank(name) ? name : pod.getSpec().getContainers().get(0).getName()
    }

    static def genDestPath(String src, String dest) {
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
