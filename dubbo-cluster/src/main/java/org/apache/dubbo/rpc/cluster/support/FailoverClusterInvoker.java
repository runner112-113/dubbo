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
package org.apache.dubbo.rpc.cluster.support;

import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_RETRIES;
import static org.apache.dubbo.common.constants.CommonConstants.RETRIES_KEY;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.CLUSTER_FAILED_MULTIPLE_RETRIES;

/**
 * When invoke fails, log the initial error and retry other invokers (retry n times, which means at most n different invokers will be invoked)
 * Note that retry causes latency.
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Failover">Failover</a>
 *
 * FailoverClusterInvoker 是 FailoverCluster 的具体实现，它会在调用失败时进行重试。
 */
public class FailoverClusterInvoker<T> extends AbstractClusterInvoker<T> {

    private static final ErrorTypeAwareLogger logger =
            LoggerFactory.getErrorTypeAwareLogger(FailoverClusterInvoker.class);

    public FailoverClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Result doInvoke(Invocation invocation, final List<Invoker<T>> invokers, LoadBalance loadbalance)
            throws RpcException {
        List<Invoker<T>> copyInvokers = invokers;
        // 获取此次调用的方法名
        String methodName = RpcUtils.getMethodName(invocation);
        // 通过方法名计算获取重试次数
        int len = calculateInvokeTimes(methodName);
        // retry loop.
        // 循环计算得到的 len 次数
        RpcException le = null; // last exception.
        // 记录已经调用过的Invoker，重试时规避
        List<Invoker<T>> invoked = new ArrayList<>(copyInvokers.size()); // invoked invokers.
        // 记录已经调用过的Provider，用于日志
        Set<String> providers = new HashSet<>(len);
        for (int i = 0; i < len; i++) {
            // Reselect before retry to avoid a change of candidate `invokers`.
            // NOTE: if `invokers` changed, then `invoked` also lose accuracy.
            // 从第2次循环开始，会有一段特殊的逻辑处理
            if (i > 0) {
                // 检测 invoker 是否被销毁了
                checkWhetherDestroyed();
                // 重新拿到调用接口的所有提供者列表集合，
                // 粗俗理解，就是提供该接口服务的每个提供方节点就是一个 invoker 对象
                copyInvokers = list(invocation);
                // check again
                // 再次检查所有拿到的 invokes 的一些可用状态
                checkInvokers(copyInvokers, invocation);
            }
            // 选择其中一个，即采用了负载均衡策略从众多 invokers 集合中挑选出一个合适可用的
            Invoker<T> invoker = select(loadbalance, invocation, copyInvokers, invoked);
            invoked.add(invoker);
            // 设置 RpcContext 上下文
            RpcContext.getServiceContext().setInvokers((List) invoked);
            boolean success = false;
            try {
                // 服务调用
                Result result = invokeWithContext(invoker, invocation);
                if (le != null && logger.isWarnEnabled()) {
                    logger.warn(
                            CLUSTER_FAILED_MULTIPLE_RETRIES,
                            "failed to retry do invoke",
                            "",
                            "Although retry the method " + methodName
                                    + " in the service " + getInterface().getName()
                                    + " was successful by the provider "
                                    + invoker.getUrl().getAddress()
                                    + ", but there have been failed providers " + providers
                                    + " (" + providers.size() + "/" + copyInvokers.size()
                                    + ") from the registry "
                                    + directory.getUrl().getAddress()
                                    + " on the consumer " + NetUtils.getLocalHost()
                                    + " using the dubbo version " + Version.getVersion() + ". Last error is: "
                                    + le.getMessage(),
                            le);
                }
                // 如果没有抛出异常的话，则认为正常拿到的返回数据
                // 那么设置调用成功标识，然后直接返回 result 结果
                success = true;
                return result;
            } catch (RpcException e) {
                // 如果是 Dubbo 框架层面认为的业务异常，那么就直接抛出异常
                if (e.isBiz()) { // biz exception.
                    throw e;
                }
                // 其他异常的话，则不继续抛出异常，那么就意味着还可以有机会再次循环调用
                le = e;
            } catch (Throwable e) {
                le = new RpcException(e.getMessage(), e);
            } finally {
                // 如果没有正常返回拿到结果的话，那么把调用异常的提供方地址信息记录起来
                if (!success) {
                    providers.add(invoker.getUrl().getAddress());
                }
            }
        }
        // 如果 len 次循环仍然还没有正常拿到调用结果的话，
        // 那么也不再继续尝试调用了，直接索性把一些需要开发人员关注的一些信息写到异常描述信息中，通过异常方式拋出去
        throw new RpcException(
                le.getCode(),
                "Failed to invoke the method "
                        + methodName + " in the service " + getInterface().getName()
                        + ". Tried " + len + " times of the providers " + providers
                        + " (" + providers.size() + "/" + copyInvokers.size()
                        + ") from the registry " + directory.getUrl().getAddress()
                        + " on the consumer " + NetUtils.getLocalHost() + " using the dubbo version "
                        + Version.getVersion() + ". Last error is: "
                        + le.getMessage(),
                le.getCause() != null ? le.getCause() : le);
    }

    private int calculateInvokeTimes(String methodName) {
        int len = getUrl().getMethodParameter(methodName, RETRIES_KEY, DEFAULT_RETRIES) + 1;
        RpcContext rpcContext = RpcContext.getClientAttachment();
        Object retry = rpcContext.getObjectAttachment(RETRIES_KEY);
        if (retry instanceof Number) {
            len = ((Number) retry).intValue() + 1;
            rpcContext.removeAttachment(RETRIES_KEY);
        }
        if (len <= 0) {
            len = 1;
        }

        return len;
    }
}
