/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.debezium.connector.mongodb.transforms;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonType;
import org.bson.BsonValue;

/**
 * MongoDataConverter handles translating MongoDB strings to Kafka Connect schemas and row data to
 * Kafka Connect records.
 *
 * @author Sairam Polavarapu
 */
public class MongoDataConverter {

  public static final String SCHEMA_NAME_REGEX = "io.debezium.mongodb.regex";

  private final ArrayEncoding arrayEncoding;

  public MongoDataConverter(ArrayEncoding arrayEncoding) {
    this.arrayEncoding = arrayEncoding;
  }

  public Struct convertRecord(
      Entry<String, BsonValue> keyValueForStruct, Schema schema, Struct struct) {
    convertFieldValue(keyValueForStruct, struct, schema);
    return struct;
  }

  @SuppressWarnings("JavaUtilDate")
  public void convertFieldValue(
      Entry<String, BsonValue> keyValueForStruct, Struct struct, Schema schema) {
    Object colValue = null;

    String key = keyValueForStruct.getKey();
    BsonType type = keyValueForStruct.getValue().getBsonType();

    switch (type) {
      case NULL:
        colValue = null;
        break;

      case STRING:
        colValue = keyValueForStruct.getValue().asString().getValue().toString();
        break;

      case OBJECT_ID:
        colValue = keyValueForStruct.getValue().asObjectId().getValue().toString();
        break;

      case DOUBLE:
        colValue = keyValueForStruct.getValue().asDouble().getValue();
        break;

      case BINARY:
        colValue = keyValueForStruct.getValue().asBinary().getData();
        break;

      case INT32:
        colValue = keyValueForStruct.getValue().asInt32().getValue();
        break;

      case INT64:
        colValue = keyValueForStruct.getValue().asInt64().getValue();
        break;

      case BOOLEAN:
        colValue = keyValueForStruct.getValue().asBoolean().getValue();
        break;

      case DATE_TIME:
        colValue = new Date(keyValueForStruct.getValue().asDateTime().getValue());
        break;

      case JAVASCRIPT:
        colValue = keyValueForStruct.getValue().asJavaScript().getCode();
        break;

      case JAVASCRIPT_WITH_SCOPE:
        Struct jsStruct = new Struct(schema.field(key).schema());
        Struct jsScopeStruct = new Struct(schema.field(key).schema().field("scope").schema());
        jsStruct.put("code", keyValueForStruct.getValue().asJavaScriptWithScope().getCode());
        BsonDocument jwsDoc =
            keyValueForStruct.getValue().asJavaScriptWithScope().getScope().asDocument();

        for (Entry<String, BsonValue> jwsDocKey : jwsDoc.entrySet()) {
          convertFieldValue(jwsDocKey, jsScopeStruct, schema.field(key).schema());
        }

        jsStruct.put("scope", jsScopeStruct);
        colValue = jsStruct;
        break;

      case REGULAR_EXPRESSION:
        Struct regexStruct = new Struct(schema.field(key).schema());
        regexStruct.put("regex", keyValueForStruct.getValue().asRegularExpression().getPattern());
        regexStruct.put("options", keyValueForStruct.getValue().asRegularExpression().getOptions());
        colValue = regexStruct;
        break;

      case TIMESTAMP:
        colValue = new Date(1000L * keyValueForStruct.getValue().asTimestamp().getTime());
        break;

      case DECIMAL128:
        colValue = keyValueForStruct.getValue().asDecimal128().getValue().toString();
        break;

      case DOCUMENT:
        Field field = schema.field(key);
        if (field == null) {
          throw new DataException("Failed to find field '" + key + "' in schema " + schema.name());
        }
        Schema documentSchema = field.schema();
        Struct documentStruct = new Struct(documentSchema);
        BsonDocument docs = keyValueForStruct.getValue().asDocument();

        for (Entry<String, BsonValue> doc : docs.entrySet()) {
          convertFieldValue(doc, documentStruct, documentSchema);
        }

        colValue = documentStruct;
        break;

      case ARRAY:
        if (keyValueForStruct.getValue().asArray().isEmpty()) {
          switch (arrayEncoding) {
            case ARRAY:
              colValue = Lists.newArrayList();
              break;
            case DOCUMENT:
              final Schema fieldSchema = schema.field(key).schema();
              colValue = new Struct(fieldSchema);
              break;
          }
        } else {
          switch (arrayEncoding) {
            case ARRAY:
              BsonType valueType = keyValueForStruct.getValue().asArray().get(0).getBsonType();
              List<BsonValue> arrValues = keyValueForStruct.getValue().asArray().getValues();
              List<Object> list = Lists.newArrayList();

              arrValues.forEach(
                  arrValue -> {
                    final Schema valueSchema;
                    if (Arrays.asList(BsonType.ARRAY, BsonType.DOCUMENT).contains(valueType)) {
                      valueSchema = schema.field(key).schema().valueSchema();
                    } else {
                      valueSchema = null;
                    }
                    convertFieldValue(valueSchema, valueType, arrValue, list);
                  });
              colValue = list;
              break;
            case DOCUMENT:
              final BsonArray array = keyValueForStruct.getValue().asArray();
              final Map<String, BsonValue> convertedArray = Maps.newHashMap();
              final Schema arraySchema = schema.field(key).schema();
              final Struct arrayStruct = new Struct(arraySchema);
              for (int i = 0; i < array.size(); i++) {
                convertedArray.put(arrayElementStructName(i), array.get(i));
              }
              convertedArray
                  .entrySet()
                  .forEach(
                      x -> {
                        final Schema elementSchema = schema.field(key).schema();
                        convertFieldValue(x, arrayStruct, elementSchema);
                      });
              colValue = arrayStruct;
              break;
          }
        }
        break;

      default:
        return;
    }
    struct.put(key, keyValueForStruct.getValue().isNull() ? null : colValue);
  }

