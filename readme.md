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
            url http://la-nexus.tandbergtv.lan:8081/repository/cms
        }
        mavenCentral()
    }

    dependencies {
        classpath 'com.mk.kube:KubeDebug:0.6.1'
    }
}

apply plugin: 'com.mk.kube.debug'
```

### config plugin

```groovy
// config the kubeDebug task in your build.gradle, here is a example
kubeDebug {
    dependsOn build

    k8s {
        host '10.116.53.141'
    }

    uploads {
        cms {
            from file('build/libs/cms.ear')
            to 'cms-config:/opt/tandbergtv/cms/plugins/jboss-deployments/'
        }
    }

    zipPatch {
        src 'cms-metadata-manager:/opt/tandbergtv/jboss/standalone/deployments/cms.ear'
        to 'cms-config:/opt/tandbergtv/cms/plugins/jboss-deployments/cms.ear'
        patch([
                (file('../com.tandbergtv.watchpoint.title/build/libs/com.tandbergtv.watchpoint.title-10.2.000.0.jar')): 'lib',
        ])
    }

    deployment {
        name 'cms-metadata-manager'
        debug false
        restore false
    }
}
```

#### supported config

| parameter  | description                             |
|------------|-----------------------------------------|
| k8s        | [configure the k8s cluster](#k8s)       |
| uploads    | [configure the upload files](#uploads)  |
| zipPath    | [configure the zip patch](#zipPatch)    |
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
        from //local file apth
        to //upload destination path: <appName:path in container>
        beforeUpload //commands before upload, type array
        afterUpload //commands after upload, type array
    }
    //...
}
```

##### <a name="zipPatch">zipPatch</a>

```groovy
zipPatch {
    src //zip file path: <appName:path in container>
    to
    //zip file path: <appName:path in container>, if the file not exit in this path, then download it from the src path
    //patch file, type Map
    patch([
            ('local file path'): 'directory in zip',
    ])
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
    debug = false //if true, config the deployment for remote debug
}
```