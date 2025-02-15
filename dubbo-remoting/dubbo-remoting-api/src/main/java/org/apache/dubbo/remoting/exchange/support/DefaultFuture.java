/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.remoting.exchange.support;

import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.resource.GlobalResourceInitializer;
import org.apache.dubbo.common.serialize.SerializationException;
import org.apache.dubbo.common.threadpool.ThreadlessExecutor;
import org.apache.dubbo.common.timer.HashedWheelTimer;
import org.apache.dubbo.common.timer.Timeout;
import org.apache.dubbo.common.timer.Timer;
import org.apache.dubbo.common.timer.TimerTask;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.TimeoutException;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.PROTOCOL_TIMEOUT_SERVER;

/**
 * DefaultFuture.
 */
public class DefaultFuture extends CompletableFuture<Object> {

    private static final ErrorTypeAwareLogger logger = LoggerFactory.getErrorTypeAwareLogger(DefaultFuture.class);

    /**
     * in-flight channels
     */
    private static final Map<Long, Channel> CHANNELS = new ConcurrentHashMap<>();

    /**
     * in-flight requests
     * 所有fly的请求
     */
    private static final Map<Long, DefaultFuture> FUTURES = new ConcurrentHashMap<>();

    private static final GlobalResourceInitializer<Timer> TIME_OUT_TIMER = new GlobalResourceInitializer<>(
            () -> new HashedWheelTimer(new NamedThreadFactory("dubbo-future-timeout", true), 30, TimeUnit.MILLISECONDS),
            DefaultFuture::destroy);

    // invoke id.
    private final Long id;

    private final Channel channel;

    private final Request request;

    private final int timeout;

    private final long start = System.currentTimeMillis();

    private volatile long sent;

    private Timeout timeoutCheckTask;

    private ExecutorService executor;

    public ExecutorService getExecutor() {
        return executor;
    }

    public void setExecutor(ExecutorService executor) {
        this.executor = executor;
    }

