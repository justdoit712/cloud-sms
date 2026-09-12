# 03 · ES 8 新 Java Client 与 beacon-search 改造（学习笔记）

> 面向：把 beacon-search 从 `RestHighLevelClient`（ES 7.6.2）迁移到 ES 8 官方新客户端 `co.elastic.clients:elasticsearch-java`。
> 状态：✅ 完成（纯学习文档，无代码改动；API 映射基于 `ElasticsearchServiceImpl.java` 逐方法对照）。

---

## 1. 背景：为什么必须换客户端

| 事实 | 说明 |
| --- | --- |
| `RestHighLevelClient`（HLRC）在 ES 7.16 被标记 deprecated | 官方宣布 8.0 起移除 |
| ES 8.x 只支持官方 **Java API Client**（`co.elastic.clients:elasticsearch-java`） | 新一代客户端，强类型 + Lambda 流式构建 |
| 旧 HLRC 基于 `org.elasticsearch` 旧包名 | 新客户端包名 `co.elastic.clients.*`，API 全部重写 |
| 本项目现状 | beacon-search 全部 ES 操作集中在 `ElasticsearchServiceImpl`（index/exists/update/search/聚合），改造面可控 |

> 低层 `RestClient`（Low-Level，HTTP 客户端）**保留不变**——新客户端就是架在它之上的强类型封装。

## 2. 新客户端核心概念

### 2.1 依赖

```xml
<dependency>
    <groupId>co.elastic.clients</groupId>
    <artifactId>elasticsearch-java</artifactId>
    <!-- 版本由父 POM 管理：8.19.21 -->
</dependency>
<!-- elasticsearch-rest-client 由其传递引入；Jackson 映射由 spring-boot 提供 -->
```

### 2.2 客户端构建

```java
RestClient restClient = RestClient.builder(
        new HttpHost(host, port, "http"))
        .setHttpClientConfigCallback(hc -> hc
                .setDefaultCredentialsProvider(credentials)  // 认证
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(...)
                        .setSocketTimeout(...).build()))
        .build();

ElasticsearchClient client = new ElasticsearchClient(
        new ElasticsearchTransport(
                new JacksonJsonpMapper(),   // 复用 Jackson，与项目一致
                restClient));
```

要点：
- `JacksonJsonpMapper()` 无参构造默认用**自己 new 的 ObjectMapper**；要复用 Boot 的 ObjectMapper（含 JavaTimeModule、日期格式定制）就传参 `new JacksonJsonpMapper(bootObjectMapper)`。
- 客户端是线程安全的单例 Bean，替换原 `RestHighLevelClient` Bean 即可。

### 2.3 标志性风格：Lambda 流式 Builder

新旧最大差别不是"换个类名"，而是 **DSL 风格**：

```java
// 旧：命令式逐行 set
BoolQueryBuilder bool = QueryBuilders.boolQuery();
bool.must(QueryBuilders.matchQuery("text", content));

// 新：Lambda 流式，一层括号一个嵌套层级
client.search(s -> s.index("sms_submit_log_2026").query(q -> q
        .bool(b -> b.must(m -> m.match(mm -> mm.field("text").query(content))))
), Map.class);
```

阅读技巧：**从外到内读括号** = 从查询根到叶子条件；`Map.class` 是结果反序列化目标类型。

## 3. 逐方法 API 迁移映射（对照现有代码）

### 3.1 `index`（写入文档）—— 对应 `ElasticsearchServiceImpl.index`

```java
// 旧
IndexRequest request = new IndexRequest(index).id(id).source(json, XContentType.JSON);
IndexResponse resp = restHighLevelClient.index(request, RequestOptions.DEFAULT);
String result = resp.getResult().getLowercase();          // "created"/"updated"

// 新（withJson 直接吃原始 JSON 字符串，走 JsonpMapper 解析）
IndexResponse resp = client.index(ir -> ir.index(index).id(id)
        .withJson(new StringReader(json)));
String result = resp.result().jsonValue();                // "created"/"updated"
```

