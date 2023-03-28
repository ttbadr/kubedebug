package com.mk.kube.debug.utils

import cn.hutool.core.io.FileUtil
import cn.hutool.core.io.file.FileNameUtil
import io.fabric8.kubernetes.api.model.Pod
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

import java.security.PublicKey
import java.util.concurrent.TimeUnit

class SshClient {
    private static final Logger logger = Logging.getLogger(SshClient)
    private final SSHClient ssh
    private final String host

    SshClient(String host) {
        ssh = new SSHClient()
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
        this.host = host
    }

    private def checkConnection() {
        if (!ssh.isConnected()) {
            ssh.connect(host)
        }
        if (!ssh.isAuthenticated()) {
            ssh.authPassword('root', 'root1234')
        }
    }

    def scp(Pod pod, String local, String remote) {
        checkConnection()

        def podName = pod.metadata.name
        def tempDir = '/tmp/kubeDebug/'
        def temp = PathUtil.genDestPath(local, tempDir)

        //create temp folder and remote folder in pod
        ssh.startSession().withCloseable {
            it.exec("mkdir -p $tempDir").join()
        }

        //scp file from local to temp
        logger.lifecycle("scp file from $local to $temp")

        def transfer = ssh.newSCPFileTransfer()
        transfer.setTransferListener(new SimpleTransferListener())
        transfer.upload(local, temp)

        //cp file from temp to pod
        logger.lifecycle("kubectl cp file from $temp to $remote")
        exec("kubectl cp $temp $podName:$remote -n ${pod.metadata.namespace} && rm -rf $temp")
    }

    def exec(String cmd, retry = 3) {
        def result = ''
        ssh.startSession().withCloseable {
            def command = it.exec(cmd)
            command.join(8, TimeUnit.MINUTES)
            if (command.exitStatus != 0) {
                if (retry > 0) {
                    logger.lifecycle("retry to command cmd in master node: $cmd")
                    exec(cmd, retry - 1)
                } else {
                    throw new GradleException("fail to command cms in master node, exit code $command.exitStatus.\n$cmd\n${IOUtils.readFully(command.getErrorStream()).toString()}")
                }
            } else {
                result = IOUtils.readFully(command.getInputStream()).toString()
            }
        }
        return result
    }

    def kubeCopy(String from, String to, String namespace) {
        return exec("kubectl cp $from $to -n $namespace")
    }

    def listFolder(String folder) {
        return exec("find ${folder} -maxdepth 1 -type f -exec basename {} \\;").readLines()
    }

    def upload(String src, String target) throws IOException {
        checkConnection()
        if (FileNameUtil.extName(target).isEmpty()) {
            ssh.startSession().withCloseable {
                it.exec("mkdir -p $target").join()
            }
        }
        ssh.newSCPFileTransfer().upload(src, target)
    }

    def download(String remote, String local) throws IOException {
        checkConnection()
        ssh.newSCPFileTransfer().download(remote, local)
    }

    def getFileContent(String remote) {
        checkConnection()
        def tempKubeConfigFile = new File(FileUtil.getTmpDir(), "kubeConfig.yaml")
        FileUtil.del(tempKubeConfigFile)
        download(remote, tempKubeConfigFile.getAbsolutePath())
        String content = FileUtil.readUtf8String(tempKubeConfigFile)
        return content
    }

    def getKubeConfigFilePath() {
        checkConnection()
        def client = ssh.newSFTPClient()
        if (client.statExistence('/root/.kube/config') != null) {
            return '/root/.kube/config'
        }
        def ls = client.ls('/home')
        for (final def folder in ls) {
            def kubeFolder = client.ls(folder.path).findAll { it.name == '.kube' }
            if (kubeFolder.size() > 0 && exist(kubeFolder.first().path + '/config')) {
                return kubeFolder.first().path + '/config'
            }
        }
        return null
    }

    boolean exist(String path) {
        checkConnection()
        ssh.newSFTPClient().statExistence(path) != null
    }
}
