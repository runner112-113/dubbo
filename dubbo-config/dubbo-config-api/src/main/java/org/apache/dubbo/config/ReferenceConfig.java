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

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.constants.LoggerCodeConstants;
import org.apache.dubbo.common.constants.RegistryConstants;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.url.component.ServiceConfigURL;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.config.annotation.Reference;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.config.utils.ConfigValidationUtils;
import org.apache.dubbo.registry.client.metadata.MetadataUtils;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.directory.StaticDirectory;
import org.apache.dubbo.rpc.cluster.support.ClusterUtils;
import org.apache.dubbo.rpc.cluster.support.registry.ZoneAwareCluster;
import org.apache.dubbo.rpc.model.AsyncMethodInfo;
import org.apache.dubbo.rpc.model.ConsumerModel;
import org.apache.dubbo.rpc.model.DubboStub;
import org.apache.dubbo.rpc.model.ModuleModel;
import org.apache.dubbo.rpc.model.ModuleServiceRepository;
import org.apache.dubbo.rpc.model.ScopeModel;
import org.apache.dubbo.rpc.model.ServiceDescriptor;
import org.apache.dubbo.rpc.protocol.injvm.InjvmProtocol;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.stub.StubSuppliers;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.beans.Transient;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.CLUSTER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SEPARATOR_CHAR;
import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_CLUSTER_DOMAIN;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_MESH_PORT;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.LOCALHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.MESH_ENABLE;
import static org.apache.dubbo.common.constants.CommonConstants.METHODS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROXY_CLASS_REF;
import static org.apache.dubbo.common.constants.CommonConstants.REVISION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SEMICOLON_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SVC;
import static org.apache.dubbo.common.constants.CommonConstants.TRIPLE;
import static org.apache.dubbo.common.constants.CommonConstants.UNLOAD_CLUSTER_RELATED;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.CLUSTER_NO_VALID_PROVIDER;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.CONFIG_FAILED_DESTROY_INVOKER;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.CONFIG_FAILED_LOAD_ENV_VARIABLE;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.CONFIG_NO_METHOD_FOUND;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.CONFIG_PROPERTY_CONFLICT;
import static org.apache.dubbo.common.constants.RegistryConstants.PROVIDED_BY;
import static org.apache.dubbo.common.constants.RegistryConstants.SUBSCRIBED_SERVICE_NAMES_KEY;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidLocalHost;
import static org.apache.dubbo.common.utils.StringUtils.splitToSet;
import static org.apache.dubbo.config.Constants.DUBBO_IP_TO_REGISTRY;
import static org.apache.dubbo.registry.Constants.CONSUMER_PROTOCOL;
import static org.apache.dubbo.registry.Constants.REGISTER_IP_KEY;
import static org.apache.dubbo.rpc.Constants.GENERIC_KEY;
import static org.apache.dubbo.rpc.Constants.LOCAL_PROTOCOL;
import static org.apache.dubbo.rpc.cluster.Constants.PEER_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.REFER_KEY;

/**
 * Please avoid using this class for any new application,
 * use {@link ReferenceConfigBase} instead.
 */
public class ReferenceConfig<T> extends ReferenceConfigBase<T> {

    public static final ErrorTypeAwareLogger logger = LoggerFactory.getErrorTypeAwareLogger(ReferenceConfig.class);

    /**
     * The {@link Protocol} implementation with adaptive functionality,it will be different in different scenarios.
     * A particular {@link Protocol} implementation is determined by the protocol attribute in the {@link URL}.
     * For example:
     *
     * <li>when the url is registry://224.5.6.7:1234/org.apache.dubbo.registry.RegistryService?application=dubbo-sample,
     * then the protocol is <b>RegistryProtocol</b></li>
     *
     * <li>when the url is dubbo://224.5.6.7:1234/org.apache.dubbo.config.api.DemoService?application=dubbo-sample, then
     * the protocol is <b>DubboProtocol</b></li>
     * <p>
     * Actually，when the {@link ExtensionLoader} init the {@link Protocol} instants,it will automatically wrap three
     * layers, and eventually will get a <b>ProtocolSerializationWrapper</b> or <b>ProtocolFilterWrapper</b> or <b>ProtocolListenerWrapper</b>
     */
    private Protocol protocolSPI;