  // TODO FIX Cyclomatic Complexity is 30 (max allowed is 12). [CyclomaticComplexity]
  @SuppressWarnings({"checkstyle:CyclomaticComplexity", "JavaUtilDate"})
  private void convertFieldValue(
      Schema valueSchema, BsonType valueType, BsonValue arrValue, List<Object> list) {
    if (arrValue.getBsonType() == BsonType.STRING && valueType == BsonType.STRING) {
      String temp = arrValue.asString().getValue();
      list.add(temp);
    } else if (arrValue.getBsonType() == BsonType.JAVASCRIPT && valueType == BsonType.JAVASCRIPT) {
      String temp = arrValue.asJavaScript().getCode();
      list.add(temp);
    } else if (arrValue.getBsonType() == BsonType.OBJECT_ID && valueType == BsonType.OBJECT_ID) {
      String temp = arrValue.asObjectId().getValue().toString();
      list.add(temp);
    } else if (arrValue.getBsonType() == BsonType.DOUBLE && valueType == BsonType.DOUBLE) {
      double temp = arrValue.asDouble().getValue();
      list.add(temp);
    } else if (arrValue.getBsonType() == BsonType.BINARY && valueType == BsonType.BINARY) {
      byte[] temp = arrValue.asBinary().getData();
      list.add(temp);
    } else if (arrValue.getBsonType() == BsonType.INT32 && valueType == BsonType.INT32) {
      int temp = arrValue.asInt32().getValue();
      list.add(temp);
    } else if (arrValue.getBsonType() == BsonType.INT64 && valueType == BsonType.INT64) {
      long temp = arrValue.asInt64().getValue();
      list.add(temp);
    } else if (arrValue.getBsonType() == BsonType.DATE_TIME && valueType == BsonType.DATE_TIME) {
      Date temp = new Date(arrValue.asInt64().getValue());
      list.add(temp);
    } else if (arrValue.getBsonType() == BsonType.DECIMAL128 && valueType == BsonType.DECIMAL128) {
      String temp = arrValue.asDecimal128().getValue().toString();
      list.add(temp);
    } else if (arrValue.getBsonType() == BsonType.TIMESTAMP && valueType == BsonType.TIMESTAMP) {
      Date temp = new Date(1000L * arrValue.asInt32().getValue());
      list.add(temp);
    } else if (arrValue.getBsonType() == BsonType.BOOLEAN && valueType == BsonType.BOOLEAN) {
      boolean temp = arrValue.asBoolean().getValue();
      list.add(temp);
    } else if (arrValue.getBsonType() == BsonType.DOCUMENT && valueType == BsonType.DOCUMENT) {
      Struct struct1 = new Struct(valueSchema);
      for (Entry<String, BsonValue> entry9 : arrValue.asDocument().entrySet()) {
        convertFieldValue(entry9, struct1, valueSchema);
      }
      list.add(struct1);
    } else if (arrValue.getBsonType() == BsonType.ARRAY && valueType == BsonType.ARRAY) {
      List<Object> subList = Lists.newArrayList();
      final Schema subValueSchema;
      if (Arrays.asList(BsonType.ARRAY, BsonType.DOCUMENT)
          .contains(arrValue.asArray().get(0).getBsonType())) {
        subValueSchema = valueSchema.valueSchema();
      } else {
        subValueSchema = null;
      }
      for (BsonValue v : arrValue.asArray()) {
        convertFieldValue(subValueSchema, v.getBsonType(), v, subList);
      }
      list.add(subList);
    }
  }

