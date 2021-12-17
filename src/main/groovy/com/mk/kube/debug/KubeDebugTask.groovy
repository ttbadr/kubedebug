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
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.gradle.api.*
import org.gradle.api.tasks.TaskAction

import javax.inject.Inject
import java.security.PublicKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class KubeDebugTask extends DefaultTask {
    boolean restore = false
    boolean debug = true
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
        }

        deploy = k8sClient.apps().deployments().withName(deployment.name).get()
        if (deploy == null) {
            throw new GradleException("deployment ${deployment.name} not exist")
        }
        if (restore) {
            restoreDeployment(deploy)
            return
        }
        uploadFiles()

        if (!debug) {
            k8sClient.apps().deployments().withName(deployment.name).scale(0)
            TimeUnit.SECONDS.sleep(2)
            k8sClient.apps().deployments().withName(deployment.name).scale(deploy.spec.replicas)
            logger.lifecycle("$deployment.name restarted")
            return
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
        def podPath = genDestPath(uploadFile.localPath, uploadFile.remotePath)
        def pod
        if (StrUtil.isNotBlank(uploadFile.deployName)) {
            pod = k8sClient.pods().withLabel("app", uploadFile.deployName).list().getItems().find { it.getStatus().getPhase() == 'Running' }
        } else if (StrUtil.isNotBlank(uploadFile.podName)) {
            pod = k8sClient.pods().withName(uploadFile.podName).get()
        } else {
            throw new GradleException("deployName or podName not define for the upload file $name")
        }

        if (StrUtil.isNotBlank(uploadFile.beforeUpload)) {
            exec(pod.metadata.name, uploadFile.containerName, 60, uploadFile.beforeUpload)
        }

        //if file lower than 20M then kubeCopy, scp otherwise
        if (FileUtil.size(new File(uploadFile.localPath)) <= 20971520) {
            kubeCopy(pod, uploadFile)
        } else {
            scp(pod, uploadFile.containerName, uploadFile.localPath, podPath)
        }

        if (StrUtil.isNotBlank(uploadFile.afterUpload)) {
            exec(pod.metadata.name, uploadFile.containerName, 60, uploadFile.afterUpload)
        }
    }

    static String getContainerName(String name, Pod pod) {
        return StrUtil.isNotBlank(name) ? name : pod.getSpec().getContainers().get(0).getName()
    }

    /**
     * generate destination path
     * <p>eg:
     * <br>  (/o/p/t.img, /o/p)     -> /o/p/t.img
     * <br>  (/o/p/t.img, /o/p/)    -> /o/p/t.img
     * <br>  (/o/p/t.img, /o/p.img) -> /o/p.img
     * <br>  (/o/p/t, /o/p)         -> /o/p
     * <br>  (/o/p/t, /o/p/)        -> /o/p/t
     * <br>  (/o/p/t/, /o/p/)       -> /o/p/t
     * @param src src path
     * @param dest dest path
     * @return new dest path
     */
    static String genDestPath(String src, String dest) {
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

    private void scp(Pod pod, String containerName, String local, String remote) {
        def podName = pod.metadata.name
        def ssh = new SSHClient()
        ssh.addHostKeyVerifier(new HostKeyVerifier() {
            @Override
            boolean verify(String hostname, int port, PublicKey key) {
                return true
            }

            @Override
            List<String> findExistingAlgorithms(String hostname, int port) {
                return Collections.emptyList()
            }
        })
        ssh.connect(k8s.host)
        ssh.withCloseable {
            ssh.authPassword('root', 'root1234')
            def tempDir = '/tmp/kubeDebug/'
            def temp = genDestPath(local, tempDir)

            //create temp folder and remote folder in pod
            ssh.startSession().withCloseable {
                it.exec("mkdir -p $tempDir").join()
            }
            exec(podName, getContainerName(containerName, pod), 5, "mkdir -p ${getParent(remote)}")

            //scp file from local to temp
            logger.lifecycle("scp file from $local to $temp")
            ssh.newSCPFileTransfer().upload(local, temp)

            //cp file from temp to pod
            logger.lifecycle("kubectl cp file from $temp to $remote")
            ssh.startSession().withCloseable {
                def exec = it.exec("kubectl cp $temp $podName:$remote && rm -rf $temp")
                System.console().print(IOUtils.readFully(exec.getInputStream()).toString())
                System.console().print(IOUtils.readFully(exec.getErrorStream()).toString())
                exec.join(8, TimeUnit.MINUTES)
                if (exec.exitStatus != 0) {
                    throw new GradleException("kubectl cp file failed. exit code $exec.exitStatus")
                }
            }
        }
    }

    private void kubeCopy(Pod pod, UploadConfig uploadFile) {
        def local = new File(uploadFile.localPath)
        boolean dir = local.isDirectory()

        ContainerResource<LogWatch, InputStream, PipedOutputStream, OutputStream, PipedInputStream, String, ExecWatch, Boolean, InputStream, Boolean> container =
                k8sClient.pods().withName(pod.metadata.name).inContainer(getContainerName(uploadFile.containerName, pod))

        def podPath = genDestPath(uploadFile.localPath, uploadFile.remotePath)
        CopyOrReadable<Boolean, InputStream, Boolean> selector
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

    static String getParent(String path) {
        return StrUtil.subBefore(path, '/', true)
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
}