    /**
     * A {@link ProxyFactory} implementation that will generate a reference service's proxy,the JavassistProxyFactory is
     * its default implementation
     */
    private ProxyFactory proxyFactory;

    private ConsumerModel consumerModel;

    /**
     * The interface proxy reference
     */
    private transient volatile T ref;

    /**
     * The invoker of the reference service
     */
    private transient volatile Invoker<?> invoker;

    /**
     * The flag whether the ReferenceConfig has been initialized
     */
    private transient volatile boolean initialized;

    /**
     * whether this ReferenceConfig has been destroyed
     */
    private transient volatile boolean destroyed;

    /**
     * The service names that the Dubbo interface subscribed.
     *
     * @since 2.7.8
     */
    private String services;

    public ReferenceConfig() {
        super();
    }

    public ReferenceConfig(ModuleModel moduleModel) {
        super(moduleModel);
    }

    public ReferenceConfig(Reference reference) {
        super(reference);
    }

    public ReferenceConfig(ModuleModel moduleModel, Reference reference) {
        super(moduleModel, reference);
    }

    @Override
    protected void postProcessAfterScopeModelChanged(ScopeModel oldScopeModel, ScopeModel newScopeModel) {
        super.postProcessAfterScopeModelChanged(oldScopeModel, newScopeModel);

        protocolSPI = this.getExtensionLoader(Protocol.class).getAdaptiveExtension();
        proxyFactory = this.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();
    }

    /**
     * Get a string presenting the service names that the Dubbo interface subscribed.
     * If it is a multiple-values, the content will be a comma-delimited String.
     *
     * @return non-null
     * @see RegistryConstants#SUBSCRIBED_SERVICE_NAMES_KEY
     * @since 2.7.8
     */
    @Deprecated
    @Parameter(key = SUBSCRIBED_SERVICE_NAMES_KEY)
    public String getServices() {
        return services;
    }

    /**
     * It's an alias method for {@link #getServices()}, but the more convenient.
     *
     * @return the String {@link List} presenting the Dubbo interface subscribed
     * @since 2.7.8
     */
    @Deprecated
    @Parameter(excluded = true)
    public Set<String> getSubscribedServices() {
        return splitToSet(getServices(), COMMA_SEPARATOR_CHAR);
    }

    /**
     * Set the service names that the Dubbo interface subscribed.
     *
     * @param services If it is a multiple-values, the content will be a comma-delimited String.
     * @since 2.7.8
     */
    public void setServices(String services) {
        this.services = services;
    }

    @Override
    @Transient
    public T get(boolean check) {
        if (destroyed) {
            throw new IllegalStateException("The invoker of ReferenceConfig(" + url + ") has already destroyed!");
        }

        //ref类型为 transient volatile T ref;
        if (ref == null) {
            if (getScopeModel().isLifeCycleManagedExternally()) {
                // prepare model for reference
                getScopeModel().getDeployer().prepare();
            } else {
                // ensure start module, compatible with old api usage
                getScopeModel().getDeployer().start();
            }

            // 如果 ref 对象为空，这就初始化一个
            // 说明引用提供方的每个接口都有着与之对应的一个 ref 对象
            init(check);
        }

        return ref;
    }

    @Override
    public void checkOrDestroy(long timeout) {
        if (!initialized || ref == null) {
            return;
        }
        try {
            checkInvokerAvailable(timeout);
        } catch (Throwable t) {
            logAndCleanup(t);
            throw t;
        }
    }

