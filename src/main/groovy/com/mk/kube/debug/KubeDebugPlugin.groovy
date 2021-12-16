package com.mk.kube.debug

import com.mk.kube.debug.config.KubeDebugExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class KubeDebugPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        def config = project.extensions.create('kubeDebug', KubeDebugExtension, project)
        def debugTask = project.tasks.create('kubeDebug', KubeDebugTask)
        project.afterEvaluate {
            configPlugin(config, debugTask)
        }
    }

    static def configPlugin(KubeDebugExtension config, KubeDebugTask task) {
        task.restore = config.restore
        task.uploadFilesOnly = config.uploadFilesOnly

        task.uploads = config.uploads

        task.host = config.k8s.host
        task.port = config.k8s.port
        task.user = config.k8s.user
        task.passwd = config.k8s.passwd
        task.namespace = config.k8s.namespace

        task.deployName = config.deployment.deployName
        task.readOnly = config.deployment.readOnly
        task.runAsRoot = config.deployment.runAsRoot
        task.replicas = config.deployment.replicas
        task.healthCheck = config.deployment.healthCheck
        task.commands = config.deployment.commands
    }
}