- `withJson(...)` 接收 `Reader/InputStream`，直接把 JSON 文档灌进去——本项目监听器里已是 JSON 字符串，迁移成本最低。
- ⚠️ 旧代码 `!CREATED.equals(result) → throw` 的幂等判定是**已知业务缺陷**（重投消息会 updated → 抛异常 → 无限重试，分析报告 🔴#20）。**按约定本轮只翻译不改逻辑**，但映射时保留该行为，缺陷留到升级后处理阶段修复。

### 3.2 `exists`（文档存在性）—— 对应 `exists`

```java
// 旧
boolean exists = restHighLevelClient.exists(new GetRequest(index).id(id), RequestOptions.DEFAULT);

// 新：exists() 返回 BooleanResponse，取 .value()
boolean exists = client.exists(e -> e.index(index).id(id)).value();
```

### 3.3 `update`（局部更新）—— 对应 `update`

```java
// 旧
UpdateRequest request = new UpdateRequest(index, id).doc(doc);   // doc 是 Map
UpdateResponse resp = restHighLevelClient.update(request, RequestOptions.DEFAULT);
Result result = resp.getResult();                               // UPDATED/NOOP/...

// 新
UpdateResponse<Map> resp = client.update(u -> u.index(index).id(id)
        .doc(doc),            // JacksonJsonpMapper 会把 Map 序列化为 doc 体
        Map.class);           // 返回体映射为 Map
String result = resp.result().jsonValue();                     // "updated"/"noop"
```

- 新客户端 `update` 的响应是泛型 `UpdateResponse<T>`，`T` 由第二个参数决定；doc 可以是 Map（Jackson 自动序列化），本项目 `{reportState: x}` 的 Map 直接可用。
- 旧代码 `Result.UPDATED/NOOP` 枚举 → 新代码比较字符串 `"updated"` / `"noop"`。

### 3.4 `findSmsByParameters`（条件分页查询）—— 对应 `search`

旧代码结构：BoolQuery（match/prefix/range/terms）+ from/size + highlighter。

```java
// 新：一个 Lambda 里完成全部组装
SearchResponse<Map> resp = client.search(sr -> sr
        .index(SearchUtils.getCurrYearIndex())
        .query(q -> q.bool(b -> {
            if (hasText(content)) {
                b.must(m -> m.match(mm -> mm.field("text").query(content)));
            }
            if (hasText(mobile)) {
                b.must(m -> m.prefix(p -> p.field("mobile").value(mobile)));
            }
            if (startTime != null) {
                b.must(m -> m.range(r -> r.field("sendTimeMillis")
                        .gte(JsonData.of(startTime))));
            }
            if (stopTime != null) {
                b.must(m -> m.range(r -> r.field("sendTimeMillis")
                        .lte(JsonData.of(stopTime))));
            }
            if (clientIds != null) {
                b.must(m -> m.terms(t -> t.field("clientId")
                        .terms(tt -> tt.value(clientIds))));
            }
            return b;
        }))
        .from(from).size(size)
        .highlight(h -> h.fields("text", hf -> hf
                .preTags("<span style='color: red'>")
                .postTags("</span>")
                .fragmentSize(100)))
, Map.class);
```

结果解析差异（**重点**）：

| 旧 | 新 |
| --- | --- |
| `resp.getHits().getTotalHits().value` | `resp.hits().total().value()` |
| `hit.getSourceAsMap()` | `hit.source()`（已经是 Map，因为请求时声明了 Map.class） |
| `hit.getHighlightFields().get("text").getFragments()[0]` | `hit.highlight().get("text")` 返回 `List<String>`，取 `get(0)` |

- `JsonData.of(...)`：数字/布尔等非 JSON 类型用 `JsonData` 包装（等价旧 API 直接传 long）。
- `terms().value(list)`：value 接受 `List<FieldValue>` 或普通值列表（Long 列表可直接传，mapper 处理）。

