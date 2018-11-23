/**
 * Copyright 2014 Flipkart Internet Pvt. Ltd.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.flipkart.foxtrot.core.querystore.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.foxtrot.common.Table;
import com.flipkart.foxtrot.core.table.impl.ElasticsearchTestUtils;
import com.flipkart.foxtrot.core.table.impl.TableMapStore;
import com.flipkart.foxtrot.core.util.ElasticsearchQueryUtils;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequest;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.get.MultiGetItemResponse;
import org.elasticsearch.action.get.MultiGetResponse;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.common.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.spy;


/**
 * Created by rishabh.goyal on 02/05/14.
 */
public class TableMapStoreTest {
    public static final String TEST_TABLE = "test-table";
    public static final String TABLE_META_INDEX = "table-meta";
    public static final String TABLE_META_TYPE = "table-meta";
    private ObjectMapper mapper = new ObjectMapper();
    private ElasticsearchConnection elasticsearchConnection;
    private TableMapStore tableMapStore;

    @Before
    public void setUp() throws Exception {
        mapper = spy(mapper);

        elasticsearchConnection = ElasticsearchTestUtils.getConnection();
        ElasticsearchUtils.initializeMappings(elasticsearchConnection.getClient());

        //Create index for table meta. Not created automatically
        Settings indexSettings = Settings.builder()
                .put("number_of_replicas", 0)
                .build();

        IndicesExistsRequest indicesExistsRequest = new IndicesExistsRequest().indices(TableMapStore.TABLE_META_INDEX);
        IndicesExistsResponse indicesExistsResponse = elasticsearchConnection.getClient()
                .admin()
                .indices()
                .exists(indicesExistsRequest)
                .actionGet();
        if(!indicesExistsResponse.isExists()) {
            CreateIndexRequest createRequest = new CreateIndexRequest(TableMapStore.TABLE_META_INDEX).settings(
                    indexSettings);
            elasticsearchConnection.getClient()
                    .admin()
                    .indices()
                    .create(createRequest)
                    .actionGet();
        }
        elasticsearchConnection.getClient()
                .admin()
                .cluster()
                .prepareHealth()
                .setWaitForGreenStatus()
                .execute()
                .actionGet();
        TableMapStore.Factory factory = new TableMapStore.Factory(elasticsearchConnection);
        tableMapStore = factory.newMapStore(null, null);
    }

    @After
    public void tearDown() throws Exception {
        try {
            DeleteIndexRequest deleteIndexRequest = new DeleteIndexRequest("*");
            elasticsearchConnection.getClient()
                    .admin()
                    .indices()
                    .delete(deleteIndexRequest);
        } catch (Exception e) {
            //Do Nothing
        }
        elasticsearchConnection.stop();
    }


    @Test
    public void testStore() throws Exception {
        Table table = new Table();
        table.setName(TEST_TABLE);
        table.setTtl(30);
        tableMapStore.store(table.getName(), table);

        GetResponse response = elasticsearchConnection.getClient()
                .prepareGet()
                .setIndex(TABLE_META_INDEX)
                .setType(TABLE_META_TYPE)
                .setId(table.getName())
                .execute()
                .actionGet();
        compareTables(table, mapper.readValue(response.getSourceAsBytes(), Table.class));
    }

    @Test(expected = RuntimeException.class)
    public void testStoreNullKey() throws Exception {
        Table table = new Table();
        table.setName(TEST_TABLE);
        table.setTtl(30);
        tableMapStore.store(null, table);
    }

    @Test(expected = RuntimeException.class)
    public void testStoreNullTable() throws Exception {
        Table table = new Table();
        table.setName(TEST_TABLE);
        table.setTtl(30);
        tableMapStore.store(table.getName(), null);
    }

    @Test(expected = RuntimeException.class)
    public void testStoreNullTableName() throws Exception {
        Table table = new Table();
        table.setName(null);
        table.setTtl(30);
        tableMapStore.store(TEST_TABLE, table);
    }

    @Test
    public void testStoreAll() throws Exception {
        Map<String, Table> tables = Maps.newHashMap();
        for(int i = 0; i < 10; i++) {
            Table table = new Table();
            table.setName(UUID.randomUUID()
                                  .toString());
            table.setTtl(20);
            tables.put(table.getName(), table);
        }
        tableMapStore.storeAll(tables);

        MultiGetResponse response = elasticsearchConnection.getClient()
                .prepareMultiGet()
                .add(TABLE_META_INDEX, TABLE_META_TYPE, tables.keySet())
                .execute()
                .actionGet();
        Map<String, Table> responseTables = Maps.newHashMap();
        for(MultiGetItemResponse multiGetItemResponse : response) {
            Table table = mapper.readValue(multiGetItemResponse.getResponse()
                                                   .getSourceAsString(), Table.class);
            responseTables.put(table.getName(), table);
        }
        for(String name : tables.keySet()) {
            compareTables(tables.get(name), responseTables.get(name));
        }
    }

