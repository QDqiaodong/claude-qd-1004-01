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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口层测试（MockMvc + H2）：
 * ① 统一错误结构 {ok:false, message}；非法跳转/空描述 400，并发关闭 409；
 * ② X-Tenant 头强制租户隔离；③ 门站/调压站/管段/燃气用户既有接口不回归。
 */
@AutoConfigureMockMvc
class InspectionWebTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private String json(String s) {
        return s;
    }

    private MvcResult postJson(String url, String tenant, String body) throws Exception {
        return mockMvc.perform(post(url)
                        .header("X-Tenant", tenant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private Long createPipe(String tenant, String code) throws Exception {
        MvcResult r = postJson("/api/pipe-segments", tenant, json("""
                {"segCode":"%s","startPoint":"A","endPoint":"B","material":"钢管",
                 "pressureLevel":"中压","lengthM":100,"status":"正常"}
                """.formatted(code)));
        return objectMapper.readTree(r.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private Long createBatch(String tenant, String no, Long targetId) throws Exception {
        String body = json("""
                {"batchNo":"%s","planStartAt":"%s","ownerName":"张工","remark":"例行巡检",
                 "targets":[{"targetType":"PIPE_SEGMENT","targetId":%d}]}
                """.formatted(no, java.time.LocalDateTime.now().plusDays(1).toString(), targetId));
        MvcResult r = postJson("/api/inspection-batches", tenant, body);
        return objectMapper.readTree(r.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    // ---------------- 统一错误结构 / 校验 ----------------

    @Test
    void create_batch_blank_remark_should_return_unified_error() throws Exception {
        Long segId = createPipe("A片区", "PS-W01");
        String body = json("""
                {"batchNo":"W-001","planStartAt":"%s","ownerName":"张工","remark":"   ",
                 "targets":[{"targetType":"PIPE_SEGMENT","targetId":%d}]}
                """.formatted(java.time.LocalDateTime.now().plusDays(1).toString(), segId));

        mockMvc.perform(post("/api/inspection-batches")
                        .header("X-Tenant", "A片区")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("巡检说明不能为空")));
    }

    @Test
    void duplicate_batch_no_should_return_unified_error() throws Exception {
        Long segId = createPipe("A片区", "PS-W02");
        createBatch("A片区", "W-DUP", segId);
        String body = json("""
                {"batchNo":"W-DUP","planStartAt":"%s","ownerName":"张工","remark":"重复",
                 "targets":[{"targetType":"PIPE_SEGMENT","targetId":%d}]}
                """.formatted(java.time.LocalDateTime.now().plusDays(1).toString(), segId));
        mockMvc.perform(post("/api/inspection-batches")
                        .header("X-Tenant", "A片区")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("批次编号已存在")));
    }

    @Test
    void invalid_object_and_cross_tenant_target_should_be_rejected() throws Exception {
        // 对象不存在
        String body = json("""
                {"batchNo":"W-INV","planStartAt":"%s","ownerName":"张工","remark":"无效对象",
                 "targets":[{"targetType":"PIPE_SEGMENT","targetId":999999}]}
                """.formatted(java.time.LocalDateTime.now().plusDays(1).toString()));
        mockMvc.perform(post("/api/inspection-batches")
                        .header("X-Tenant", "A片区")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("不存在或不属于当前租户")));

        // B 租户对象，A 租户关联
        Long segB = createPipe("B片区", "PS-W03");
        String body2 = json("""
                {"batchNo":"W-CROSS","planStartAt":"%s","ownerName":"张工","remark":"跨租户",
                 "targets":[{"targetType":"PIPE_SEGMENT","targetId":%d}]}
                """.formatted(java.time.LocalDateTime.now().plusDays(1).toString(), segB));
        mockMvc.perform(post("/api/inspection-batches")
                        .header("X-Tenant", "A片区")
                        .contentType(MediaType.APPLICATION_JSON).content(body2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("不存在或不属于当前租户")));
    }

    // ---------------- 租户隔离 ----------------

    @Test
    void batch_should_be_invisible_across_tenants() throws Exception {
        Long segId = createPipe("A片区", "PS-W10");
        Long batchId = createBatch("A片区", "W-ISO", segId);

        mockMvc.perform(get("/api/inspection-batches/" + batchId).header("X-Tenant", "A片区"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.batch.batchNo").value("W-ISO"));

        mockMvc.perform(get("/api/inspection-batches/" + batchId).header("X-Tenant", "B片区"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("批次不存在")));

        // 列表隔离
        mockMvc.perform(get("/api/inspection-batches").header("X-Tenant", "B片区"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    // ---------------- 状态机 + 409 ----------------

    @Test
    void expired_batch_skip_execution_should_fail_and_close_conflict_returns_409() throws Exception {
        Long segId = createPipe("A片区", "PS-W20");
        String body = json("""
                {"batchNo":"W-EXP","planStartAt":"%s","ownerName":"张工","remark":"过期批次",
                 "targets":[{"targetType":"PIPE_SEGMENT","targetId":%d}]}
                """.formatted(java.time.LocalDateTime.now().minusDays(1).toString(), segId));
        MvcResult r = postJson("/api/inspection-batches", "A片区", body);
        Long batchId = objectMapper.readTree(r.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        // 过期跳过执行：400
        mockMvc.perform(post("/api/inspection-batches/" + batchId + "/request-review")
                        .header("X-Tenant", "A片区")
                        .contentType(MediaType.APPLICATION_JSON).content(json("""
                                {"operator":"甲"}""")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("必须先补开始执行")));

        // 非法跳转直接关闭：400
        mockMvc.perform(post("/api/inspection-batches/" + batchId + "/close")
                        .header("X-Tenant", "A片区")
                        .contentType(MediaType.APPLICATION_JSON).content(json("""
                                {"operator":"甲"}""")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("非法状态跳转")));

        // 走完整无隐患路径
        mockMvc.perform(post("/api/inspection-batches/" + batchId + "/start")
                        .header("X-Tenant", "A片区")
                        .contentType(MediaType.APPLICATION_JSON).content(json("""
                                {"operator":"甲"}""")))
                .andExpect(status().isOk());

        MvcResult detail = mockMvc.perform(get("/api/inspection-batches/" + batchId)
                        .header("X-Tenant", "A片区"))
                .andReturn();
        Long linkId = objectMapper.readTree(detail.getResponse().getContentAsString())
                .path("data").path("targets").get(0).path("linkId").asLong();

        mockMvc.perform(post("/api/inspection-batches/" + batchId + "/targets/" + linkId + "/inspect")
                        .header("X-Tenant", "A片区")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"result":"正常","operator":"甲","requestId":"REQ-WEXP-1"}""")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/inspection-batches/" + batchId + "/request-review")
                        .header("X-Tenant", "A片区")
                        .contentType(MediaType.APPLICATION_JSON).content(json("""
                                {"operator":"甲"}""")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/inspection-batches/" + batchId + "/close")
                        .header("X-Tenant", "A片区")
                        .contentType(MediaType.APPLICATION_JSON).content(json("""
                                {"operator":"赵"}""")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.closedBy").value("赵"));

        // 重复关闭：409 + 统一错误结构 + 不覆盖关闭人
        mockMvc.perform(post("/api/inspection-batches/" + batchId + "/close")
                        .header("X-Tenant", "A片区")
                        .contentType(MediaType.APPLICATION_JSON).content(json("""
                                {"operator":"钱"}""")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已由")));

        mockMvc.perform(get("/api/inspection-batches/" + batchId).header("X-Tenant", "A片区"))
                .andExpect(jsonPath("$.data.batch.closedBy").value("赵"));
    }

    // ---------------- 既有能力不回归 ----------------

    @Test
    void existing_modules_should_still_work() throws Exception {
        // 门站
        mockMvc.perform(post("/api/gate-stations")
                        .header("X-Tenant", "A片区")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"stationCode":"GS-W01","stationName":"测试门站",
                                 "inletPressureMpa":1.2,"outletPressureMpa":0.36,
                                 "dailyFlowM3":1000,"odorantMg":22,"status":"运行"}""")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stationCode").value("GS-W01"));

        // 调压站
        mockMvc.perform(post("/api/regulator-stations")
                        .header("X-Tenant", "A片区")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"stationCode":"RS-W01","stationName":"测试调压站","stationType":"区域调压站",
                                 "inPressureMpa":0.38,"outletPressureMpa":0.12,"status":"运行"}""")))
                .andExpect(status().isOk());

        // 管段
        mockMvc.perform(post("/api/pipe-segments")
                        .header("X-Tenant", "B片区")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"segCode":"PS-W99","startPoint":"X","endPoint":"Y","material":"PE管",
                                 "pressureLevel":"低压","lengthM":50,"status":"正常"}""")))
                .andExpect(status().isOk());

        // 燃气用户
        mockMvc.perform(post("/api/gas-users")
                        .header("X-Tenant", "A片区")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"userNo":"U-W01","userName":"测试户","address":"测试路1号",
                                 "meterNo":"MW01","lastReading":0,"currentReading":0}""")))
                .andExpect(status().isOk());

        // 列表 & 租户过滤
        mockMvc.perform(get("/api/pipe-segments").header("X-Tenant", "B片区"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].segCode").value("PS-W99"));
        mockMvc.perform(get("/api/gate-stations").header("X-Tenant", "B片区"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        // 既有业务规则仍生效：报废管段不能巡检
        Long scrapId = createPipe("A片区", "PS-W88");
        mockMvc.perform(post("/api/pipe-segments/" + scrapId + "/transfer")
                        .header("X-Tenant", "A片区")
                        .param("target", "报废"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/pipe-segments/" + scrapId + "/patrol")
                        .header("X-Tenant", "A片区"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("报废")));
    }
}
