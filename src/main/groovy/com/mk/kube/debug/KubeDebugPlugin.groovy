package com.mk.kube.debug


import org.gradle.api.Plugin
import org.gradle.api.Project

class KubeDebugPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.tasks.register('kubeDebug', KubeDebugTask, project)
    }
}