    @Test(expected = RuntimeException.class)
    public void testStoreAllNull() throws Exception {
        tableMapStore.storeAll(null);
    }

    @Test(expected = RuntimeException.class)
    public void testStoreAllNullTableKey() throws Exception {
        Map<String, Table> tables = Maps.newHashMap();
        for(int i = 0; i < 10; i++) {
            Table table = new Table();
            table.setName(UUID.randomUUID()
                                  .toString());
            table.setTtl(20);
            tables.put(null, table);
        }
        tableMapStore.storeAll(tables);
    }

    @Test(expected = RuntimeException.class)
    public void testStoreAllNullTableValue() throws Exception {
        Map<String, Table> tables = Maps.newHashMap();
        for(int i = 0; i < 10; i++) {
            tables.put(UUID.randomUUID()
                               .toString(), null);
        }
        tableMapStore.storeAll(tables);
    }

    @Test(expected = RuntimeException.class)
    public void testStoreAllNullTableKeyValue() throws Exception {
        Map<String, Table> tables = Maps.newHashMap();
        for(int i = 0; i < 10; i++) {
            tables.put(null, null);
        }
        tableMapStore.storeAll(tables);
    }

    @Test(expected = RuntimeException.class)
    public void testStoreAllSomeNullKeys() throws Exception {
        Map<String, Table> tables = Maps.newHashMap();
        for(int i = 0; i < 10; i++) {
            Table table = new Table();
            table.setName(UUID.randomUUID()
                                  .toString());
            table.setTtl(20);
            tables.put(table.getName(), table);
        }

        Table table = new Table();
        table.setName(UUID.randomUUID()
                              .toString());
        table.setTtl(20);
        tables.put(null, table);
        tableMapStore.storeAll(tables);
    }

    @Test(expected = RuntimeException.class)
    public void testStoreAllSomeNullValues() throws Exception {
        Map<String, Table> tables = Maps.newHashMap();
        for(int i = 0; i < 10; i++) {
            Table table = new Table();
            table.setName(UUID.randomUUID()
                                  .toString());
            table.setTtl(20);
            tables.put(table.getName(), table);
        }

        Table table = new Table();
        table.setName(UUID.randomUUID()
                              .toString());
        table.setTtl(20);
        tables.put(table.getName(), null);
        tableMapStore.storeAll(tables);
    }

    @Test(expected = RuntimeException.class)
    public void testStoreAllSomeNullKeyValues() throws Exception {
        Map<String, Table> tables = Maps.newHashMap();
        for(int i = 0; i < 10; i++) {
            Table table = new Table();
            table.setName(UUID.randomUUID()
                                  .toString());
            table.setTtl(20);
            tables.put(table.getName(), table);
        }
        tables.put(null, null);
        tableMapStore.storeAll(tables);
    }


    @Test
    public void testDelete() throws Exception {
        Table table = new Table();
        table.setName(TEST_TABLE);
        table.setTtl(30);
        tableMapStore.store(table.getName(), table);
        GetResponse response = elasticsearchConnection.getClient()
                .prepareGet()
                .setIndex(TABLE_META_INDEX)
                .setType(TABLE_META_TYPE)
                .setId(table.getName())
                .execute()
                .actionGet();
        assertTrue(response.isExists());

        tableMapStore.delete(table.getName());
        response = elasticsearchConnection.getClient()
                .prepareGet()
                .setIndex(TABLE_META_INDEX)
                .setType(TABLE_META_TYPE)
                .setId(table.getName())
                .execute()
                .actionGet();
        assertFalse(response.isExists());
    }

    @Test(expected = RuntimeException.class)
    public void testDeleteNullKey() throws Exception {
        tableMapStore.delete(null);
    }

    //TODO Why it didn't throw any exception ?
    @Test
    public void testDeleteMissingKey() throws Exception {
        tableMapStore.delete("HELLO");
    }


    @Test
    public void testDeleteAll() throws Exception {
        Map<String, Table> tables = Maps.newHashMap();
        for(int i = 0; i < 10; i++) {
            Table table = new Table();
            table.setName(UUID.randomUUID()
                                  .toString());
            table.setTtl(20);
            tables.put(table.getName(), table);
        }
        tableMapStore.storeAll(tables);
        for(String name : tables.keySet()) {
            GetResponse response = elasticsearchConnection.getClient()
                    .prepareGet()
                    .setIndex(TABLE_META_INDEX)
                    .setType(TABLE_META_TYPE)
                    .setId(name)
                    .execute()
                    .actionGet();
            assertTrue(response.isExists());
        }

        tableMapStore.deleteAll(tables.keySet());
        for(String name : tables.keySet()) {
            GetResponse response = elasticsearchConnection.getClient()
                    .prepareGet()
                    .setIndex(TABLE_META_INDEX)
                    .setType(TABLE_META_TYPE)
                    .setId(name)
                    .execute()
                    .actionGet();
            assertFalse(response.isExists());
        }

    }

