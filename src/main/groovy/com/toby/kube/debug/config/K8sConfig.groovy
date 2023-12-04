package com.toby.kube.debug.config

import cn.hutool.core.util.StrUtil
import org.gradle.api.GradleException

class K8sConfig {
    String host
    String namespace = 'default'
    String sshUser
    String sshPasswd
    int sshPort = 22

    def validate() {
        if(StrUtil.hasBlank(host, sshUser, sshPasswd)) {
            throw new GradleException("host, ssh sshUser and sshPasswd must be defined")
        }
    }
}