### 3.5 `countSmsState`（terms 聚合）—— 对应 `search` + `aggregations`

```java
// 旧
TermsAggregationBuilder stateAgg = AggregationBuilders.terms("state_group").field("reportState");
Terms terms = resp.getAggregations().get("state_group");
for (Terms.Bucket bucket : terms.getBuckets()) { key/bucket.getDocCount() }

// 新
SearchResponse<Map> resp = client.search(sr -> sr
        .index(SearchUtils.getCurrYearIndex())
        .query(q -> ...)              // 同样的 bool 条件
        .size(0)                      // 不取明细，突破 10000 限制的技巧保留
        .aggregations("state_group", a -> a
                .terms(t -> t.field("reportState")))
, Map.class);

// 解析：新客户端聚合结果在 resp.aggregations()（Map<String, Aggregate>）
Aggregate agg = resp.aggregations().get("state_group");
// 强类型判断
if (agg.isSterms()) {
    for (StringTermsBucket bucket : agg.sterms().buckets().array()) {
        int state = Integer.parseInt(bucket.key().stringValue());
        long count = bucket.docCount();
    }
}
```

- 新客户端聚合结果用 **`isSterms()`/`sterms()` 判别式**（"short terms"），这是新客户端最绕的一处，写一次就记住。
- `bucket.key()` 是 `FieldValue`，`stringValue()`/`longValue()` 取值。

## 4. 配置类改造（RestHighLevelClientConfig → ElasticsearchClientConfig）

| 旧 | 新 |
| --- | --- |
| `RestHighLevelClientBuilder(RestClient.builder(hosts))` | `ElasticsearchTransport + ElasticsearchClient`（见 2.2） |
| `elasticsearch.hostAndPorts` 逗号分隔 host:port | 配置不变，解析逻辑复用 |
| BasicAuth `CredentialsProvider` | 同样用 `setHttpClientConfigCallback` 注入 |
| 无超时/连接池（旧配置缺失，分析报告 🟡） | **可顺手补** `setConnectTimeout/setSocketTimeout/setMaxConnTotal` —— 属于技术升级范畴 |

## 5. 迁移顺序建议（重构阶段执行）

1. `pom.xml`：删 `elasticsearch-rest-high-level-client`、`elasticsearch`（7.6.2），加 `elasticsearch-java`（版本在父 POM）。
2. `RestHighLevelClientConfig` → 新配置类，产出 `ElasticsearchClient` Bean（方法名保留兼容可减少改动）。
3. `ElasticsearchServiceImpl` 按第 3 节逐方法翻译（这是唯一大改的文件）。
4. `SearchService` 接口签名不变（index/exists/update/findSmsByParameters/countSmsState），监听器/控制器零改动。
5. 测试迁移：旧测试 mock `RestHighLevelClient`，新客户端类型复杂，建议改为 mock `SearchService` 接口或集成测试。

## 6. 学习资源与验证

- 官方迁移指南：<https://www.elastic.co/guide/en/elasticsearch/client/java-api-client/current/migrate-hlrc.html>
- 新客户端文档：<https://www.elastic.co/guide/en/elasticsearch/client/java-api-client/current/index.html>
- 验证：启动 beacon-search（连本地 ES 8.x + 安全认证）→ 发一条 `sms_write_log_topic` 消息 → Kibana 查 `sms_submit_log_{year}` 出现文档；再发 Deliver 回执 → 10s 后 reportState 更新。

## 7. 遗留注意

- ES 服务端也要从 7.6.2 升到 8.x（升级基线），**索引无显式 mapping 依赖动态映射**，升级服务端后建议用 Kibana 建显式 mapping 与 ILM（属于后处理优化项）。
- 跨年索引路由、无租户隔离、幂等判定等业务缺陷本轮不修（分析报告 🔴#13/20/21）。

---

*下一篇：04 Netty 4.1 升级与 CMPP 网关改造。*