    @Test(expected = RuntimeException.class)
    public void testDeleteAllNull() throws Exception {
        tableMapStore.deleteAll(null);
    }

    @Test(expected = RuntimeException.class)
    public void testDeleteAllNullKeys() throws Exception {
        List<String> keys = new ArrayList<String>();
        keys.add(null);
        keys.add(null);
        tableMapStore.deleteAll(keys);
    }


    @Test
    public void testLoad() throws Exception {
        Table table = new Table();
        table.setName(TEST_TABLE);
        table.setTtl(30);
        Map<String, Object> sourceMap = ElasticsearchQueryUtils.getSourceMap(table, Table.class);
        elasticsearchConnection.getClient()
                .prepareIndex()
                .setIndex(TABLE_META_INDEX)
                .setType(TABLE_META_TYPE)
                .setSource(sourceMap)
                .setId(table.getName())
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .execute()
                .actionGet();

        Table responseTable = tableMapStore.load(table.getName());
        compareTables(table, responseTable);
    }

    @Test
    public void testLoadMissingKey() throws Exception {
        assertNull(tableMapStore.load(UUID.randomUUID()
                                              .toString()));
    }

    //TODO Why not an error ?
    @Test
    public void testLoadNullKey() throws Exception {
        assertNull(tableMapStore.load(UUID.randomUUID()
                                              .toString()));
    }

    // Exception Caught because of Runtime. Not an IOException
    @Test(expected = RuntimeException.class)
    public void testLoadKeyWithWrongJson() throws Exception {
        elasticsearchConnection.getClient()
                .prepareIndex()
                .setIndex(TABLE_META_INDEX)
                .setType(TABLE_META_TYPE)
                .setSource("{ \"test\" : \"test\"}")
                .setId(TEST_TABLE)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .execute()
                .actionGet();
        tableMapStore.load(TEST_TABLE);
    }


    @Test
    public void testLoadAll() throws Exception {
        Map<String, Table> tables = Maps.newHashMap();
        for(int i = 0; i < 10; i++) {
            Table table = new Table();
            table.setName(UUID.randomUUID()
                                  .toString());
            table.setTtl(20);
            tables.put(table.getName(), table);
            Map<String, Object> sourceMap = ElasticsearchQueryUtils.getSourceMap(table, Table.class);
            elasticsearchConnection.getClient()
                    .prepareIndex()
                    .setIndex(TABLE_META_INDEX)
                    .setType(TABLE_META_TYPE)
                    .setSource(sourceMap)
                    .setId(table.getName())
                    .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                    .execute()
                    .actionGet();
        }

        Set<String> names = ImmutableSet.copyOf(Iterables.limit(tables.keySet(), 5));
        Map<String, Table> responseTables = tableMapStore.loadAll(names);
        assertEquals(names.size(), responseTables.size());
        for(String name : names) {
            compareTables(tables.get(name), responseTables.get(name));
        }
    }

    @Test(expected = NullPointerException.class)
    public void testLoadAllNull() throws Exception {
        tableMapStore.loadAll(null);
    }

    @Test(expected = RuntimeException.class)
    public void testLoadAllKeyWithWrongJson() throws Exception {
        elasticsearchConnection.getClient()
                .prepareIndex()
                .setIndex(TABLE_META_INDEX)
                .setType(TABLE_META_TYPE)
                .setSource("{ \"test\" : \"test\"}")
                .setId(TEST_TABLE)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .execute()
                .actionGet();
        tableMapStore.loadAll(Arrays.asList(TEST_TABLE));
    }


    @Test
    public void testLoadAllKeys() throws Exception {
        Map<String, Table> tables = Maps.newHashMap();
        for(int i = 0; i < 10; i++) {
            Table table = new Table();
            table.setName(UUID.randomUUID()
                                  .toString());
            table.setTtl(20);
            tables.put(table.getName(), table);
            Map<String, Object> sourceMap = ElasticsearchQueryUtils.getSourceMap(table, Table.class);
            elasticsearchConnection.getClient()
                    .prepareIndex()
                    .setIndex(TABLE_META_INDEX)
                    .setType(TABLE_META_TYPE)
                    .setSource(sourceMap)
                    .setId(table.getName())
                    .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                    .execute()
                    .actionGet();
        }

        Set<String> responseKeys = tableMapStore.loadAllKeys();
        for(String name : tables.keySet()) {
            assertTrue(responseKeys.contains(name));
        }
    }

    private void compareTables(Table expected, Table actual) {
        assertNotNull(actual);
        assertEquals(expected.getName(), actual.getName());
        assertEquals(expected.getTtl(), actual.getTtl());
    }
}
