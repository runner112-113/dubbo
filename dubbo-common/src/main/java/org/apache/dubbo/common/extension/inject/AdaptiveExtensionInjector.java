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
package org.apache.dubbo.common.extension.inject;

import org.apache.dubbo.common.context.Lifecycle;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.ExtensionAccessor;
import org.apache.dubbo.common.extension.ExtensionInjector;
import org.apache.dubbo.common.extension.ExtensionLoader;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * AdaptiveExtensionInjector
 */
@Adaptive
public class AdaptiveExtensionInjector implements ExtensionInjector, Lifecycle {

    private Collection<ExtensionInjector> injectors = Collections.emptyList();
    private ExtensionAccessor extensionAccessor;

    public AdaptiveExtensionInjector() {}

    @Override
    public void setExtensionAccessor(final ExtensionAccessor extensionAccessor) {
        this.extensionAccessor = extensionAccessor;
    }

    @Override
    public void initialize() throws IllegalStateException {
        // 获取【扩展点注入器】的加载器
        ExtensionLoader<ExtensionInjector> loader = extensionAccessor.getExtensionLoader(ExtensionInjector.class);
        // 从加载器中拿出所有的可被使用的注册器实现类
        injectors = loader.getSupportedExtensions().stream()
                .map(loader::getExtension)
                // 然后通过不可变集合包装起来，意味着不允许别人对注册器实现类进行任何修改
                .collect(Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList));
    }

    @Override
    public <T> T getInstance(final Class<T> type, final String name) {
        return injectors.stream()
                // 循环每种容器，从容器中根据类型加名字获取实例对象
                // 一旦获取到了的话，那就直接返回即可，不用再循环其他容器了
                .map(injector -> injector.getInstance(type, name))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    @Override
    public void start() throws IllegalStateException {}

    @Override
    public void destroy() throws IllegalStateException {}
}
