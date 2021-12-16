package com.mk.kube.debug.config

import cn.hutool.core.io.FileUtil
import cn.hutool.core.util.StrUtil
import org.gradle.api.GradleException

class UploadConfig {
    final String name
    String deployName
    String podName
    String containerName
    String localPath
    String remotePath

    UploadConfig(String name) {
        this.name = name
    }

    void podName(String podName) {
        this.podName = podName
    }

    void deployName(String deployName) {
        this.deployName = deployName
    }

    void containerName(String containerName) {
        this.containerName = containerName
    }

    void localPath(String localPath) {
        this.localPath = localPath
    }

    void remotePath(String remotePath) {
        this.remotePath = remotePath
    }

    def validate() {
        if (StrUtil.isAllBlank(deployName, podName)) {
            throw new GradleException("deployName or podName not define for the upload file $name")
        }
        if (StrUtil.hasBlank(localPath, remotePath)) {
            throw new GradleException("localPath or remotePath not define for the upload file $name")
        }
        if (!FileUtil.exist(localPath)) {
            throw new GradleException("$localPath not exist")
        }
    }
}