    private DefaultFuture(Channel channel, Request request, int timeout) {
        this.channel = channel;
        this.request = request;
        this.id = request.getId();
        this.timeout = timeout > 0 ? timeout : channel.getUrl().getPositiveParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT);
        // put into waiting map.
        FUTURES.put(id, this);
        CHANNELS.put(id, channel);
    }

    /**
     * check time out of the future
     */
    private static void timeoutCheck(DefaultFuture future) {
        TimeoutCheckTask task = new TimeoutCheckTask(future.getId());
        future.timeoutCheckTask = TIME_OUT_TIMER.get().newTimeout(task, future.getTimeout(), TimeUnit.MILLISECONDS);
    }

    public static void destroy() {
        TIME_OUT_TIMER.remove(Timer::stop);
        FUTURES.clear();
        CHANNELS.clear();
    }

    /**
     * init a DefaultFuture
     * 1.init a DefaultFuture
     * 2.timeout check
     *
     * @param channel channel
     * @param request the request
     * @param timeout timeout
     * @return a new DefaultFuture
     */
    public static DefaultFuture newFuture(Channel channel, Request request, int timeout, ExecutorService executor) {
        final DefaultFuture future = new DefaultFuture(channel, request, timeout);
        future.setExecutor(executor);
        // timeout check 超时的会返回超时并移除DefaultFuture
        timeoutCheck(future);
        return future;
    }

    public static DefaultFuture getFuture(long id) {
        return FUTURES.get(id);
    }

    public static boolean hasFuture(Channel channel) {
        return CHANNELS.containsValue(channel);
    }

    public static void sent(Channel channel, Request request) {
        DefaultFuture future = FUTURES.get(request.getId());
        if (future != null) {
            future.doSent();
        }
    }

    /**
     * close a channel when a channel is inactive
     * directly return the unfinished requests.
     *
     * @param channel channel to close
     */
    public static void closeChannel(Channel channel, long timeout) {
        long deadline = timeout > 0 ? System.currentTimeMillis() + timeout : 0;
        for (Map.Entry<Long, Channel> entry : CHANNELS.entrySet()) {
            if (channel.equals(entry.getValue())) {
                DefaultFuture future = getFuture(entry.getKey());
                if (future != null && !future.isDone()) {
                    long restTime = deadline - System.currentTimeMillis();
                    if (restTime > 0) {
                        try {
                            future.get(restTime, TimeUnit.MILLISECONDS);
                        } catch (java.util.concurrent.TimeoutException ignore) {
                            logger.warn(
                                    PROTOCOL_TIMEOUT_SERVER,
                                    "",
                                    "",
                                    "Trying to close channel " + channel + ", but response is not received in "
                                            + timeout + "ms, and the request id is " + future.id);
                        } catch (Throwable ignore) {
                        }
                    }
                    if (!future.isDone()) {
                        respInactive(channel, future);
                    }
                }
            }
        }
    }

    private static void respInactive(Channel channel, DefaultFuture future) {
        Response disconnectResponse = new Response(future.getId());
        disconnectResponse.setStatus(Response.CHANNEL_INACTIVE);
        disconnectResponse.setErrorMessage("Channel " + channel
                + " is inactive. Directly return the unFinished request : "
                + (logger.isDebugEnabled()
                        ? future.getRequest()
                        : future.getRequest().copyWithoutData()));
        DefaultFuture.received(channel, disconnectResponse);
    }

    public static void received(Channel channel, Response response) {
        received(channel, response, false);
    }

    public static void received(Channel channel, Response response, boolean timeout) {
        try {
            DefaultFuture future = FUTURES.remove(response.getId());
            if (future != null) {
                Timeout t = future.timeoutCheckTask;
                if (!timeout) {
                    // decrease Time
                    t.cancel();
                }
                future.doReceived(response);
                shutdownExecutorIfNeeded(future);
            } else {
                logger.warn(
                        PROTOCOL_TIMEOUT_SERVER,
                        "",
                        "",
                        "The timeout response finally returned at "
                                + (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()))
                                + ", response status is " + response.getStatus()
                                + (channel == null
                                        ? ""
                                        : ", channel: " + channel.getLocalAddress() + " -> "
                                                + channel.getRemoteAddress())
                                + ", please check provider side for detailed result.");
            }
        } finally {
            CHANNELS.remove(response.getId());
        }
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        Response errorResult = new Response(id);
        errorResult.setStatus(Response.CLIENT_ERROR);
        errorResult.setErrorMessage("request future has been canceled.");
        this.doReceived(errorResult);
        DefaultFuture future = FUTURES.remove(id);
        shutdownExecutorIfNeeded(future);
        CHANNELS.remove(id);
        timeoutCheckTask.cancel();
        return true;
    }

    private static void shutdownExecutorIfNeeded(DefaultFuture future) {
        ExecutorService executor = future.getExecutor();
        if (executor instanceof ThreadlessExecutor && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    public void cancel() {
        this.cancel(true);
    }

    private void doReceived(Response res) {
        if (res == null) {
            throw new IllegalStateException("response cannot be null");
        }
        if (res.getStatus() == Response.OK) {
            this.complete(res.getResult());
        } else if (res.getStatus() == Response.CLIENT_TIMEOUT || res.getStatus() == Response.SERVER_TIMEOUT) {
            this.completeExceptionally(
                    new TimeoutException(res.getStatus() == Response.SERVER_TIMEOUT, channel, res.getErrorMessage()));
        } else if (res.getStatus() == Response.SERIALIZATION_ERROR) {
            this.completeExceptionally(new SerializationException(res.getErrorMessage()));
        } else {
            this.completeExceptionally(new RemotingException(channel, res.getErrorMessage()));
        }
    }

    private long getId() {
        return id;
    }

    private Channel getChannel() {
        return channel;
    }

    private boolean isSent() {
        return sent > 0;
    }

    public Request getRequest() {
        return request;
    }

    private int getTimeout() {
        return timeout;
    }

    private void doSent() {
        sent = System.currentTimeMillis();
    }

    private String getTimeoutMessage(boolean scan) {
        long nowTimestamp = System.currentTimeMillis();
        return (sent > 0 && sent - start < timeout
                        ? "Waiting server-side response timeout"
                        : "Sending request timeout in client-side")
                + (scan ? " by scan timer" : "") + ". start time: "
                + (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date(start))) + ", end time: "
                + (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date(nowTimestamp))) + ","
                + (sent > 0
                        ? " client elapsed: " + (sent - start) + " ms, server elapsed: " + (nowTimestamp - sent)
                        : " elapsed: " + (nowTimestamp - start))
                + " ms, timeout: "
                + timeout + " ms, request: " + (logger.isDebugEnabled() ? request : request.copyWithoutData())
                + ", channel: " + channel.getLocalAddress()
                + " -> " + channel.getRemoteAddress();
    }

    private static class TimeoutCheckTask implements TimerTask {

        private final Long requestID;

        TimeoutCheckTask(Long requestID) {
            this.requestID = requestID;
        }

        @Override
        public void run(Timeout timeout) {
            DefaultFuture future = DefaultFuture.getFuture(requestID);
            if (future == null || future.isDone()) {
                return;
            }

            ExecutorService executor = future.getExecutor();
            if (executor != null && !executor.isShutdown()) {
                executor.execute(() -> notifyTimeout(future));
            } else {
                notifyTimeout(future);
            }
        }

        private void notifyTimeout(DefaultFuture future) {
            // create exception response.
            // 构建超时响应返回
            Response timeoutResponse = new Response(future.getId());
            // set timeout status.
            timeoutResponse.setStatus(future.isSent() ? Response.SERVER_TIMEOUT : Response.CLIENT_TIMEOUT);
            timeoutResponse.setErrorMessage(future.getTimeoutMessage(true));
            // handle response.
            DefaultFuture.received(future.getChannel(), timeoutResponse, true);
        }
    }
}