  protected String arrayElementStructName(int index) {
    return "_" + index;
  }

  public void addFieldSchema(Entry<String, BsonValue> keyValuesForSchema, SchemaBuilder builder) {
    String key = keyValuesForSchema.getKey();
    BsonType type = keyValuesForSchema.getValue().getBsonType();

    switch (type) {
      case NULL:
      case STRING:
      case JAVASCRIPT:
      case OBJECT_ID:
      case DECIMAL128:
        builder.field(key, Schema.OPTIONAL_STRING_SCHEMA);
        break;

      case DOUBLE:
        builder.field(key, Schema.OPTIONAL_FLOAT64_SCHEMA);
        break;

      case BINARY:
        builder.field(key, Schema.OPTIONAL_BYTES_SCHEMA);
        break;

      case INT32:
        builder.field(key, Schema.OPTIONAL_INT32_SCHEMA);
        break;

      case INT64:
        builder.field(key, Schema.OPTIONAL_INT64_SCHEMA);
        break;

      case DATE_TIME:
      case TIMESTAMP:
        builder.field(key, org.apache.kafka.connect.data.Timestamp.builder().optional().build());
        break;

      case BOOLEAN:
        builder.field(key, Schema.OPTIONAL_BOOLEAN_SCHEMA);
        break;

      case JAVASCRIPT_WITH_SCOPE:
        SchemaBuilder jsWithScope = SchemaBuilder.struct().name(builder.name() + "." + key);
        jsWithScope.field("code", Schema.OPTIONAL_STRING_SCHEMA);
        SchemaBuilder scope = SchemaBuilder.struct().name(jsWithScope.name() + ".scope").optional();
        BsonDocument jwsDocument =
            keyValuesForSchema.getValue().asJavaScriptWithScope().getScope().asDocument();

        for (Entry<String, BsonValue> jwsDocumentKey : jwsDocument.entrySet()) {
          addFieldSchema(jwsDocumentKey, scope);
        }

        Schema scopeBuild = scope.build();
        jsWithScope.field("scope", scopeBuild).build();
        builder.field(key, jsWithScope);
        break;

      case REGULAR_EXPRESSION:
        SchemaBuilder regexwop = SchemaBuilder.struct().name(SCHEMA_NAME_REGEX).optional();
        regexwop.field("regex", Schema.OPTIONAL_STRING_SCHEMA);
        regexwop.field("options", Schema.OPTIONAL_STRING_SCHEMA);
        builder.field(key, regexwop.build());
        break;

      case DOCUMENT:
        SchemaBuilder builderDoc =
            SchemaBuilder.struct().name(builder.name() + "." + key).optional();
        BsonDocument docs = keyValuesForSchema.getValue().asDocument();

        for (Entry<String, BsonValue> doc : docs.entrySet()) {
          addFieldSchema(doc, builderDoc);
        }
        builder.field(key, builderDoc.build());
        break;

      case ARRAY:
        if (keyValuesForSchema.getValue().asArray().isEmpty()) {
          switch (arrayEncoding) {
            case ARRAY:
              builder.field(
                  key, SchemaBuilder.array(Schema.OPTIONAL_STRING_SCHEMA).optional().build());
              break;
            case DOCUMENT:
              builder.field(
                  key, SchemaBuilder.struct().name(builder.name() + "." + key).optional().build());
              break;
          }
        } else {
          switch (arrayEncoding) {
            case ARRAY:
              BsonArray value = keyValuesForSchema.getValue().asArray();
              BsonType valueType = value.get(0).getBsonType();
              testType(builder, key, keyValuesForSchema.getValue(), valueType);
              builder.field(
                  key,
                  SchemaBuilder.array(subSchema(builder, key, valueType, value))
                      .optional()
                      .build());
              break;
            case DOCUMENT:
              final BsonArray array = keyValuesForSchema.getValue().asArray();
              final SchemaBuilder arrayStructBuilder =
                  SchemaBuilder.struct().name(builder.name() + "." + key).optional();
              final Map<String, BsonValue> convertedArray = Maps.newHashMap();
              for (int i = 0; i < array.size(); i++) {
                convertedArray.put(arrayElementStructName(i), array.get(i));
              }
              convertedArray.entrySet().forEach(x -> addFieldSchema(x, arrayStructBuilder));
              builder.field(key, arrayStructBuilder.build());
              break;
          }
        }
        break;
      default:
        break;
    }
  }

