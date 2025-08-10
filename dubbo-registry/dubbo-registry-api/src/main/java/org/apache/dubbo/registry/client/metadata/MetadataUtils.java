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
package org.apache.dubbo.registry.client.metadata;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.metadata.MetadataInfo;
import org.apache.dubbo.metadata.MetadataService;
import org.apache.dubbo.metadata.definition.model.FullServiceDefinition;
import org.apache.dubbo.metadata.report.MetadataReport;
import org.apache.dubbo.metadata.report.MetadataReportInstance;
import org.apache.dubbo.metadata.report.identifier.MetadataIdentifier;
import org.apache.dubbo.metadata.report.identifier.SubscriberMetadataIdentifier;
import org.apache.dubbo.registry.client.ServiceInstance;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ConsumerModel;
import org.apache.dubbo.rpc.model.ModuleModel;
import org.apache.dubbo.rpc.model.ServiceDescriptor;
import org.apache.dubbo.rpc.service.Destroyable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.PROXY_CLASS_REF;
import static org.apache.dubbo.common.constants.CommonConstants.REMOTE_METADATA_STORAGE_TYPE;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.REGISTRY_FAILED_CREATE_INSTANCE;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.REGISTRY_FAILED_LOAD_METADATA;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_CLUSTER_KEY;
import static org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils.METADATA_SERVICE_URLS_PROPERTY_NAME;

public class MetadataUtils {
    public static final ErrorTypeAwareLogger logger = LoggerFactory.getErrorTypeAwareLogger(MetadataUtils.class);

    /**
     * 发布Service元数据
     * @param url
     * @param serviceDescriptor
     * @param applicationModel
     */
    public static void publishServiceDefinition(
            URL url, ServiceDescriptor serviceDescriptor, ApplicationModel applicationModel) {
        if (getMetadataReports(applicationModel).size() == 0) {
            String msg =
                    "Remote Metadata Report Server is not provided or unavailable, will stop registering service definition to remote center!";
            logger.warn(REGISTRY_FAILED_LOAD_METADATA, "", "", msg);
            return;
        }

        try {
            String side = url.getSide();
            // 生产端
            if (PROVIDER_SIDE.equalsIgnoreCase(side)) {
                String serviceKey = url.getServiceKey();
                FullServiceDefinition serviceDefinition = serviceDescriptor.getFullServiceDefinition(serviceKey);

                if (StringUtils.isNotEmpty(serviceKey) && serviceDefinition != null) {
                    serviceDefinition.setParameters(url.getParameters());
                    for (Map.Entry<String, MetadataReport> entry :
                            getMetadataReports(applicationModel).entrySet()) {
                        MetadataReport metadataReport = entry.getValue();
                        if (!metadataReport.shouldReportDefinition()) {
                            logger.info("Report of service definition is disabled for " + entry.getKey());
                            continue;
                        }
                        metadataReport.storeProviderMetadata(
                                new MetadataIdentifier(
                                        url.getServiceInterface(),
                                        url.getVersion() == null ? "" : url.getVersion(),
                                        url.getGroup() == null ? "" : url.getGroup(),
                                        PROVIDER_SIDE,
                                        applicationModel.getApplicationName()),
                                serviceDefinition);
                    }
                }
            } else {
                // 消费端
                for (Map.Entry<String, MetadataReport> entry :
                        getMetadataReports(applicationModel).entrySet()) {
                    MetadataReport metadataReport = entry.getValue();
                    if (!metadataReport.shouldReportDefinition()) {
                        logger.info("Report of service definition is disabled for " + entry.getKey());
                        continue;
                    }
                    metadataReport.storeConsumerMetadata(
                            new MetadataIdentifier(
                                    url.getServiceInterface(),
                                    url.getVersion() == null ? "" : url.getVersion(),
                                    url.getGroup() == null ? "" : url.getGroup(),
                                    CONSUMER_SIDE,
                                    applicationModel.getApplicationName()),
                            url.getParameters());
                }
            }
        } catch (Exception e) {
            // ignore error
            logger.error(REGISTRY_FAILED_CREATE_INSTANCE, "", "", "publish service definition metadata error.", e);
        }
    }

    /**
     * 暴露MetadataService   元数据 LOCAL模式会执行此RPC
     * @param instance
     * @return
     */
    public static ProxyHolder referProxy(ServiceInstance instance) {
        MetadataServiceURLBuilder builder;
        ExtensionLoader<MetadataServiceURLBuilder> loader =
                instance.getApplicationModel().getExtensionLoader(MetadataServiceURLBuilder.class);

        Map<String, String> metadata = instance.getMetadata();
        // METADATA_SERVICE_URLS_PROPERTY_NAME is a unique key exists only on instances of spring-cloud-alibaba.
        String dubboUrlsForJson = metadata.get(METADATA_SERVICE_URLS_PROPERTY_NAME);
        if (metadata.isEmpty() || StringUtils.isEmpty(dubboUrlsForJson)) {
            builder = loader.getExtension(StandardMetadataServiceURLBuilder.NAME);
        } else {
            builder = loader.getExtension(SpringCloudMetadataServiceURLBuilder.NAME);
        }

        List<URL> urls = builder.build(instance);
        if (CollectionUtils.isEmpty(urls)) {
            throw new IllegalStateException("Introspection service discovery mode is enabled " + instance
                    + ", but no metadata service can build from it.");
        }

        URL url = urls.get(0);

        // Simply rely on the first metadata url, as stated in MetadataServiceURLBuilder.
        ApplicationModel applicationModel = instance.getApplicationModel();
        ModuleModel internalModel = applicationModel.getInternalModule();
        ConsumerModel consumerModel =
                applicationModel.getInternalModule().registerInternalConsumer(MetadataService.class, url);

        Protocol protocol = applicationModel.getExtensionLoader(Protocol.class).getExtension(url.getProtocol(), false);

        url = url.setServiceModel(consumerModel);

        Invoker<MetadataService> invoker = protocol.refer(MetadataService.class, url);

        ProxyFactory proxyFactory =
                applicationModel.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();

        MetadataService metadataService = proxyFactory.getProxy(invoker);

        consumerModel.getServiceMetadata().setTarget(metadataService);
        consumerModel.getServiceMetadata().addAttribute(PROXY_CLASS_REF, metadataService);
        consumerModel.setProxyObject(metadataService);
        consumerModel.initMethodModels();

        return new ProxyHolder(consumerModel, metadataService, internalModel);
    }

