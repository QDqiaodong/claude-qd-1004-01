package com.citygas.network;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管段检修联动接口测试（MockMvc + H2）：
 * ① 统一错误结构 {ok:false,message}：校验失败/发布拦截/非法跳转 400；
 * ② X-Tenant 头强制租户隔离，跨租户检修单不可见、跨租户对象建不了草稿；
 * ③ 停气→开工→验收→申请复气→安全确认→复气放行全链路 HTTP 反馈；
 * ④ 验收未完成 / 安全确认未完成的拦截在接口层同样成立。
 */
@AutoConfigureMockMvc
class MaintenanceWebTest extends BaseIntegrationTest {

    private static final String A = "A片区";
    private static final String B = "B片区";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private MvcResult postJson(String url, String tenant, String body) throws Exception {
        return mockMvc.perform(post(url)
                        .header("X-Tenant", tenant)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private MvcResult putJson(String url, String tenant, String body) throws Exception {
        return mockMvc.perform(put(url)
                        .header("X-Tenant", tenant)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private JsonNode body(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    /** 建拓扑 + 检修草稿，返回 [draftId, segImpactId(管段行), userImpactId(用户行)] */
    private long[] prepareDraftWithTopology() throws Exception {
        // 管段：城东门站 → 兴业路调压站；门站/调压站与管段节点同名；用户地址含调压站名
        postJson("/api/gate-stations", A, """
                {"stationCode":"GS-W01","stationName":"城东门站","inletPressureMpa":1.2,
                 "outletPressureMpa":0.38,"dailyFlowM3":100000,"odorantMg":22,"status":"运行"}""");
        postJson("/api/regulator-stations", A, """
                {"stationCode":"RS-W01","stationName":"兴业路调压站","stationType":"区域调压站",
                 "inPressureMpa":0.38,"outletPressureMpa":0.12,"status":"运行"}""");
        MvcResult segR = postJson("/api/pipe-segments", A, """
                {"segCode":"PS-W01","startPoint":"城东门站","endPoint":"兴业路调压站",
                 "material":"钢管","pressureLevel":"中压","lengthM":1000,"status":"正常"}""");
        long segId = body(segR).path("data").path("id").asLong();
        postJson("/api/gas-users", A, """
                {"userNo":"U-W01","userName":"王建国","address":"兴业路调压站旁1栋","meterNo":"M-W01",
                 "lastReading":0,"currentReading":0,"status":"正常"}""");

        String orderBody = """
                {"orderNo":"WX-W01","planStartAt":"%s","planEndAt":"%s","ownerName":"施工负责人甲",
                 "remark":"阀门更换","segmentIds":[%d],
                 "manualBoundaries":[{"code":"BND-W01","name":"围墙外阀井","note":"产权分界，现场挂牌"}]}
                """.formatted(java.time.LocalDateTime.now().plusDays(1),
                java.time.LocalDateTime.now().plusDays(2), segId);
        MvcResult orderR = postJson("/api/maintenance-orders", A, orderBody);
        long orderId = body(orderR).path("data").path("id").asLong();

        MvcResult detailR = mockMvc.perform(get("/api/maintenance-orders/" + orderId)
                        .header("X-Tenant", A)).andReturn();
        JsonNode impacts = body(detailR).path("data").path("impacts");
        long segImpactId = 0;
        long userImpactId = 0;
        for (JsonNode i : impacts) {
            if ("PIPE_SEGMENT".equals(i.path("targetType").asText())) {
                segImpactId = i.path("id").asLong();
            }
            if ("GAS_USER".equals(i.path("targetType").asText())) {
                userImpactId = i.path("id").asLong();
            }
        }
        return new long[] {orderId, segImpactId, userImpactId};
    }

    private String action(String operator) {
        return """
                {"operator":"%s"}""".formatted(operator);
    }

    // ---------------- 校验与统一错误结构 ----------------

    @Test
    void create_blank_owner_and_empty_scope_should_return_unified_error() throws Exception {
        String bad = """
                {"orderNo":"WX-BAD","planStartAt":"%s","ownerName":"   ",
                 "segmentIds":[999999]}
                """.formatted(java.time.LocalDateTime.now().plusDays(1));
        mockMvc.perform(post("/api/maintenance-orders")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("施工负责人不能为空")));
    }

    @Test
    void cross_tenant_segment_and_invisible_order_should_be_rejected() throws Exception {
        // B 租户建管段，A 租户拿来建草稿
        MvcResult segR = postJson("/api/pipe-segments", B, """
                {"segCode":"PS-WB01","startPoint":"X","endPoint":"Y","material":"钢管",
                 "pressureLevel":"中压","lengthM":100,"status":"正常"}""");
        long segB = body(segR).path("data").path("id").asLong();

        String body = """
                {"orderNo":"WX-CROSS","planStartAt":"%s","ownerName":"负责人甲",
                 "segmentIds":[%d]}
                """.formatted(java.time.LocalDateTime.now().plusDays(1), segB);
        mockMvc.perform(post("/api/maintenance-orders")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("不存在或不属于当前租户")));

        long[] ids = prepareDraftWithTopology();
        long orderId = ids[0];
        // B 租户看不到 A 的检修单
        mockMvc.perform(get("/api/maintenance-orders/" + orderId).header("X-Tenant", B))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("检修单不存在")));
    }

    // ---------------- 发布拦截 ----------------

    @Test
    void publish_blocked_when_key_station_disabled_and_edits_kept() throws Exception {
        // 单独构造：门站建成后转停用
        MvcResult gateR = postJson("/api/gate-stations", A, """
                {"stationCode":"GS-W09","stationName":"停用门站","inletPressureMpa":1.2,
                 "outletPressureMpa":0.38,"dailyFlowM3":100000,"odorantMg":22,"status":"运行"}""");
        long gateId = body(gateR).path("data").path("id").asLong();
        mockMvc.perform(post("/api/gate-stations/" + gateId + "/transfer?target=停用")
                        .header("X-Tenant", A))
                .andExpect(status().isOk());
        MvcResult segR = postJson("/api/pipe-segments", A, """
                {"segCode":"PS-W09","startPoint":"停用门站","endPoint":"末端",
                 "material":"钢管","pressureLevel":"中压","lengthM":1000,"status":"正常"}""");
        long segId = body(segR).path("data").path("id").asLong();
        String orderBody = """
                {"orderNo":"WX-W09","planStartAt":"%s","ownerName":"负责人甲",
                 "segmentIds":[%d]}
                """.formatted(java.time.LocalDateTime.now().plusDays(1), segId);
        MvcResult orderR = postJson("/api/maintenance-orders", A, orderBody);
        long orderId = body(orderR).path("data").path("id").asLong();

        // 详情里已给出 BLOCK 差异
        mockMvc.perform(get("/api/maintenance-orders/" + orderId).header("X-Tenant", A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.blockCount").value(org.hamcrest.Matchers.greaterThan(0)));

        // 发布 400 拦截，消息含「发布被拦截」「已保留全部编辑内容」
        mockMvc.perform(post("/api/maintenance-orders/" + orderId + "/publish")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON).content(action("发布人甲")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("发布被拦截"),
                                org.hamcrest.Matchers.containsString("已保留全部编辑内容"))));

        // 草稿仍在：状态待发布、frozen=false
        mockMvc.perform(get("/api/maintenance-orders/" + orderId).header("X-Tenant", A))
                .andExpect(jsonPath("$.data.order.status").value("待发布"))
                .andExpect(jsonPath("$.data.order.frozen").value(false));
    }

    // ---------------- 全链路：停复气联动 + 验收/安全确认拦截 ----------------

    @Test
    void full_chain_start_before_cutoff_blocked_and_restore_requires_both_conditions() throws Exception {
        long[] ids = prepareDraftWithTopology();
        long orderId = ids[0];
        long segImpactId = ids[1];
        long userImpactId = ids[2];

        // 发布
        mockMvc.perform(post("/api/maintenance-orders/" + orderId + "/publish")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON).content(action("发布人甲")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("待停气"))
                .andExpect(jsonPath("$.data.frozen").value(true));

        // 冻结后不可编辑
        String editBody = """
                {"orderNo":"WX-W01","planStartAt":"%s","ownerName":"负责人乙","segmentIds":[1]}
                """.formatted(java.time.LocalDateTime.now().plusDays(3));
        mockMvc.perform(put("/api/maintenance-orders/" + orderId)
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON).content(editBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("已发布冻结")));

        // 未停气先开工 → 400
        mockMvc.perform(post("/api/maintenance-orders/" + orderId + "/start-work")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON).content(action("施工员甲")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("尚未完成停气")));

