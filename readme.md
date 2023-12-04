# Kube debug plugin

## Description

Kube debug plugin feature

* upload file to a pod
* restart deployment
* edit a deployment to debug java application
* path ear/war/zip file in the pod
* gradle version must >= 4.10.3

## Usage

### import plugin

```groovy
buildscript {
    repositories {
        maven {
            url 'custom maven repo'
        }
        mavenCentral()
    }

    dependencies {
        classpath 'com.toby.kube:KubeDebug:0.6.1'
    }
}

apply plugin: 'com.toby.kube.debug'
```

### config plugin

```groovy
// config the kubeDebug task in your build.gradle, here is a example
kubeDebug {
    dependsOn build

    k8s {
        host '127.0.0.1'
    }

    uploads {
        foo {
            from file('build/libs/foo.ear')
            to 'foo-config:/path/in/pod/'
        }
    }

    zipPatch {
        src 'app-pod:/path/foo.ear'
        to 'foo-config:/path/foo.ear'
        patch([
                (file('../foo/build/libs/tet.jar')): 'lib',
        ])
    }

    deployment {
        name 'deploy-name'
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
    sshPort //k8s master node ssh port, default 22
    sshUser //k8s master node ssh user name
    sshPasswd //k8s master node ssh password
    namespace //k8s namespace, default namespace is default
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