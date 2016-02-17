/**
 * Copyright 2014 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.confluent.kafka.schemaregistry.client;

import io.confluent.kafka.schemaregistry.client.rest.RestService;
import io.confluent.kafka.schemaregistry.client.rest.entities.Config;
import io.confluent.kafka.schemaregistry.client.rest.entities.SchemaString;
import io.confluent.kafka.schemaregistry.client.rest.entities.requests.ConfigUpdateRequest;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import org.apache.avro.Schema;

import java.io.IOException;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class CachedSchemaRegistryClient implements SchemaRegistryClient {

  private final RestService restService;
  private final int identityMapCapacity;
  private final Map<String, Map<Schema, Integer>> schemaCache;
  private final Map<Integer, Schema> idCache;
  private final Map<String, Map<Schema, Integer>> versionCache;
  private final Map<String, Map<Integer, Schema>> nameVersionCache;

  public CachedSchemaRegistryClient(String baseUrl, int identityMapCapacity) {
    this(new RestService(baseUrl), identityMapCapacity);
  }

  public CachedSchemaRegistryClient(List<String> baseUrls, int identityMapCapacity) {
    this(new RestService(baseUrls), identityMapCapacity);
  }

  public CachedSchemaRegistryClient(RestService restService, int identityMapCapacity) {
    this.identityMapCapacity = identityMapCapacity;
    this.schemaCache = new HashMap<String, Map<Schema, Integer>>();
    this.idCache = new HashMap<Integer, Schema>();
    this.versionCache = new HashMap<String, Map<Schema, Integer>>();
    this.nameVersionCache = new HashMap<>();
    this.restService = restService;
  }

  private int registerAndGetId(String subject, Schema schema)
      throws IOException, RestClientException {
    return restService.registerSchema(schema.toString(), subject);
  }

  private Schema getSchemaByIdFromRegistry(int id) throws IOException, RestClientException {
    SchemaString restSchema = restService.getId(id);
    return new Schema.Parser().parse(restSchema.getSchemaString());
  }

  private int getVersionFromRegistry(String subject, Schema schema)
      throws IOException, RestClientException{
    io.confluent.kafka.schemaregistry.client.rest.entities.Schema response =
        restService.lookUpSubjectVersion(schema.toString(), subject);
    return response.getVersion();
  }

  private Schema getSchemaByNameAndVersionFromRegistry(String subject, int version) throws IOException, RestClientException{
    io.confluent.kafka.schemaregistry.client.rest.entities.Schema restSchema =
            restService.getVersion(subject, version);

    return new Schema.Parser().parse(restSchema.getSchema());
  }

  @Override
  public synchronized int register(String subject, Schema schema)
      throws IOException, RestClientException {
    Map<Schema, Integer> schemaIdMap;
    if (schemaCache.containsKey(subject)) {
      schemaIdMap = schemaCache.get(subject);
    } else {
      schemaIdMap = new IdentityHashMap<Schema, Integer>();
      schemaCache.put(subject, schemaIdMap);
    }

    if (schemaIdMap.containsKey(schema)) {
      return schemaIdMap.get(schema);
    } else {
      if (schemaIdMap.size() >= identityMapCapacity) {
        throw new IllegalStateException("Too many schema objects created for " + subject + "!");
      }
      int id = registerAndGetId(subject, schema);
      schemaIdMap.put(schema, id);
      return id;
    }
  }

  @Override
  public synchronized Schema getByID(int id) throws IOException, RestClientException {
    if (idCache.containsKey(id)) {
      return idCache.get(id);
    } else {
      Schema schema = getSchemaByIdFromRegistry(id);
      idCache.put(id, schema);
      return schema;
    }
  }

  @Override
  public synchronized SchemaMetadata getLatestSchemaMetadata(String subject)
      throws IOException, RestClientException {
    io.confluent.kafka.schemaregistry.client.rest.entities.Schema response
     = restService.getLatestVersion(subject);
    int id = response.getId();
    int version = response.getVersion();
    String schema = response.getSchema();
    return new SchemaMetadata(id, version, schema);
  }

  @Override
  public synchronized int getVersion(String subject, Schema schema)
      throws IOException, RestClientException{
    Map<Schema, Integer> schemaVersionMap;
    if (versionCache.containsKey(subject)) {
      schemaVersionMap = versionCache.get(subject);
    } else {
      schemaVersionMap = new IdentityHashMap<Schema, Integer>();
      versionCache.put(subject, schemaVersionMap);
    }

    if (schemaVersionMap.containsKey(schema)) {
      return schemaVersionMap.get(schema);
    }  else {
      if (schemaVersionMap.size() >= identityMapCapacity) {
        throw new IllegalStateException("Too many schema objects created for " + subject + "!");
      }
      int version = getVersionFromRegistry(subject, schema);
      schemaVersionMap.put(schema, version);
      return version;
    }
  }
  
  @Override
  public boolean testCompatibility(String subject, Schema schema) throws IOException, RestClientException {
    return restService.testCompatibility(schema.toString(), subject, "latest");
  }

  @Override
  public String updateCompatibility(String subject, String compatibility) throws IOException, RestClientException {
    ConfigUpdateRequest response = restService.updateCompatibility(compatibility, subject);
    return response.getCompatibilityLevel();
  }

  @Override
  public String getCompatibility(String subject) throws IOException, RestClientException {
    Config response = restService.getConfig(subject);
    return response.getCompatibilityLevel();
  }

  @Override
  public Schema getBySubjectAndVersion(String subject, int version) throws IOException, RestClientException {

    final Map<Integer, Schema> versionSchemaMap;
    if (this.nameVersionCache.containsKey(subject)) {
      versionSchemaMap = this.nameVersionCache.get(subject);
    } else {
      versionSchemaMap = new HashMap<>();
      this.nameVersionCache.put(subject, versionSchemaMap);
    }

    final Schema schema;
    if (versionSchemaMap.containsKey(version)) {
      schema = versionSchemaMap.get(version);
    } else {
      schema = getSchemaByNameAndVersionFromRegistry(subject, version);
      versionSchemaMap.put(version, schema);
    }

    return schema;
  }


}
