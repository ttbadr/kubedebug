package com.toby.kube.debug.config

import cn.hutool.core.io.FileUtil
import cn.hutool.core.util.StrUtil
import org.gradle.api.GradleException

class UploadConfig {
    String name
    String from
    String to
    String beforeUpload
    String afterUpload
    boolean transitMode = false

    UploadConfig(String name) {
        this.name = name
    }

    String getToApp() {
        return StrUtil.subBefore(to, ':', false)
    }

    String getToPath() {
        return StrUtil.subAfter(to, ':', false)
    }

    void from(String local) {
        this.from = local
    }

    void from(File local) {
        this.from = local.canonicalPath
    }

    void to(String remote) {
        this.to = remote
    }

    void beforeUpload(String beforeUpload) {
        this.beforeUpload = beforeUpload
    }

    void afterUpload(String afterUpload) {
        this.afterUpload = afterUpload
    }

    void transitMode(boolean mode) {
        this.transitMode = mode
    }

    def validate() {
        if (StrUtil.isBlank(to) || to.count(':') != 1) {
            throw new GradleException("should define to path with format in the $name : appName:path")
        }
        if (StrUtil.hasBlank(from) || !FileUtil.exist(from)) {
            throw new GradleException("localpath not defined or invalid in the $name")
        }
    }
}
