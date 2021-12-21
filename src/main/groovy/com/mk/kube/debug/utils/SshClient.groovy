package com.mk.kube.debug.utils

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
    private static SshClient instant
    private final SSHClient ssh

    private SshClient(String host) {
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
        ssh.connect(host)
    }

    static init(String host) {
        instant = new SshClient(host)
    }

    static SshClient get() {
        if (instant == null) {
            throw new GradleException("SshClient not initialized")
        }
        return instant
    }

    def scp(Pod pod, String containerName, String local, String remote) {
        def podName = pod.metadata.name
        ssh.withCloseable {
            ssh.authPassword('root', 'root1234')
            def tempDir = '/tmp/kubeDebug/'
            def temp = PathUtil.genDestPath(local, tempDir)

            //create temp folder and remote folder in pod
            ssh.startSession().withCloseable {
                it.exec("mkdir -p $tempDir").join()
            }

            containerName = KubeClient.getContainerName(containerName, pod)
            KubeClient.get().exec(podName, containerName, 5, "mkdir -p ${PathUtil.getParent(remote)}")

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
}
