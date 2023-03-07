# Kube debug plugin

## Description

Kube debug plugin feature

* upload file to a pod
* restart deployment
* edit a deployment to debug java application

## Usage

### import plugin

```groovy
buildscript {
    repositories {
        maven {
            url cmsNexus
        }
    }

    dependencies {
        classpath 'com.mk.kube:KubeDebug:0.6.0'
    }
}

apply plugin: 'com.mk.kube.debug'
```

### config plugin

```groovy
// config the kubeDebug task in your build.gradle
kubeDebug {
    dependsOn build

    k8s {
        host '10.116.53.141'
    }

    uploads {
        cms {
            localPath file('build/libs/cms.ear').canonicalPath
            podName 'cms-config-0'
            remotePath '/opt/tandbergtv/cms/plugins/jboss-deployments/'
        }
    }

    deployment {
        name 'cms-metadata-manager'
        debug true
        restore false
    }
}
```

#### supported config

| parameter  | description                             |
|------------|-----------------------------------------|
| k8s        | [configure the k8s cluster](#k8s)       |
| uploads    | [configure the upload files](#uploads)  |
| deployment | [configure the deployment](#deployment) |

##### <a name="k8s">k8s</a>

```groovy
k8s {
    host //k8s master node address
    port = 6443 //k8s api server port, default 6443
    user = 'mdt-admin' //k8s api server user, default mdt-admin
    passwd = 'youmustchangeme' //k8s api server passwd, default youmustchangeme
    namespace = 'mkcms' //k8s namespace, default mkcms
}
```

##### <a name="uploads">uploads</a>

```groovy
uploads {
    name { // upload conf name, use whatever you want
        deployName //destination deployment name
        podName //destination pod name, should specify one of the podName and deployName
        containerName //if not specify, then default use the first container of pod
        localPath //upload local source file path
        remotePath //upload destination path in pod container
        beforeUpload //commands before upload, type array
        afterUpload //commands after upload, type array
    }
    //...
}
```

##### <a name="deployment">deployment</a>

```groovy
deployment {
    name //deployment name
    readOnly = true //deployment read only file system, default true
    runAsRoot = false //deployment run as root, default false
    replicas = 0 //default 0, means use original replica, force 1 when debug is true
    healthCheck = true //deployment readiness and liveliness check, force false when debug is true
    commands //replace the container commands with this; default null
    restore = false //if ture, then restore the deployment to the time before applying this plugin
    debug = false
    //if true, config the deployment for remote debug, otherwise just restart the deployment
}
```