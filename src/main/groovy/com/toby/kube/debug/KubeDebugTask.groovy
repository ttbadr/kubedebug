package com.toby.kube.debug

import cn.hutool.core.io.FileUtil
import cn.hutool.core.io.file.FileNameUtil
import cn.hutool.core.net.NetUtil
import cn.hutool.core.util.StrUtil
import com.toby.kube.debug.config.K8sConfig
import com.toby.kube.debug.config.DeployConfig
import com.toby.kube.debug.config.UploadConfig
import com.toby.kube.debug.config.ZipPatch
import com.toby.kube.debug.utils.KubeClient
import com.toby.kube.debug.utils.PathUtil
import com.toby.kube.debug.utils.SshClient
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.utils.Serialization
import org.gradle.api.*
import org.gradle.api.tasks.TaskAction

import javax.inject.Inject
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

class KubeDebugTask extends DefaultTask {
    private static final String REMOTE_BACKUP_DIR = "/tmp/kubeDebug/backup/"

    NamedDomainObjectContainer<UploadConfig> uploads
    K8sConfig k8sConfig
    DeployConfig deployment
    ZipPatch zipPatch

    private KubeClient k8sClient
    private SshClient sshClient

    @Inject
    KubeDebugTask(Project project) {
        k8sConfig = project.objects.newInstance(K8sConfig)
        deployment = project.objects.newInstance(DeployConfig)
        zipPatch = project.objects.newInstance(ZipPatch)
        uploads = project.container(UploadConfig)
    }

    void k8s(Action<? super K8sConfig> action) {
        action.execute(k8sConfig)
    }

    void zipPatch(Action<? super ZipPatch> action) {
        action.execute(zipPatch)
    }

    void deployment(Action<? super DeployConfig> action) {
        action.execute(deployment)
    }

    void uploads(Action<? super NamedDomainObjectContainer<UploadConfig>> action) {
        action.execute(uploads)
    }

    @TaskAction
    private void run() {
        init()

        uploadFiles()
        processZipPatch()
        editDeployment()
    }

    private void editDeployment() {
        //check deployment is valid
        if (StrUtil.isBlank(deployment.name)) {
            return
        }
        def deploy = k8sClient.getDeploy(deployment.name)
        if (deploy == null) {
            throw new GradleException("deployment ${deployment.name} not exist")
        }

        //backup if deployment not update by this plugin
        if (!deploy.metadata.labels.containsKey("kubeDebug")) {
            backupResource(deploy)
        }

        //restore if configured
        if (deployment.restore) {
            restoreDeployment(deploy)
            return
        }

        int port = createDebugService()

        //before update deployment, scale to 0
        k8sClient.scale(deploy.metadata.name, 0)
        //update deployment
        def newDeployment = k8sClient.updateDeployment(deploy, deployment, deployment.debug, port)
        //scale to original replica or configured replica
        def replicas = deployment.replicas == 0 ? deploy.spec.replicas : deployment.replicas
        //set replica to 1 if original replica is 0
        replicas = replicas == 0 ? 1 : replicas
        //if debug enable, force scale to 1
        k8sClient.scale(newDeployment.metadata.name, deployment.debug ? 1 : replicas)

        if (port > 0) {
            logger.lifecycle("$deployment.name debugable on $k8sConfig.host:$port")
        } else {
            logger.lifecycle("$deployment.name restarted")
        }
    }

    private int createDebugService() {
        def port = 0
        if (deployment.debug) {
            def serviceName = "${deployment.name}-debug"
            def debugService = k8sClient.getService(serviceName)
            def isServiceCreated = debugService != null

            port = isServiceCreated ? debugService.getSpec().getPorts().get(0).getNodePort() : getUsablePort()
            if (!isServiceCreated) {
                k8sClient.createDebugService(serviceName, deployment.name, port)
            }
        }
        port
    }

