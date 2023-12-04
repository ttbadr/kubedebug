package com.toby.kube.debug.config

class DeployConfig {
    String name
    boolean readOnly = true
    boolean runAsRoot = false
    int replicas = 0
    boolean healthCheck = true
    boolean debug = false
    boolean restore = false
    List<String> commands
}
