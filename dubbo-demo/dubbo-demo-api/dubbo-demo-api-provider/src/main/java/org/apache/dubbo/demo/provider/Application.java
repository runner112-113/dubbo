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
package org.apache.dubbo.demo.provider;

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.config.*;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.demo.DemoService;

public class Application {

    private static final String REGISTRY_URL = "zookeeper://127.0.0.1:2181";
    private static final String REGISTRY_NACOS_URL = "nacos://124.222.122.96:8848";

    public static void main(String[] args) {
        startWithBootstrap();
    }

    private static void startWithBootstrap() {
        ServiceConfig<DemoServiceImpl> service = new ServiceConfig<>();
        service.setInterface(DemoService.class);
        service.setGroup("dev");
        service.setVersion("1.1.1");
        service.setRef(new DemoServiceImpl());

        DubboBootstrap bootstrap = DubboBootstrap.getInstance();
        RegistryConfig registryConfig = new RegistryConfig(REGISTRY_NACOS_URL);
        registryConfig.setRegisterMode("instance");

        MetadataReportConfig metadataReportConfig = new MetadataReportConfig();
        metadataReportConfig.setSyncReport(true);
        bootstrap
                .application(new ApplicationConfig("dubbo-demo-api-provider"))
                .registry(registryConfig)
                .protocol(new ProtocolConfig(CommonConstants.DUBBO, -1))
                .service(service)
                .metadataReport(metadataReportConfig)
                .start()
                .await();
    }
}