        // 停气 → 开工 → 完工
        mockMvc.perform(post("/api/maintenance-orders/" + orderId + "/cutoff")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON).content(action("调度员甲")))
                .andExpect(jsonPath("$.data.status").value("待停气"))
                .andExpect(jsonPath("$.data.cutOffBy").value("调度员甲"));
        mockMvc.perform(post("/api/maintenance-orders/" + orderId + "/start-work")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON).content(action("施工员甲")))
                .andExpect(jsonPath("$.data.status").value("施工中"));
        mockMvc.perform(post("/api/maintenance-orders/" + orderId + "/finish-work")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON).content(action("施工员甲")))
                .andExpect(jsonPath("$.data.status").value("待验收"));

        // 验收不通过必须填原因
        mockMvc.perform(post("/api/maintenance-orders/" + orderId
                        + "/impacts/" + segImpactId + "/accept")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"验收员甲","passed":false}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("验收不通过时必须填写原因")));

        // 验收通过
        mockMvc.perform(post("/api/maintenance-orders/" + orderId
                        + "/impacts/" + segImpactId + "/accept")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"验收员甲","passed":true}"""))
                .andExpect(jsonPath("$.data.acceptanceStatus").value("验收通过"));

        // 申请复气（夹具只有 1 条管段，验收已齐）
        mockMvc.perform(post("/api/maintenance-orders/" + orderId + "/request-restore")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON).content(action("验收员甲")))
                .andExpect(jsonPath("$.data.status").value("待复气"));

        // 未做用户安全确认直接复气放行 → 400 拦截
        mockMvc.perform(post("/api/maintenance-orders/" + orderId + "/restore")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON).content(action("调度员乙")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("未完成安全确认")));

        // 用户安全确认
        mockMvc.perform(post("/api/maintenance-orders/" + orderId
                        + "/impacts/" + userImpactId + "/safety")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"安检员甲","note":"立管保压合格"}"""))
                .andExpect(jsonPath("$.data.safetyStatus").value("已确认"));

        // 复气放行成功
        mockMvc.perform(post("/api/maintenance-orders/" + orderId + "/restore")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON).content(action("调度员乙")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("已完成"))
                .andExpect(jsonPath("$.data.restoredBy").value("调度员乙"));

        // 完成后重复复气：返回首次结果（仍已完成，操作人保持首次）
        mockMvc.perform(post("/api/maintenance-orders/" + orderId + "/restore")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON).content(action("调度员丙")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("已完成"))
                .andExpect(jsonPath("$.data.restoredBy").value("调度员乙"));
    }
}
