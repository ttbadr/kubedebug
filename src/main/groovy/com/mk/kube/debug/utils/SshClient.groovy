package com.mk.kube.debug.utils

import cn.hutool.core.io.file.FileNameUtil
import io.fabric8.kubernetes.api.model.Pod
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.connection.channel.direct.Session
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
        ssh.newSCPFileTransfer().upload(local, temp)

        //cp file from temp to pod
        logger.lifecycle("kubectl cp file from $temp to $remote")
        ssh.startSession().withCloseable {
            kubectlCopy(it, "kubectl cp $temp $podName:$remote && rm -rf $temp")
        }
    }

    def kubectlCopy(Session session, String cmd, retry = 3) {
        def exec = session.exec(cmd)
        exec.join(8, TimeUnit.MINUTES)
        if (exec.exitStatus != 0) {
            if (retry > 0) {
                logger.lifecycle("kubectl cp failed, retry...")
                kubectlCopy(session, cmd, retry - 1)
            } else {
                throw new GradleException("kubectl cp file failed. exit code $exec.exitStatus, ${IOUtils.readFully(exec.getErrorStream()).toString()}")
            }
        }
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

    boolean exist(String path) {
        checkConnection()
        ssh.newSFTPClient().statExistence(path) != null
    }
}