  private Schema subSchema(SchemaBuilder builder, String key, BsonType valueType, BsonValue value) {
    switch (valueType) {
      case NULL:
      case STRING:
      case JAVASCRIPT:
      case OBJECT_ID:
      case DECIMAL128:
        return Schema.OPTIONAL_STRING_SCHEMA;
      case DOUBLE:
        return Schema.OPTIONAL_FLOAT64_SCHEMA;
      case BINARY:
        return Schema.OPTIONAL_BYTES_SCHEMA;
      case INT32:
        return Schema.OPTIONAL_INT32_SCHEMA;
      case INT64:
        return Schema.OPTIONAL_INT64_SCHEMA;
      case TIMESTAMP:
      case DATE_TIME:
        return org.apache.kafka.connect.data.Timestamp.builder().optional().build();
      case BOOLEAN:
        return Schema.OPTIONAL_BOOLEAN_SCHEMA;
      case DOCUMENT:
        final SchemaBuilder documentSchemaBuilder =
            SchemaBuilder.struct().name(builder.name() + "." + key).optional();
        final Map<String, BsonType> union = Maps.newHashMap();
        if (value.isArray()) {
          value
              .asArray()
              .forEach(f -> subSchema(documentSchemaBuilder, union, f.asDocument(), true));
          if (documentSchemaBuilder.fields().isEmpty()) {
            value
                .asArray()
                .forEach(f -> subSchema(documentSchemaBuilder, union, f.asDocument(), false));
          }
        } else {
          subSchema(documentSchemaBuilder, union, value.asDocument(), false);
        }
        return documentSchemaBuilder.build();
      case ARRAY:
        BsonType subValueType = value.asArray().get(0).asArray().get(0).getBsonType();
        return SchemaBuilder.array(subSchema(builder, key, subValueType, value.asArray().get(0)))
            .optional()
            .build();
      default:
        throw new IllegalArgumentException(
            "The value type '" + valueType + " is not yet supported inside for a subSchema.");
    }
  }

  private void subSchema(
      SchemaBuilder documentSchemaBuilder,
      Map<String, BsonType> union,
      BsonDocument arrayDocs,
      boolean emptyChecker) {
    for (Entry<String, BsonValue> arrayDoc : arrayDocs.entrySet()) {
      final String key = arrayDoc.getKey();
      if (emptyChecker
          && ((arrayDoc.getValue() instanceof BsonDocument
                  && ((BsonDocument) arrayDoc.getValue()).isEmpty())
              || (arrayDoc.getValue() instanceof BsonArray
                  && ((BsonArray) arrayDoc.getValue()).isEmpty()))) {
        continue;
      }
      final BsonType prevType = union.putIfAbsent(key, arrayDoc.getValue().getBsonType());
      if (prevType == null) {
        addFieldSchema(arrayDoc, documentSchemaBuilder);
      } else {
        testArrayElementType(documentSchemaBuilder, arrayDoc, union);
      }
    }
  }

  private void testType(SchemaBuilder builder, String key, BsonValue value, BsonType valueType) {
    if (valueType == BsonType.DOCUMENT) {
      final Map<String, BsonType> union = Maps.newHashMap();
      for (BsonValue element : value.asArray()) {
        final BsonDocument arrayDocs = element.asDocument();
        for (Entry<String, BsonValue> arrayDoc : arrayDocs.entrySet()) {
          testArrayElementType(builder, arrayDoc, union);
        }
      }
    } else if (valueType == BsonType.ARRAY) {
      for (BsonValue element : value.asArray()) {
        BsonType subValueType = element.asArray().get(0).getBsonType();
        testType(builder, key, element, subValueType);
      }
    } else {
      for (BsonValue element : value.asArray()) {
        if (element.getBsonType() != valueType) {
          throw new RuntimeException(
              "Field "
                  + key
                  + " of schema "
                  + builder.name()
                  + " is not a homogenous array.\n"
                  + "Check option 'struct' of parameter 'array.encoding'");
        }
      }
    }
  }

  private void testArrayElementType(
      SchemaBuilder builder, Entry<String, BsonValue> arrayDoc, Map<String, BsonType> union) {
    final String docKey = (String) arrayDoc.getKey();
    final BsonType currentType = arrayDoc.getValue().getBsonType();
    final BsonType prevType = union.putIfAbsent(docKey, currentType);

    if (prevType != null) {
      if ((prevType == BsonType.NULL || currentType == BsonType.NULL)
          && !Objects.equals(prevType, currentType)) {
        // set non-null type as real schema
        if (prevType == BsonType.NULL) {
          union.put(docKey, currentType);
        }
      } else if (!Objects.equals(prevType, currentType)) {
        throw new RuntimeException(
            "Field "
                + docKey
                + " of schema "
                + builder.name()
                + " is not the same type for all documents in the array.\n"
                + "Check option 'struct' of parameter 'array.encoding'");
      }
    }
  }
}