    private void logAndCleanup(Throwable t) {
        try {
            if (invoker != null) {
                invoker.destroy();
            }
        } catch (Throwable destroy) {
            logger.warn(
                    CONFIG_FAILED_DESTROY_INVOKER,
                    "",
                    "",
                    "Unexpected error occurred when destroy invoker of ReferenceConfig(" + url + ").",
                    t);
        }
        if (consumerModel != null) {
            ModuleServiceRepository repository = getScopeModel().getServiceRepository();
            repository.unregisterConsumer(consumerModel);
        }
        initialized = false;
        invoker = null;
        ref = null;
        consumerModel = null;
        serviceMetadata.setTarget(null);
        serviceMetadata.getAttributeMap().remove(PROXY_CLASS_REF);

        // Thrown by checkInvokerAvailable().
        if (t.getClass() == IllegalStateException.class
                && t.getMessage().contains("No provider available for the service")) {

            // 2-2 - No provider available.
            logger.error(CLUSTER_NO_VALID_PROVIDER, "server crashed", "", "No provider available.", t);
        }
    }

    @Override
    public synchronized void destroy() {
        super.destroy();
        if (destroyed) {
            return;
        }
        destroyed = true;
        try {
            if (invoker != null) {
                invoker.destroy();
            }
        } catch (Throwable t) {
            logger.warn(
                    CONFIG_FAILED_DESTROY_INVOKER,
                    "",
                    "",
                    "Unexpected error occurred when destroy invoker of ReferenceConfig(" + url + ").",
                    t);
        }
        invoker = null;
        ref = null;
        if (consumerModel != null) {
            ModuleServiceRepository repository = getScopeModel().getServiceRepository();
            repository.unregisterConsumer(consumerModel);
        }
    }

    protected synchronized void init() {
        init(true);
    }

    protected synchronized void init(boolean check) {
        if (initialized && ref != null) {
            return;
        }
        try {
            if (!this.isRefreshed()) {
                this.refresh();
            }
            // auto detect proxy type
            // 获取代理的方式：jdk、javassist
            String proxyType = getProxy();
            if (StringUtils.isBlank(proxyType) && DubboStub.class.isAssignableFrom(interfaceClass)) {
                setProxy(CommonConstants.NATIVE_STUB);
            }

            // init serviceMetadata
            // 初始化ServiceMetadata类型对象serviceMetadata 为其设置服务基本属性比如版本号，分组，服务接口名
            initServiceMetadata(consumer);

            // 初始化元数据信息 服务接口类型和key
            serviceMetadata.setServiceType(getServiceInterfaceClass());
            // TODO, uncomment this line once service key is unified
            serviceMetadata.generateServiceKey();

            // 配置转Map类型
            Map<String, String> referenceParameters = appendConfig();

            // 本地内存模块服务存储库
            ModuleServiceRepository repository = getScopeModel().getServiceRepository();
            ServiceDescriptor serviceDescriptor;
            if (CommonConstants.NATIVE_STUB.equals(getProxy())) {
                serviceDescriptor = StubSuppliers.getServiceDescriptor(interfaceName);
                repository.registerService(serviceDescriptor);
                setInterface(serviceDescriptor.getInterfaceName());
            } else {
                serviceDescriptor = repository.registerService(interfaceClass);
            }
            //消费者模型对象
            consumerModel = new ConsumerModel(
                    serviceMetadata.getServiceKey(),
                    proxy,
                    serviceDescriptor,
                    getScopeModel(),
                    serviceMetadata,
                    createAsyncMethodInfo(),
                    interfaceClassLoader);

            // Compatible with dependencies on ServiceModel#getReferenceConfig() , and will be removed in a future
            // version.
            consumerModel.setConfig(this);

            // 本地存储库注册消费者模型对象
            repository.registerConsumer(consumerModel);

            //与前面代码一样基础初始化服务元数据对象为其设置附加参数
            serviceMetadata.getAttachments().putAll(referenceParameters);

            // 创建代理对象
            // 根据消费端参数创建服务的代理对象
            ref = createProxy(referenceParameters);

            //为服务元数据对象设置代理对象
            serviceMetadata.setTarget(ref);
            // refClass
            serviceMetadata.addAttribute(PROXY_CLASS_REF, ref);

            consumerModel.setDestroyRunner(getDestroyRunner());
            consumerModel.setProxyObject(ref);
            consumerModel.initMethodModels();

            if (check) {
                //检查invoker对象初始结果
                checkInvokerAvailable(0);
            }
        } catch (Throwable t) {
            logAndCleanup(t);

            throw t;
        }
        initialized = true;
    }