    private void processZipPatch() {
        if (StrUtil.isBlank(zipPatch.to)) {
            return
        }
        logger.lifecycle("start to patch zip")
        zipPatch.validate()
        def tempFileName = 'temp.zip'
        def tempZipFile = "/tmp/$tempFileName"
        def tempZipFolder = '/tmp/tempZip'
        def fromPod = k8sClient.getPodByAppName(zipPatch.srcApp).metadata.name
        def toPod = k8sClient.getPodByAppName(zipPatch.toApp).metadata.name

        // download file from target or fromPath to the temp folder
        if (k8sClient.exist(toPod, zipPatch.toPath)) {
            logger.lifecycle("downloading file from $toPod:$zipPatch.toPath to $tempZipFile")
            sshClient.kubeCopy("$toPod:$zipPatch.toPath", tempZipFile, k8sConfig.namespace)
        } else {
            logger.lifecycle("$zipPatch.toPath not exist in pod $toPod")
            if (StrUtil.hasBlank(zipPatch.srcPath, fromPod)) {
                return
            } else {
                logger.lifecycle("downloading file from $fromPod:$zipPatch.srcPath to $tempZipFile")
                sshClient.kubeCopy("$fromPod:$zipPatch.srcPath", tempZipFile, k8sConfig.namespace)
            }
        }
        if (!sshClient.exist(tempZipFile)) {
            logger.lifecycle("Could not download file from pod")
            return
        }
        //unzip file
        logger.lifecycle("unzip $tempZipFile to $tempZipFolder")
        sshClient.exec("rm -rf $tempZipFolder;unzip -q $tempZipFile -d $tempZipFolder && rm -f $tempZipFile")
        for (final def e in zipPatch.patch.entrySet()) {
            def localFilePath = e.key.canonicalPath
            def target = determineTargetFilePath(tempZipFolder, localFilePath, e.value)
            logger.lifecycle("patch $target with $localFilePath")
            sshClient.upload(localFilePath, target)
        }
        //zip file
        logger.lifecycle("check zip cmd installation...")
        sshClient.exec('which zip || yum install zip -y')
        logger.lifecycle("packing folder $tempZipFolder to $tempZipFile")
        sshClient.exec("cd $tempZipFolder && zip -qr ../$tempFileName ./ && cd .. && rm -rf $tempZipFolder")
        //upload temp zip to target
        logger.lifecycle("upload $tempZipFile to $toPod:$zipPatch.toPath")
        k8sClient.exec(toPod, "mkdir -p ${StrUtil.subBefore(zipPatch.toPath, '/', true)}")
        sshClient.kubeCopy(tempZipFile, "$toPod:$zipPatch.toPath", k8sConfig.namespace)
    }

    private String determineTargetFilePath(String folderPath, String src, String targetFolder) {
        def name = FileNameUtil.getName(src)

        //upload to zip root folder
        if (targetFolder == '.' || targetFolder == './') {
            targetFolder = ''
        }

        def split = StrUtil.subAfter(name, '-', true)
        //not contain version
        if (split.isEmpty() || !split.contains('.')) {
            return Paths.get(folderPath, targetFolder, name).toUnixPath()
        } else {
            def files = sshClient.listFolder(Paths.get(folderPath, targetFolder).toUnixPath())
            def nameWithoutVersion = StrUtil.subBefore(name, '-', true)
            def matches = files.findAll { StrUtil.subBefore(it, '-', true) == nameWithoutVersion }
            if (matches.size() != 1) {
                throw new GradleException("find ${matches.size()} in $folderPath startWith $nameWithoutVersion")
            }
            return Paths.get(folderPath, targetFolder, matches.first()).toUnixPath()
        }
    }

    private def init() {
        k8sConfig.validate()
        sshClient = new SshClient(k8sConfig.host, k8sConfig.sshUser, k8sConfig.sshPasswd, k8sConfig.sshPort)
        k8sClient = new KubeClient(getKubeConfig(), k8sConfig.namespace)

        Path.metaClass.toUnixPath = { -> return delegate.toString().replace('\\', '/') }
    }

    private String getKubeConfig() {
        def path = sshClient.getKubeConfigFilePath()
        if (StrUtil.isBlank(path)) {
            throw new GradleException("could not find kubeConfig file in $k8sConfig.host")
        }
        def kubeConfig = sshClient.getFileContent(path)
        if (StrUtil.isBlank(kubeConfig)) {
            throw new GradleException("could not get kubeConfig content from $k8sConfig.host/$path")
        }
        return kubeConfig
    }

    private int getUsablePort() {
        int port = 30055
        while (NetUtil.isOpen(new InetSocketAddress(k8sConfig.host, port), TimeUnit.SECONDS.toMillis(3).intValue())) {
            logger.lifecycle("testing port $port on ${k8sConfig.host}")
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
        def podPath = PathUtil.genDestPath(uploadFile.from, uploadFile.toPath)
        def pod = k8sClient.getPodByAppName(uploadFile.toApp)

        if (StrUtil.isNotBlank(uploadFile.beforeUpload)) {
            k8sClient.exec(pod.metadata.name, uploadFile.beforeUpload)
        }

        if(uploadFile.transitMode){
            def parent = PathUtil.getParent(podPath)
            k8sClient.exec(pod.metadata.name, "[ ! -e $parent ] && mkdir -p $parent")
            sshClient.scp(pod, uploadFile.from, podPath)
        } else {
            k8sClient.copy(pod, uploadFile)
        }

        if (StrUtil.isNotBlank(uploadFile.afterUpload)) {
            k8sClient.exec(pod.metadata.name, uploadFile.afterUpload)
        }
    }

    private def backupResource(HasMetadata resource) {
        def name = "${resource.metadata.name}.yaml"
        try {
            def yaml = Serialization.asYaml(resource)
            def file = new File(FileUtil.getTmpDir(), name)
            FileUtil.writeUtf8String(yaml, file)
            sshClient.upload(file.canonicalPath, REMOTE_BACKUP_DIR)
            file.delete()
            logger.lifecycle("backup deployment ${resource.metadata.name} to ${k8sConfig.host}:$REMOTE_BACKUP_DIR$name")
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
