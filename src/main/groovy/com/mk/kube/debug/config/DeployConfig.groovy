package com.mk.kube.debug.config

class DeployConfig {
    String name
    boolean readOnly = true
    boolean runAsRoot = false
    int replicas = 1
    boolean healthCheck = false
    List<String> commands
}
