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
package org.apache.dubbo.config;

import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.rpc.model.ModuleModel;

import java.beans.Transient;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

import static org.apache.dubbo.common.constants.CommonConstants.EXPORTER_LISTENER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.EXPORT_ASYNC_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SERVICE_EXECUTOR;
import static org.apache.dubbo.common.constants.CommonConstants.SERVICE_FILTER_KEY;

/**
 * AbstractServiceConfig
 *
 * @export
 */
public abstract class AbstractServiceConfig extends AbstractInterfaceConfig {

    private static final long serialVersionUID = -9026290350363878309L;

    /**
     * The service version
     */
    protected String version;

    /**
     * The service group
     */
    protected String group;

    /**
     * whether the service is deprecated
     */
    protected Boolean deprecated; // false;

    /**
     * The time delay register service (milliseconds)
     */
    protected Integer delay;

    /**
     * Whether to export the service
     */
    protected Boolean export;

    /**
     * The service weight
     */
    protected Integer weight;

    /**
     * Document center
     */
    protected String document;

    /**
     * Whether to register as a dynamic service or not on register center, the value is true, the status will be enabled
     * after the service registered,and it needs to be disabled manually; if you want to disable the service, you also need
     * manual processing
     */
    // 服务是否动态注册，如果设为false，注册后将显示为disable状态，需人工启用，并且服务提供者停止时，也不会自动取消注册，需人工禁用。
    protected Boolean dynamic; // true;

    /**
     * Whether to use token
     */
    protected String token;

    /**
     * Whether to export access logs to logs
     */
    protected String accesslog;

    /**
     * The protocol list the service will export with
     * Also see {@link #protocolIds}, only one of them will work.
     */
    // 服务准备暴露的协议：dubbo，rest等
    protected List<ProtocolConfig> protocols;

    /**
     * The id list of protocols the service will export with
     * Also see {@link #protocols}, only one of them will work.
     */
    protected String protocolIds;

    /**
     * Max allowed executing times
     */
    private Integer executes;

    /**
     * Whether to register
     */
    // 是否向此注册中心注册服务，如果设为false，将只订阅，不注册
    private Boolean register;

    /**
     * Warm up period
     */
    private Integer warmup;

    /**
     * The serialization type
     */
    private String serialization;

    /**
     * If the parameter has a value, the consumer will read the parameter first.
     * If the Dubbo Sdk you are using contains the serialization type, the serialization method specified by the argument is used.
     * <p>
     * When this parameter is null or the serialization type specified by this parameter does not exist in the Dubbo SDK, the serialization type specified by serialization is used.
     * If the Dubbo SDK if still does not exist, the default type of the Dubbo SDK is used.
     * For Dubbo SDK >= 3.2, <code>preferSerialization</code> takes precedence over <code>serialization</code>
     * <p>
     * The configuration supports multiple, which are separated by commas.Such as:<code>fastjson2,fastjson,hessian2</code>
     */
    private String preferSerialization; // default:fastjson2,hessian2

    /**
     * Weather the service is export asynchronously
     * @deprecated
     * @see ModuleConfig#exportAsync
     */
    @Deprecated
    private Boolean exportAsync;

    /**
     * used for thread pool isolation between services
     */
    private Executor executor;

    /**
     * Payload max length.
     */
    private Integer payload;

    /**
     * Whether to use java_package in IDL as path. Default use package.
     * This param only available when service using native stub.
     */
    private Boolean useJavaPackageAsPath;

    public AbstractServiceConfig() {}

    public AbstractServiceConfig(ModuleModel moduleModel) {
        super(moduleModel);
    }

