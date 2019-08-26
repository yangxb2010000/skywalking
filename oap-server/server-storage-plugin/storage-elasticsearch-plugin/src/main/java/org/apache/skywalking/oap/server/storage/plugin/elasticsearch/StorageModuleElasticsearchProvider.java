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
 *
 */

package org.apache.skywalking.oap.server.storage.plugin.elasticsearch;

import java.io.IOException;
import org.apache.skywalking.apm.util.StringUtil;
import org.apache.skywalking.oap.server.core.CoreModule;
import org.apache.skywalking.oap.server.core.config.ConfigService;
import org.apache.skywalking.oap.server.core.storage.*;
import org.apache.skywalking.oap.server.core.storage.cache.*;
import org.apache.skywalking.oap.server.core.storage.query.*;
import org.apache.skywalking.oap.server.library.client.elasticsearch.ElasticSearchClient;
import org.apache.skywalking.oap.server.library.module.*;
import org.apache.skywalking.oap.server.storage.plugin.elasticsearch.base.*;
import org.apache.skywalking.oap.server.storage.plugin.elasticsearch.cache.*;
import org.apache.skywalking.oap.server.storage.plugin.elasticsearch.lock.*;
import org.apache.skywalking.oap.server.storage.plugin.elasticsearch.query.*;
import org.apache.skywalking.oap.server.storage.plugin.elasticsearch.ttl.ElasticsearchStorageTTL;

/**
 * @author peng-yongsheng
 */
public class StorageModuleElasticsearchProvider extends ModuleProvider {

    protected final StorageModuleElasticsearchConfig config;
    protected ElasticSearchClient elasticSearchClient;

    public StorageModuleElasticsearchProvider() {
        super();
        this.config = new StorageModuleElasticsearchConfig();
    }

    @Override
    public String name() {
        return "elasticsearch";
    }

    @Override
    public Class<? extends ModuleDefine> module() {
        return StorageModule.class;
    }

    @Override
    public ModuleConfig createConfigBeanIfAbsent() {
        return config;
    }

    @Override
    public void prepare() throws ServiceNotProvidedException {
        if (!StringUtil.isEmpty(config.getNameSpace())) {
            config.setNameSpace(config.getNameSpace().toLowerCase());
        }
        elasticSearchClient = new ElasticSearchClient(config.getClusterNodes(), config.getProtocol(), config.getNameSpace(), config.getUser(), config.getPassword());

        this.registerServiceImplementation(IBatchDAO.class, new BatchProcessEsDAO(elasticSearchClient, config.getBulkActions(), config.getFlushInterval(), config.getConcurrentRequests()));
        this.registerServiceImplementation(StorageDAO.class, new StorageEsDAO(elasticSearchClient));
        this.registerServiceImplementation(IRegisterLockDAO.class, new RegisterLockDAOImpl(elasticSearchClient));
        this.registerServiceImplementation(IHistoryDeleteDAO.class, new HistoryDeleteEsDAO(getManager(), elasticSearchClient, new ElasticsearchStorageTTL()));

        this.registerServiceImplementation(IServiceInventoryCacheDAO.class, new ServiceInventoryCacheEsDAO(elasticSearchClient));
        this.registerServiceImplementation(IServiceInstanceInventoryCacheDAO.class, new ServiceInstanceInventoryCacheDAO(elasticSearchClient));
        this.registerServiceImplementation(IEndpointInventoryCacheDAO.class, new EndpointInventoryCacheEsDAO(elasticSearchClient));
        this.registerServiceImplementation(INetworkAddressInventoryCacheDAO.class, new NetworkAddressInventoryCacheEsDAO(elasticSearchClient));

        this.registerServiceImplementation(ITopologyQueryDAO.class, new TopologyQueryEsDAO(elasticSearchClient));
        this.registerServiceImplementation(IMetricsQueryDAO.class, new MetricsQueryEsDAO(elasticSearchClient));
        this.registerServiceImplementation(ITraceQueryDAO.class, new TraceQueryEsDAO(elasticSearchClient, config.getSegmentQueryMaxSize()));
        this.registerServiceImplementation(IMetadataQueryDAO.class, new MetadataQueryEsDAO(elasticSearchClient, config.getMetadataQueryMaxSize()));
        this.registerServiceImplementation(IAggregationQueryDAO.class, new AggregationQueryEsDAO(elasticSearchClient));
        this.registerServiceImplementation(IAlarmQueryDAO.class, new AlarmQueryEsDAO(elasticSearchClient));
        this.registerServiceImplementation(ITopNRecordsQueryDAO.class, new TopNRecordsQueryEsDAO(elasticSearchClient));
        this.registerServiceImplementation(ILogQueryDAO.class, new LogQueryEsDAO(elasticSearchClient));
    }

    @Override
    public void start() throws ModuleStartException {
        overrideCoreModuleTTLConfig();

        try {
            elasticSearchClient.connect();

            StorageEsInstaller installer = new StorageEsInstaller(getManager(), config.getIndexShardsNumber(), config.getIndexReplicasNumber(), config.getIndexRefreshInterval());
            installer.install(elasticSearchClient);

            RegisterLockInstaller lockInstaller = new RegisterLockInstaller(elasticSearchClient);
            lockInstaller.install();
        } catch (StorageException | IOException e) {
            throw new ModuleStartException(e.getMessage(), e);
        }
    }

    @Override
    public void notifyAfterCompleted() {
    }

    @Override
    public String[] requiredModules() {
        return new String[] {CoreModule.NAME};
    }

    private void overrideCoreModuleTTLConfig() {
        ConfigService configService = getManager().find(CoreModule.NAME).provider().getService(ConfigService.class);
        configService.getDataTTLConfig().setRecordDataTTL(config.getRecordDataTTL());
        configService.getDataTTLConfig().setMinuteMetricsDataTTL(config.getMinuteMetricsDataTTL());
        configService.getDataTTLConfig().setHourMetricsDataTTL(config.getHourMetricsDataTTL());
        configService.getDataTTLConfig().setDayMetricsDataTTL(config.getDayMetricsDataTTL());
        configService.getDataTTLConfig().setMonthMetricsDataTTL(config.getMonthMetricsDataTTL());
    }
}