    /**
     * convert and aggregate async method info
     *
     * @return Map<String, AsyncMethodInfo>
     */
    private Map<String, AsyncMethodInfo> createAsyncMethodInfo() {
        Map<String, AsyncMethodInfo> attributes = null;
        if (CollectionUtils.isNotEmpty(getMethods())) {
            attributes = new HashMap<>(16);
            for (MethodConfig methodConfig : getMethods()) {
                AsyncMethodInfo asyncMethodInfo = methodConfig.convertMethodConfig2AsyncInfo();
                if (asyncMethodInfo != null) {
                    attributes.put(methodConfig.getName(), asyncMethodInfo);
                }
            }
        }

        return attributes;
    }

    /**
     * Append all configuration required for service reference.
     *
     * @return reference parameters
     */
    private Map<String, String> appendConfig() {
        Map<String, String> map = new HashMap<>(16);

        // interface
        map.put(INTERFACE_KEY, interfaceName);
        // side
        map.put(SIDE_KEY, CONSUMER_SIDE);

        // 添加运行时相关的参数
        ReferenceConfigBase.appendRuntimeParameters(map);

        // 非泛化调用
        if (!ProtocolUtils.isGeneric(generic)) {
            String revision = Version.getVersion(interfaceClass, version);
            if (StringUtils.isNotEmpty(revision)) {
                map.put(REVISION_KEY, revision);
            }

            String[] methods = methods(interfaceClass);
            if (methods.length == 0) {
                logger.warn(
                        CONFIG_NO_METHOD_FOUND,
                        "",
                        "",
                        "No method found in service interface: " + interfaceClass.getName());
                map.put(METHODS_KEY, ANY_VALUE);
            } else {
                map.put(METHODS_KEY, StringUtils.join(new TreeSet<>(Arrays.asList(methods)), COMMA_SEPARATOR));
            }
        }

        // 添加Application级别参数
        AbstractConfig.appendParameters(map, getApplication());
        // 添加Module级别参数
        AbstractConfig.appendParameters(map, getModule());
        // 添加Consumer级别参数
        AbstractConfig.appendParameters(map, consumer);
        // 添加当前Reference参数
        AbstractConfig.appendParameters(map, this);

        String hostToRegistry = ConfigUtils.getSystemProperty(DUBBO_IP_TO_REGISTRY);
        if (StringUtils.isEmpty(hostToRegistry)) {
            hostToRegistry = NetUtils.getLocalHost();
        } else if (isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException("Specified invalid registry ip from property:" + DUBBO_IP_TO_REGISTRY
                    + ", value:" + hostToRegistry);
        }

        map.put(REGISTER_IP_KEY, hostToRegistry);

        if (CollectionUtils.isNotEmpty(getMethods())) {
            for (MethodConfig methodConfig : getMethods()) {
                AbstractConfig.appendParameters(map, methodConfig, methodConfig.getName());
                String retryKey = methodConfig.getName() + ".retry";
                if (map.containsKey(retryKey)) {
                    String retryValue = map.remove(retryKey);
                    if ("false".equals(retryValue)) {
                        map.put(methodConfig.getName() + ".retries", "0");
                    }
                }
            }
        }

        return map;
    }

