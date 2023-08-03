package com.mk.kube.debug.utils;

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
        log.lifecycle("started transferring file `{}` ({})", name,
            ProgressLoggerWrapper.toLengthText(size));
        return new CopyListener(name, size);
    }

    private static class CopyListener implements StreamCopier.Listener {

        private final String name;
        private final long size;
        private long lastPrintTime = 0;
        private long lastPercent = 0;
        private boolean complete = false;

        public CopyListener(String name, long size) {
            this.name = name;
            this.size = size;
        }

        @Override
        public void reportProgress(long transferred) {
            if (size <= 0 || complete) {
                return;
            }
            long percent = (transferred * 100) / size;
            if (percent == 100) {
                complete = true;
            }
            long now = System.currentTimeMillis();
            if (complete || (now - lastPrintTime > 6000 && percent - lastPercent >= 1)) {
                lastPrintTime = now;
                log.lifecycle("`{}` transferred {}%", name, percent);
            }
            lastPercent = percent;
        }
    }
}