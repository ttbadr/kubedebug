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
import org.gradle.api.*
import org.gradle.api.tasks.TaskAction

import javax.inject.Inject
import java.util.concurrent.TimeUnit

class KubeDebugTask extends DefaultTask {
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
            k8sClient.restoreDeployment(k8s, deploy)
            return
        }
        uploadFiles()

        if (!debug) {
            k8sClient.scale(deployment.name, 0)
            TimeUnit.SECONDS.sleep(2)
            k8sClient.scale(deployment.name, deploy.spec.replicas)
            logger.lifecycle("$deployment.name restarted")
            return
        }

        def serviceName = "${deployment.name}-debug"
        def debugService = k8sClient.getService(serviceName)
        def debugEnabled = debugService == null

        def port = debugEnabled ? getUsablePort() : debugService.getSpec().getPorts().get(0).getNodePort()
        if (!debugEnabled) {
            k8sClient.createDebugService(serviceName, deployment.name, port)
        }

        def envs = KubeClient.getEnvs(deploy, KubeClient.JAVA_OPTIONS)

        if (envs.isEmpty()) {
            k8sClient.backupResource(k8s, deploy)
        }

        if (envs.isEmpty() || !envs.get(0).getValue().contains(String.valueOf(port))) {
            k8sClient.debugDeployment(deploy, deployment, port)
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

    private def init() {
        KubeClient.init(k8s)
        SshClient.init(k8s.host)
        k8sClient = KubeClient.get()
        sshClient = SshClient.get()
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
            sshClient.scp(pod, uploadFile.containerName, uploadFile.localPath, podPath)
        }

        if (StrUtil.isNotBlank(uploadFile.afterUpload)) {
            k8sClient.exec(pod.metadata.name, uploadFile.containerName, 60, uploadFile.afterUpload)
        }
    }
}