    @SuppressWarnings({"unchecked"})
    private T createProxy(Map<String, String> referenceParameters) {
        urls.clear();

        meshModeHandleUrl(referenceParameters);

        if (StringUtils.isNotEmpty(url)) {
            // user specified URL, could be peer-to-peer address, or register center's address.
            // url存在则为点对点引用  直连生产者
            parseUrl(referenceParameters);
        } else {
            // if protocols not in jvm checkRegistry
            // 从注册表中获取URL并将其聚合。这个其实就是初始化一下注册中心的url配置
            aggregateUrlFromRegistry(referenceParameters);
        }
        // 创建远程引用，创建远程引用调用器
        createInvoker();

        if (logger.isInfoEnabled()) {
            logger.info("Referred dubbo service: [" + referenceParameters.get(INTERFACE_KEY) + "]."
                    + (ProtocolUtils.isGeneric(referenceParameters.get(GENERIC_KEY))
                            ? " it's GenericService reference"
                            : " it's not GenericService reference"));
        }

        URL consumerUrl = new ServiceConfigURL(
                CONSUMER_PROTOCOL,
                referenceParameters.get(REGISTER_IP_KEY),
                0,
                referenceParameters.get(INTERFACE_KEY),
                referenceParameters);
        consumerUrl = consumerUrl.setScopeModel(getScopeModel());
        consumerUrl = consumerUrl.setServiceModel(consumerModel); // 上报消费者元数据， report-consumer-definition设置为true才会上报
        MetadataUtils.publishServiceDefinition(consumerUrl, consumerModel.getServiceModel(), getApplicationModel());

        // create service proxy
        // 创建刚刚创建出来的 invoker 对象通过调用proxyFactory.getProxy方法包装成代理对象
        return (T) proxyFactory.getProxy(invoker, ProtocolUtils.isGeneric(generic));
    }

    /**
     * if enable mesh mode, handle url.
     *
     * @param referenceParameters referenceParameters
     */
    private void meshModeHandleUrl(Map<String, String> referenceParameters) {
        if (!checkMeshConfig(referenceParameters)) {
            return;
        }
        if (StringUtils.isNotEmpty(url)) {
            // user specified URL, could be peer-to-peer address, or register center's address.
            if (logger.isInfoEnabled()) {
                logger.info("The url already exists, mesh no longer processes url: " + url);
            }
            return;
        }

        // get provider namespace if (@DubboReference, <reference provider-namespace="xx"/>) present
        String podNamespace = referenceParameters.get(RegistryConstants.PROVIDER_NAMESPACE);

        // get pod namespace from env if annotation not present the provider namespace
        if (StringUtils.isEmpty(podNamespace)) {
            if (StringUtils.isEmpty(System.getenv("POD_NAMESPACE"))) {
                if (logger.isWarnEnabled()) {
                    logger.warn(
                            CONFIG_FAILED_LOAD_ENV_VARIABLE,
                            "",
                            "",
                            "Can not get env variable: POD_NAMESPACE, it may not be running in the K8S environment , "
                                    + "finally use 'default' replace.");
                }
                podNamespace = "default";
            } else {
                podNamespace = System.getenv("POD_NAMESPACE");
            }
        }

        // In mesh mode, providedBy equals K8S Service name.
        String providedBy = referenceParameters.get(PROVIDED_BY);
        // cluster_domain default is 'cluster.local',generally unchanged.
        String clusterDomain =
                Optional.ofNullable(System.getenv("CLUSTER_DOMAIN")).orElse(DEFAULT_CLUSTER_DOMAIN);
        // By VirtualService and DestinationRule, envoy will generate a new route rule,such as
        // 'demo.default.svc.cluster.local:80',the default port is 80.
        Integer meshPort = Optional.ofNullable(getProviderPort()).orElse(DEFAULT_MESH_PORT);
        // DubboReference default is -1, process it.
        meshPort = meshPort > -1 ? meshPort : DEFAULT_MESH_PORT;
        // get mesh url.
        url = TRIPLE + "://" + providedBy + "." + podNamespace + SVC + clusterDomain + ":" + meshPort;
    }

    /**
     * check if mesh config is correct
     *
     * @param referenceParameters referenceParameters
     * @return mesh config is correct
     */
    private boolean checkMeshConfig(Map<String, String> referenceParameters) {
        if (!"true".equals(referenceParameters.getOrDefault(MESH_ENABLE, "false"))) {
            // In mesh mode, unloadClusterRelated can only be false.
            referenceParameters.put(UNLOAD_CLUSTER_RELATED, "false");
            return false;
        }

        getScopeModel()
                .getConfigManager()
                .getProtocol(TRIPLE)
                .orElseThrow(() -> new IllegalStateException("In mesh mode, a triple protocol must be specified"));

        String providedBy = referenceParameters.get(PROVIDED_BY);
        if (StringUtils.isEmpty(providedBy)) {
            throw new IllegalStateException("In mesh mode, the providedBy of ReferenceConfig is must be set");
        }

        return true;
    }