    @Override
    protected void checkDefault() {
        super.checkDefault();
        if (deprecated == null) {
            deprecated = false;
        }
        if (dynamic == null) {
            dynamic = true;
        }
        if (useJavaPackageAsPath == null) {
            useJavaPackageAsPath = false;
        }
        if (StringUtils.isBlank(preferSerialization)) {
            preferSerialization = serialization;
        }
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public void setVersion(String version) {
        this.version = version;
    }

    @Override
    public String getGroup() {
        return group;
    }

    @Override
    public void setGroup(String group) {
        this.group = group;
    }

    public Integer getDelay() {
        return delay;
    }

    public void setDelay(Integer delay) {
        this.delay = delay;
    }

    public Boolean getExport() {
        return export;
    }

    public void setExport(Boolean export) {
        this.export = export;
    }

    public Integer getWeight() {
        return weight;
    }

    public void setWeight(Integer weight) {
        this.weight = weight;
    }

    @Parameter(escaped = true)
    public String getDocument() {
        return document;
    }

    public void setDocument(String document) {
        this.document = document;
    }

    public String getToken() {
        return token;
    }

    public void setToken(Boolean token) {
        if (token == null) {
            setToken((String) null);
        } else {
            setToken(String.valueOf(token));
        }
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Boolean isDeprecated() {
        return deprecated;
    }

    public void setDeprecated(Boolean deprecated) {
        this.deprecated = deprecated;
    }

    public Boolean isDynamic() {
        return dynamic;
    }

    public void setDynamic(Boolean dynamic) {
        this.dynamic = dynamic;
    }

    public List<ProtocolConfig> getProtocols() {
        return protocols;
    }

    @SuppressWarnings({"unchecked"})
    public void setProtocols(List<? extends ProtocolConfig> protocols) {
        this.protocols = (List<ProtocolConfig>) protocols;
    }

    public ProtocolConfig getProtocol() {
        return CollectionUtils.isEmpty(protocols) ? null : protocols.get(0);
    }

    public void setProtocol(ProtocolConfig protocol) {
        setProtocols(new ArrayList<>(Arrays.asList(protocol)));
    }

    @Parameter(excluded = true)
    public String getProtocolIds() {
        return protocolIds;
    }

    public void setProtocolIds(String protocolIds) {
        this.protocolIds = protocolIds;
    }

    public String getAccesslog() {
        return accesslog;
    }

    public void setAccesslog(Boolean accesslog) {
        if (accesslog == null) {
            setAccesslog((String) null);
        } else {
            setAccesslog(String.valueOf(accesslog));
        }
    }

    public void setAccesslog(String accesslog) {
        this.accesslog = accesslog;
    }

    public Integer getExecutes() {
        return executes;
    }

    public void setExecutes(Integer executes) {
        this.executes = executes;
    }

    @Override
    @Parameter(key = SERVICE_FILTER_KEY, append = true)
    public String getFilter() {
        return super.getFilter();
    }

    @Override
    @Parameter(key = EXPORTER_LISTENER_KEY, append = true)
    public String getListener() {
        return listener;
    }

    @Override
    public void setListener(String listener) {
        this.listener = listener;
    }

    public Boolean isRegister() {
        return register;
    }

    public void setRegister(Boolean register) {
        this.register = register;
    }

    public Integer getWarmup() {
        return warmup;
    }

    public void setWarmup(Integer warmup) {
        this.warmup = warmup;
    }

    public String getSerialization() {
        return serialization;
    }

    public void setSerialization(String serialization) {
        this.serialization = serialization;
    }

    public String getPreferSerialization() {
        return preferSerialization;
    }

    public void setPreferSerialization(String preferSerialization) {
        this.preferSerialization = preferSerialization;
    }

    @Deprecated
    @Parameter(key = EXPORT_ASYNC_KEY)
    public Boolean getExportAsync() {
        return exportAsync;
    }

    @Deprecated
    public void setExportAsync(Boolean exportAsync) {
        this.exportAsync = exportAsync;
    }

    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    @Parameter(key = SERVICE_EXECUTOR)
    @Transient
    public Executor getExecutor() {
        return executor;
    }

    public Integer getPayload() {
        return payload;
    }

    public void setPayload(Integer payload) {
        this.payload = payload;
    }

    @Parameter(excluded = true, attribute = false)
    public Boolean getUseJavaPackageAsPath() {
        return useJavaPackageAsPath;
    }

    public void setUseJavaPackageAsPath(Boolean useJavaPackageAsPath) {
        this.useJavaPackageAsPath = useJavaPackageAsPath;
    }
}
