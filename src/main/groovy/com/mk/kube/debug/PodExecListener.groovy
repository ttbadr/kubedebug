package com.mk.kube.debug

import io.fabric8.kubernetes.client.dsl.ExecListener
import okhttp3.Response
import org.slf4j.Logger

import java.util.concurrent.CountDownLatch

class PodExecListener implements ExecListener {
    private final CountDownLatch execLatch
    private final Logger logger

    PodExecListener(CountDownLatch execLatch, Logger logger) {
        this.execLatch = execLatch
        this.logger = logger
    }

    @Override
    void onOpen(Response response) {
        logger.debug("Shell was opened")
    }

    @Override
    void onFailure(Throwable throwable, Response response) {
        logger.error("Exec cmd in pod error", throwable)
        execLatch.countDown()
    }

    @Override
    void onClose(int i, String s) {
        logger.debug("Shell Closing, code {} , reason: {}", i, s)
        execLatch.countDown()
    }
}
