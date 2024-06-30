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
package org.apache.dubbo.rpc.model;

import java.lang.reflect.Method;
import java.lang.reflect.Type;

public interface MethodDescriptor {

    String getMethodName();

    String getParamDesc();

    /**
     * duplicate filed as paramDesc, but with different format.
     */
    String[] getCompatibleParamSignatures();

    Class<?>[] getParameterClasses();

    Class<?> getReturnClass();

    Type[] getReturnTypes();

    RpcType getRpcType();

    boolean isGeneric();

    /**
     * Only available for ReflectionMethod
     *
     * @return method
     */
    Method getMethod();

    void addAttribute(String key, Object value);

    Object getAttribute(String key);

    enum RpcType {
        /**
         * 单一请求-响应模式。这是最常见的 RPC 模式，客户端发送一个请求，服务器处理该请求并返回一个响应
         */
        UNARY,
        /**
         * CLIENT_STREAM: 客户端流模式。在这种模式下，客户端可以发送多个请求流到服务器，但服务器只返回一个响应。
         * 这种模式适用于客户端需要连续发送数据的场景，例如上传文件或实时数据流。
         */
        CLIENT_STREAM,
        /**
         * 服务器流模式。在这种模式下，客户端发送一个请求，服务器通过流的方式连续返回多个响应。
         * 这种模式适用于服务器需要连续发送数据给客户端的场景，例如订阅服务或实时通知。
         */
        SERVER_STREAM,
        /**
         * 双向流模式。在这种模式下，客户端和服务器都可以发送和接收多个消息流。
         * 这是一种全双工通信模式，适用于需要实时双向通信的场景，例如聊天应用或实时协作工具。
         */
        BI_STREAM
    }
}