    /**
     * 获取远程的Metadata，分两种情况：
     * <ul>
     *     <ol>service metadata is remote mode,get metadata from metadata center</ol>
     *     <ol>service metadata is save in service locally,invoke MetadataService#getMetadataInfo by dubbo rpc</ol>
     * </ul>
     *
     * @param revision
     * @param instances
     * @param metadataReport
     * @return
     */
    public static MetadataInfo getRemoteMetadata(
            String revision, List<ServiceInstance> instances, MetadataReport metadataReport) {
        // 从应用中随机选取一个instance
        ServiceInstance instance = selectInstance(instances);
        // 判断元数据的存储模式 默认LOCAL
        String metadataType = ServiceInstanceMetadataUtils.getMetadataStorageType(instance);
        MetadataInfo metadataInfo;
        try {
            if (logger.isDebugEnabled()) {
                logger.debug("Instance " + instance.getAddress() + " is using metadata type " + metadataType);
            }
            // 远程获取
            if (REMOTE_METADATA_STORAGE_TYPE.equals(metadataType)) {
                metadataInfo = MetadataUtils.getMetadata(revision, instance, metadataReport);
            } else {
                // 通过MetadataService RPC去获取
                // change the instance used to communicate to avoid all requests route to the same instance
                ProxyHolder proxyHolder = null;
                try {
                    // 构建MetadataService的代理对象
                    proxyHolder = MetadataUtils.referProxy(instance);
                    metadataInfo = proxyHolder
                            .getProxy()
                            // 走RPC获取
                            .getMetadataInfo(ServiceInstanceMetadataUtils.getExportedServicesRevision(instance));
                } finally {
                    MetadataUtils.destroyProxy(proxyHolder);
                }
            }
        } catch (Exception e) {
            logger.error(
                    REGISTRY_FAILED_LOAD_METADATA,
                    "",
                    "",
                    "Failed to get app metadata for revision " + revision + " for type " + metadataType
                            + " from instance " + instance.getAddress(),
                    e);
            metadataInfo = null;
        }

        if (metadataInfo == null) {
            metadataInfo = MetadataInfo.EMPTY;
        }
        return metadataInfo;
    }

    public static void destroyProxy(ProxyHolder proxyHolder) {
        if (proxyHolder != null) {
            proxyHolder.destroy();
        }
    }

    public static MetadataInfo getMetadata(String revision, ServiceInstance instance, MetadataReport metadataReport) {
        SubscriberMetadataIdentifier identifier = new SubscriberMetadataIdentifier(instance.getServiceName(), revision);

        if (metadataReport == null) {
            throw new IllegalStateException("No valid remote metadata report specified.");
        }

        String registryCluster = instance.getRegistryCluster();
        Map<String, String> params = new HashMap<>(instance.getExtendParams());
        if (registryCluster != null && !registryCluster.equalsIgnoreCase(params.get(REGISTRY_CLUSTER_KEY))) {
            params.put(REGISTRY_CLUSTER_KEY, registryCluster);
        }

        return metadataReport.getAppMetadata(identifier, params);
    }

    private static Map<String, MetadataReport> getMetadataReports(ApplicationModel applicationModel) {
        return applicationModel
                .getBeanFactory()
                .getBean(MetadataReportInstance.class)
                .getMetadataReports(false);
    }

    private static ServiceInstance selectInstance(List<ServiceInstance> instances) {
        if (instances.size() == 1) {
            return instances.get(0);
        }
        return instances.get(ThreadLocalRandom.current().nextInt(0, instances.size()));
    }

    public static class ProxyHolder {
        private final ConsumerModel consumerModel;
        private final MetadataService proxy;
        private final ModuleModel internalModel;

        public ProxyHolder(ConsumerModel consumerModel, MetadataService proxy, ModuleModel internalModel) {
            this.consumerModel = consumerModel;
            this.proxy = proxy;
            this.internalModel = internalModel;
        }

        public void destroy() {
            if (proxy instanceof Destroyable) {
                ((Destroyable) proxy).$destroy();
            }
            internalModel.getServiceRepository().unregisterConsumer(consumerModel);
        }

        public ConsumerModel getConsumerModel() {
            return consumerModel;
        }

        public MetadataService getProxy() {
            return proxy;
        }

        public ModuleModel getInternalModel() {
            return internalModel;
        }
    }
}
