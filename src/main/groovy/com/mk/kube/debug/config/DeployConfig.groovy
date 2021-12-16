package com.mk.kube.debug.config

class DeployConfig {
    String deployName
    boolean readOnly = true
    boolean runAsRoot = false
    int replicas = 1
    boolean healthCheck = false
    List<String> commands
}
