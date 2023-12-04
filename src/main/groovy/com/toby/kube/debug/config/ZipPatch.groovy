package com.toby.kube.debug.config

import cn.hutool.core.io.file.FileNameUtil
import cn.hutool.core.util.StrUtil
import org.gradle.api.GradleException

class ZipPatch {
    String src
    String to
    Map<File, String> patch

    String getSrcApp() {
        return StrUtil.subBefore(src, ':', false)
    }

    String getToApp() {
        return StrUtil.subBefore(to, ':', false)
    }

    String getSrcPath() {
        return StrUtil.subAfter(src, ':', false)
    }

    String getToPath() {
        return StrUtil.subAfter(to, ':', false)
    }

    def validate() {
        if (StrUtil.isNotBlank(src) && src.count(':') != 1) {
            throw new GradleException("invalid src format, should be 'appName:filePath'")
        }
        if (StrUtil.isNotBlank(to) && to.count(':') != 1) {
            throw new GradleException("invalid to format, should be 'appName:filePath'")
        }
        if (patch.isEmpty()) {
            throw new GradleException("patch not define for the ZipPatch config")
        } else {
            for (final def e in patch.entrySet()) {
                if (!e.key.exists() || !e.key.isFile()) {
                    throw new GradleException("${e.key.canonicalPath} is not a file")
                }
                if (!FileNameUtil.extName(e.value).isEmpty()) {
                    throw new GradleException("$e.value is not a valid folder path")
                }
            }
        }
    }
}
