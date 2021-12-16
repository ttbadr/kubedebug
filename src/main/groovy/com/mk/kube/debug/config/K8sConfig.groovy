package com.mk.kube.debug.config

class K8sConfig {
    String host
    int port = 6443
    String user = 'mdt-admin'
    String passwd = 'youmustchangeme'
    String namespace = 'mkcms'
}
