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
        classpath 'com.mk.kube:KubeDebug:0.4.7'
    }
}

apply plugin: 'com.mk.kube.debug'
```

### config plugin

```groovy
// config the kubeDebug task in your build.gradle
kubeDebug {
    dependsOn build
    restore false
    debug false

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
    }
}
```

#### supported config

| parameter       | default | description                                                                                                               |
| ----------------- | --------- | --------------------------------------------------------------------------------------------------------------------------- |
| restore         | false   | if ture then restore the deployment if the deployment exist in the local                                                  |
| debug           | true    | edit the deployment, also create debug node port to debug java deployment.<br />if false then just restart the deployment |
| uploadFilesOnly | false   | just upload configured files, doesn't debug or restart deployment                                                         |
| k8s             | null    | [configure the k8s cluster](#k8s)                                                                                         |
| uploads         | null    | [configure the upload files](#uploads)                                                                                                |
| deployment      | null    | [configure the deployment that need to restart or debug](#deployment)                                                                    |

##### <a name="k8s">k8s</a>

```groovy
k8s {
    host //mdt1 ip
    port = 6443 //k8s api server port, default 6443
    user = 'mdt-admin' //k8s api server user, default mdt-admin
    passwd = 'youmustchangeme' //k8s api server passwd, default youmustchangeme
    namespace = 'mkcms' //k8s namespace, default mkcms
}
```

##### <a name="uploads">uploads</a>

```groovy
uploads {
    name { // upload conf name, use whatever you want, could specify multiple times
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
    name //deployment name for debug
    readOnly = true //deployment read only file system, default true
    runAsRoot = false //deployment run as root, default false
    replicas = 1 //deployment replica for debug, default 1
    healthCheck = false //deployment readiness and liveliness check for debug, default false
    commands
    //replace the container commands with this; type array, default null, means don't change the container's command
}
```