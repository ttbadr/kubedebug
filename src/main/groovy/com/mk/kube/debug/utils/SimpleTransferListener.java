package com.mk.kube.debug.utils;

import cn.hutool.core.util.NumberUtil;
import net.schmizz.sshj.common.StreamCopier;
import net.schmizz.sshj.xfer.TransferListener;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

/**
 * simple file transfer listener to show file transfer progress
 *
 * @author toby.tan
 */
public class SimpleTransferListener implements TransferListener {

    private static final Logger log = Logging.getLogger(SimpleTransferListener.class);

    @Override
    public TransferListener directory(String name) {
        log.lifecycle("started transferring directory `{}`", name);
        return this;
    }

    @Override
    public StreamCopier.Listener file(final String name, final long size) {
        log.lifecycle("started transferring file `{}` ({} kB)", name,
            NumberUtil.div(size, 1024, 1));
        return new CopyListener(name, size);
    }

    private static class CopyListener implements StreamCopier.Listener {

        private final String name;
        private final long size;
        private long prePercent = 0;

        public CopyListener(String name, long size) {
            this.name = name;
            this.size = size;
        }

        @Override
        public void reportProgress(long transferred) {
            if (size <= 0) {
                return;
            }
            long percent = (transferred * 100) / size;
            //print progress if changed more than 1 percent
            if (percent - prePercent >= 1) {
                log.lifecycle("transferred {}% of `{}`", percent, name);
            }
            prePercent = percent;
        }
    }
}