package com.mk.kube.debug

import cn.hutool.core.io.FileUtil
import cn.hutool.core.net.NetUtil
import cn.hutool.core.util.StrUtil
import com.mk.kube.debug.config.DeployConfig
import com.mk.kube.debug.config.K8sConfig
import com.mk.kube.debug.config.UploadConfig
import com.mk.kube.debug.utils.KubeClient
import com.mk.kube.debug.utils.PathUtil
import com.mk.kube.debug.utils.SshClient
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.client.internal.SerializationUtils
import org.gradle.api.*
import org.gradle.api.tasks.TaskAction

import javax.inject.Inject
import java.util.concurrent.TimeUnit

class KubeDebugTask extends DefaultTask {
    private static final String REMOTE_BACKUP_DIR = "/tmp/kubeDebug/backup/"
    boolean restore = false
    boolean debug = true
    boolean uploadFilesOnly = false

    NamedDomainObjectContainer<UploadConfig> uploads
    K8sConfig k8s
    DeployConfig deployment

    private KubeClient k8sClient
    private SshClient sshClient

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
    private def run() {
        validate()
        init()
        def deploy

        if (uploadFilesOnly) {
            uploadFiles()
            return
        }

        deploy = k8sClient.getDeploy(deployment.name)
        if (deploy == null) {
            throw new GradleException("deployment ${deployment.name} not exist")
        }
        if (restore) {
            restoreDeployment(deploy)
            return
        }
        uploadFiles()

        if (!debug) {
            restart(deploy)
            return
        }

        def serviceName = "${deployment.name}-debug"
        def debugService = k8sClient.getService(serviceName)
        def isServiceCreated = debugService != null

        def port = isServiceCreated ? debugService.getSpec().getPorts().get(0).getNodePort() : getUsablePort()
        if (!isServiceCreated) {
            k8sClient.createDebugService(serviceName, deployment.name, port)
        }

        def envs = KubeClient.getEnvs(deploy, KubeClient.JAVA_OPTIONS)

        if (envs.isEmpty()) {
            backupResource(deploy)
        }

        if (envs.isEmpty() || !envs.get(0).getValue().contains(String.valueOf(port))) {
            k8sClient.debugDeployment(deploy, deployment, port)
        } else {
            restart(deploy)
        }
    }

    private def restart(Deployment deploy) {
        k8sClient.scale(deploy.metadata.name, 0)
        TimeUnit.SECONDS.sleep(2)
        k8sClient.scale(deploy.metadata.name, deploy.spec.replicas)
        logger.lifecycle("${deploy.metadata.name} restarted")
    }

    private def validate() {
        if (StrUtil.isBlank(k8s.host)) {
            throw new GradleException("should specify k8s ${k8s.host}")
        }
        if (StrUtil.isBlank(deployment.name)) {
            throw new GradleException("should specify debug deploy name")
        }
    }

    private def init() {
        k8sClient = new KubeClient(k8s)
        sshClient = new SshClient(k8s.host)
    }

    private int getUsablePort() {
        int port = 30055
        while (NetUtil.isOpen(new InetSocketAddress(k8s.host, port), TimeUnit.SECONDS.toMillis(3).intValue())) {
            logger.lifecycle("testing port $port on ${k8s.host}")
            port++
        }
        return port
    }

    private void uploadFiles() {
        if (uploads == null && uploads.isEmpty()) {
            return
        }
        for (file in uploads) {
            upload(file)
        }
    }

    private def upload(UploadConfig uploadFile) {
        uploadFile.validate()
        def podPath = PathUtil.genDestPath(uploadFile.localPath, uploadFile.remotePath)
        def pod
        if (StrUtil.isNotBlank(uploadFile.deployName)) {
            pod = k8sClient.getRunningPodByLabel('app', uploadFile.deployName)
        } else if (StrUtil.isNotBlank(uploadFile.podName)) {
            pod = k8sClient.getPod(uploadFile.podName)
        } else {
            throw new GradleException("deployName or podName not define for the upload file $name")
        }

        if (StrUtil.isNotBlank(uploadFile.beforeUpload)) {
            k8sClient.exec(pod.metadata.name, uploadFile.containerName, 60, uploadFile.beforeUpload)
        }

        //if file lower than 20M then kubeCopy, scp otherwise
        if (FileUtil.size(new File(uploadFile.localPath)) <= 20971520) {
            k8sClient.copy(pod, uploadFile)
        } else {
            def containerName = KubeClient.getContainerName(uploadFile.containerName, pod)
            k8sClient.exec(pod.metadata.name, containerName, 5, "mkdir -p ${PathUtil.getParent(podPath)}")
            sshClient.scp(pod, uploadFile.localPath, podPath)
        }

        if (StrUtil.isNotBlank(uploadFile.afterUpload)) {
            k8sClient.exec(pod.metadata.name, uploadFile.containerName, 60, uploadFile.afterUpload)
        }
    }

    private def backupResource(HasMetadata resource) {
        def name = "${resource.metadata.name}.yaml"
        try {
            def yaml = SerializationUtils.dumpWithoutRuntimeStateAsYaml(resource)
            def file = new File(FileUtil.getTmpDir(), name)
            FileUtil.writeUtf8String(yaml, file)
            sshClient.upload(file.canonicalPath, REMOTE_BACKUP_DIR)
            file.delete()
            logger.lifecycle("backup deployment ${resource.metadata.name} to ${k8s.host}:$REMOTE_BACKUP_DIR/$name")
        } catch (Exception e) {
            throw new GradleException("backup resource $name error", e)
        }
    }

    private def restoreDeployment(HasMetadata resource) {
        def name = "${resource.metadata.name}.yaml"
        def backupFile = new File(FileUtil.getTmpDir(), name)
        if (sshClient.exist(REMOTE_BACKUP_DIR + name)) {
            sshClient.download(REMOTE_BACKUP_DIR + name, backupFile.canonicalPath)
        } else {
            throw new GradleException("no backup file to restore $name")
        }
        k8sClient.restore(backupFile)
        logger.lifecycle("success to restore ${resource.metadata.name}")
    }
}
