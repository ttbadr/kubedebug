package com.toby.kube.debug.utils

import cn.hutool.core.io.file.FileNameUtil
import cn.hutool.core.util.StrUtil

class PathUtil {

    /**
     * generate destination path
     * <p>eg:
     * <br>  (/o/p/t.img, /o/p)     -> /o/p/t.img
     * <br>  (/o/p/t.img, /o/p/)    -> /o/p/t.img
     * <br>  (/o/p/t.img, /o/p.img) -> /o/p.img
     * <br>  (/o/p/t, /o/p)         -> /o/p
     * <br>  (/o/p/t, /o/p/)        -> /o/p/t
     * <br>  (/o/p/t/, /o/p/)       -> /o/p/t
     * @param src src path
     * @param dest dest path
     * @return new dest path
     */
    static String genDestPath(String src, String dest) {
        String separator = "/"
        boolean dir = FileNameUtil.extName(src).isEmpty()
        if (dir) {
            // if src is a dir and dest is a dir with '/'
            if (dest.endsWith(separator)) {
                String dirName = FileNameUtil.getName(StrUtil.removeSuffix(src, separator))
                dest += dirName
            }
        } else {
            // if src is a file and dest is a dir
            if (FileNameUtil.extName(dest).isEmpty()) {
                dest = StrUtil.removeSuffix(dest, separator) + separator + FileNameUtil.getName(src)
            }
        }
        return dest
    }

    static String getParent(String path) {
        return StrUtil.subBefore(path, '/', true)
    }
}