    /**
     * Parse the directly configured url.
     */
    private void parseUrl(Map<String, String> referenceParameters) {
        // ,分割 点对点直连url
        String[] us = SEMICOLON_SPLIT_PATTERN.split(url);
        if (ArrayUtils.isNotEmpty(us)) {
            for (String u : us) {
                URL url = URL.valueOf(u);
                if (StringUtils.isEmpty(url.getPath())) {
                    url = url.setPath(interfaceName);
                }
                url = url.setScopeModel(getScopeModel());
                url = url.setServiceModel(consumerModel);
                // 解析，保存到urls集合
                // 若解析的过程中发现是注册中心地址的话
                // 那么就会将服务引用的 referenceParameters 整体信息归到注册中心地址的 "refer" 属性上
                if (UrlUtils.isRegistry(url)) {
                    urls.add(url.putAttribute(REFER_KEY, referenceParameters));
                } else {
                    // 直连的话则添加到"peer"参数
                    URL peerUrl = getScopeModel()
                            .getApplicationModel()
                            .getBeanFactory()
                            .getBean(ClusterUtils.class)
                            .mergeUrl(url, referenceParameters);
                    peerUrl = peerUrl.putAttribute(PEER_KEY, true);
                    urls.add(peerUrl);
                }
            }
        }
    }

    /**
     * Get URLs from the registry and aggregate them.
     */
    private void aggregateUrlFromRegistry(Map<String, String> referenceParameters) {
        checkRegistry();
        // 加载注册中心URL,Registries 可以指定从哪个注册中心获取
        List<URL> us = ConfigValidationUtils.loadRegistries(this, false);
        if (CollectionUtils.isNotEmpty(us)) {
            for (URL u : us) {
                URL monitorUrl = ConfigValidationUtils.loadMonitor(this, u);
                if (monitorUrl != null) {
                    u = u.putAttribute(MONITOR_KEY, monitorUrl);
                }
                u = u.setScopeModel(getScopeModel());
                u = u.setServiceModel(consumerModel);
                if (isInjvm() != null && isInjvm()) {
                    u = u.addParameter(LOCAL_PROTOCOL, true);
                }
                // 给注册中心URL添加要引用的服务参数:refer
                urls.add(u.putAttribute(REFER_KEY, referenceParameters));
            }
        }
        // injvm
        // 如果在同一个JVM里，Consumer要引用的服务自身已经提供了，那么Dubbo会尝试直接引用同一个JVM内的服务，这样可以避免网络传输带来的性能损耗
        if (urls.isEmpty() && shouldJvmRefer(referenceParameters)) {
            // 优先引用同一个JVM内的Provider
            URL injvmUrl = new URL(LOCAL_PROTOCOL, LOCALHOST_VALUE, 0, interfaceClass.getName())
                    .addParameters(referenceParameters);
            injvmUrl = injvmUrl.setScopeModel(getScopeModel());
            injvmUrl = injvmUrl.setServiceModel(consumerModel);
            urls.add(injvmUrl.putAttribute(REFER_KEY, referenceParameters));
        }
        if (urls.isEmpty()) {
            throw new IllegalStateException("No such any registry to reference " + interfaceName + " on the consumer "
                    + NetUtils.getLocalHost() + " use dubbo version "
                    + Version.getVersion()
                    + ", please config <dubbo:registry address=\"...\" /> to your spring config.");
        }
    }

    /**
     * create a reference invoker
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void createInvoker() {
        // 这个url 为注册协议如：
        // registry://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=dubbo-demo-api-consumer&dubbo=2.0.2&pid=6204&qos.enable=false&qos.port=-1&registry=zookeeper&release=3.0.9&timestamp=1657439419495
        // 单注册
        if (urls.size() == 1) {
            URL curUrl = urls.get(0);
            // 这个SPI对象是由字节码动态生成的自适应对象Protocol$Adaptie
            invoker = protocolSPI.refer(interfaceClass, curUrl);
            // registry url, mesh-enable and unloadClusterRelated is true, not need Cluster.
            if (!UrlUtils.isRegistry(curUrl) && !curUrl.getParameter(UNLOAD_CLUSTER_RELATED, false)) {
                List<Invoker<?>> invokers = new ArrayList<>();
                invokers.add(invoker);
                invoker = Cluster.getCluster(getScopeModel(), Cluster.DEFAULT)
                        .join(new StaticDirectory(curUrl, invokers), true);
            }
        } else {
            List<Invoker<?>> invokers = new ArrayList<>();
            URL registryUrl = null;
            for (URL url : urls) {
                // For multi-registry scenarios, it is not checked whether each referInvoker is available.
                // Because this invoker may become available later.
                // RegisterProtocol会修改url refer
                invokers.add(protocolSPI.refer(interfaceClass, url));

                if (UrlUtils.isRegistry(url)) {
                    // use last registry url
                    registryUrl = url;
                }
            }

            // 如果循环完了之后，发现含有注册中心地址的话，
            // 那就继续用集群扩展器包装起来
            if (registryUrl != null) {
                // registry url is available
                // for multi-subscription scenario, use 'zone-aware' policy by default
                String cluster = registryUrl.getParameter(CLUSTER_KEY, ZoneAwareCluster.NAME);
                // The invoker wrap sequence would be: ZoneAwareClusterInvoker(StaticDirectory) ->
                // FailoverClusterInvoker
                // (RegistryDirectory, routing happens here) -> Invoker
                invoker = Cluster.getCluster(registryUrl.getScopeModel(), cluster, false)
                        .join(new StaticDirectory(registryUrl, invokers), false);
            } else {
                // not a registry url, must be direct invoke.
                if (CollectionUtils.isEmpty(invokers)) {
                    throw new IllegalArgumentException("invokers == null");
                }
                URL curUrl = invokers.get(0).getUrl();
                String cluster = curUrl.getParameter(CLUSTER_KEY, Cluster.DEFAULT);
                invoker =
                        Cluster.getCluster(getScopeModel(), cluster).join(new StaticDirectory(curUrl, invokers), true);
            }
        }
    }

    private void checkInvokerAvailable(long timeout) throws IllegalStateException {
        if (!shouldCheck()) {
            return;
        }
        boolean available = invoker.isAvailable();
        if (available) {
            return;
        }

        long startTime = System.currentTimeMillis();
        long checkDeadline = startTime + timeout;
        do {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            available = invoker.isAvailable();
        } while (!available && checkDeadline > System.currentTimeMillis());
        logger.warn(
                LoggerCodeConstants.REGISTRY_EMPTY_ADDRESS,
                "",
                "",
                "Check reference of [" + getUniqueServiceName() + "] failed very beginning. " + "After "
                        + (System.currentTimeMillis() - startTime) + "ms reties, finally "
                        + (available ? "succeed" : "failed")
                        + ".");
        if (!available) {
            // 2-2 - No provider available.

            IllegalStateException illegalStateException =
                    new IllegalStateException("Failed to check the status of the service "
                            + interfaceName
                            + ". No provider available for the service "
                            + (group == null ? "" : group + "/")
                            + interfaceName + (version == null ? "" : ":" + version)
                            + " from the url "
                            + invoker.getUrl()
                            + " to the consumer "
                            + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion());

            logger.error(
                    CLUSTER_NO_VALID_PROVIDER,
                    "provider not started",
                    "",
                    "No provider available.",
                    illegalStateException);

            throw illegalStateException;
        }
    }

    /**
     * This method should be called right after the creation of this class's instance, before any property in other config modules is used.
     * Check each config modules are created properly and override their properties if necessary.
     */
    protected void checkAndUpdateSubConfigs() {
        if (StringUtils.isEmpty(interfaceName)) {
            throw new IllegalStateException("<dubbo:reference interface=\"\" /> interface not allow null!");
        }

        // get consumer's global configuration
        completeCompoundConfigs();

        // init some null configuration.
        List<ConfigInitializer> configInitializers = this.getExtensionLoader(ConfigInitializer.class)
                .getActivateExtension(URL.valueOf("configInitializer://"), (String[]) null);
        configInitializers.forEach(e -> e.initReferConfig(this));

        if (getGeneric() == null && getConsumer() != null) {
            setGeneric(getConsumer().getGeneric());
        }
        if (ProtocolUtils.isGeneric(generic)) {
            if (interfaceClass != null && !interfaceClass.equals(GenericService.class)) {
                logger.warn(
                        CONFIG_PROPERTY_CONFLICT,
                        "",
                        "",
                        String.format(
                                "Found conflicting attributes for interface type: [interfaceClass=%s] and [generic=%s], "
                                        + "because the 'generic' attribute has higher priority than 'interfaceClass', so change 'interfaceClass' to '%s'. "
                                        + "Note: it will make this reference bean as a candidate bean of type '%s' instead of '%s' when resolving dependency in Spring.",
                                interfaceClass.getName(),
                                generic,
                                GenericService.class.getName(),
                                GenericService.class.getName(),
                                interfaceClass.getName()));
            }
            interfaceClass = GenericService.class;
        } else {
            try {
                if (getInterfaceClassLoader() != null
                        && (interfaceClass == null || interfaceClass.getClassLoader() != getInterfaceClassLoader())) {
                    interfaceClass = Class.forName(interfaceName, true, getInterfaceClassLoader());
                } else if (interfaceClass == null) {
                    interfaceClass = Class.forName(
                            interfaceName, true, Thread.currentThread().getContextClassLoader());
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }

        checkStubAndLocal(interfaceClass);
        ConfigValidationUtils.checkMock(interfaceClass, this);

        if (StringUtils.isEmpty(url)) {
            checkRegistry();
        }

        resolveFile();
        ConfigValidationUtils.validateReferenceConfig(this);
        postProcessConfig();
    }

    @Override
    protected void postProcessRefresh() {
        super.postProcessRefresh();
        checkAndUpdateSubConfigs();
    }

    protected void completeCompoundConfigs() {
        super.completeCompoundConfigs(consumer);
        if (consumer != null) {
            if (StringUtils.isEmpty(registryIds)) {
                setRegistryIds(consumer.getRegistryIds());
            }
        }
    }

    /**
     * Figure out should refer the service in the same JVM from configurations. The default behavior is true
     * 1. if injvm is specified, then use it
     * 2. then if a url is specified, then assume it's a remote call
     * 3. otherwise, check scope parameter
     * 4. if scope is not specified but the target service is provided in the same JVM, then prefer to make the local
     * call, which is the default behavior
     */
    protected boolean shouldJvmRefer(Map<String, String> map) {
        boolean isJvmRefer;
        if (isInjvm() == null) {
            // if an url is specified, don't do local reference
            // 点对点调用的url不为空
            if (StringUtils.isNotEmpty(url)) {
                isJvmRefer = false;
            } else {
                // by default, reference local service if there is
                URL tmpUrl = new ServiceConfigURL("temp", "localhost", 0, map);
                isJvmRefer = InjvmProtocol.getInjvmProtocol(getScopeModel()).isInjvmRefer(tmpUrl);
            }
        } else {
            isJvmRefer = isInjvm();
        }
        return isJvmRefer;
    }

    private void postProcessConfig() {
        List<ConfigPostProcessor> configPostProcessors = this.getExtensionLoader(ConfigPostProcessor.class)
                .getActivateExtension(URL.valueOf("configPostProcessor://"), (String[]) null);
        configPostProcessors.forEach(component -> component.postProcessReferConfig(this));
    }

    /**
     * Return if ReferenceConfig has been initialized
     * Note: Cannot use `isInitilized` as it may be treated as a Java Bean property
     *
     * @return initialized
     */
    @Transient
    public boolean configInitialized() {
        return initialized;
    }

    /**
     * just for test
     *
     * @return
     */
    @Deprecated
    @Transient
    public Invoker<?> getInvoker() {
        return invoker;
    }

    @Transient
    public Runnable getDestroyRunner() {
        return this::destroy;
    }
}
