package com.citygas.network;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.citygas.network.entity.GasUser;
import com.citygas.network.entity.PipeSegment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 检修单接口层测试（MockMvc + H2）：
 * ① 统一错误结构 {ok:false, message}；非法跳转/空说明 400，版本冲突与重复发布/复气 409；
 * ② X-Tenant 头强制租户隔离；③ 发布冻结快照后差异提示可见；④ 双闸门未满足时复气被拦截。
 */
@AutoConfigureMockMvc
class MaintenanceWebTest extends BaseIntegrationTest {

    private static final String A = "A片区";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    /** 标准拓扑 + 草稿检修单，返回检修单 id */
    private Long prepareDraft(String orderNo) {
        useTenant(A);
        newGateStation(A, "GS-W01", "青云门站");
        newRegulator(A, "RS-W01", "绿谷小区调压箱");
        PipeSegment seg = newSegment(A, "PS-W01", "青云门站", "绿谷小区");
        newUser(A, "U-W01", "绿谷小区1栋101");
        newUser(A, "U-W02", "绿谷小区2栋202");
        try {
            MvcResult r = mockMvc.perform(post("/api/maintenance-orders")
                            .header("X-Tenant", A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"orderNo":"%s","planStartAt":"%s","planEndAt":"%s",
                                     "leaderName":"施工队长王","scopeRemark":"更换老旧管段",
                                     "segmentIds":[%d]}
                                    """.formatted(orderNo,
                                    java.time.LocalDateTime.now().plusDays(1).toString(),
                                    java.time.LocalDateTime.now().plusDays(2).toString(),
                                    seg.getId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andReturn();
            return objectMapper.readTree(r.getResponse().getContentAsString())
                    .path("data").path("id").asLong();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode getJson(String url, String tenant) throws Exception {
        MvcResult r = mockMvc.perform(get(url).header("X-Tenant", tenant)).andReturn();
        // 测试环境响应头不带 charset，按字节以 UTF-8 解码，避免中文乱码
        return objectMapper.readTree(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private long versionOf(Long orderId) throws Exception {
        return getJson("/api/maintenance-orders/" + orderId, A).path("data").path("order")
                .path("version").asLong();
    }

    private MvcResult action(Long orderId, String action, String body) throws Exception {
        return mockMvc.perform(post("/api/maintenance-orders/" + orderId + "/" + action)
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    // ---------------- 统一错误结构 / 校验 ----------------

    @Test
    void create_with_blank_leader_should_return_unified_error() throws Exception {
        useTenant(A);
        PipeSegment seg = newSegment(A, "PS-W10", "青云门站", "绿谷小区");
        mockMvc.perform(post("/api/maintenance-orders")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderNo":"MO-W-BAD","planStartAt":"%s","leaderName":"  ",
                                 "scopeRemark":"x","segmentIds":[%d]}
                                """.formatted(java.time.LocalDateTime.now().plusDays(1), seg.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("施工负责人不能为空")));
    }

    @Test
    void publish_with_stale_version_should_return_409() throws Exception {
        Long orderId = prepareDraft("MO-W-VER");
        mockMvc.perform(post("/api/maintenance-orders/" + orderId + "/publish")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"调度甲\",\"version\":99}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("已被他人修改")));
    }

    // ---------------- 租户隔离 ----------------

    @Test
    void order_should_be_invisible_across_tenants() throws Exception {
        Long orderId = prepareDraft("MO-W-ISO");
        mockMvc.perform(get("/api/maintenance-orders/" + orderId).header("X-Tenant", "B片区"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("检修单不存在")));
        mockMvc.perform(get("/api/maintenance-orders").header("X-Tenant", "B片区"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    // ---------------- 发布冻结 / 重复发布 409 / 差异提示 ----------------

    @Test
    void publish_freezes_snapshot_and_duplicate_publish_conflicts() throws Exception {
        Long orderId = prepareDraft("MO-W-PUB");
        long version = versionOf(orderId);

        // 重复单号：400
        useTenant(A);
        PipeSegment seg = segmentRepository.findBySegCode("PS-W01").orElseThrow();
        mockMvc.perform(post("/api/maintenance-orders")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderNo":"MO-W-PUB","planStartAt":"%s","leaderName":"施工队长王",
                                 "scopeRemark":"重复单号","segmentIds":[%d]}
                                """.formatted(java.time.LocalDateTime.now().plusDays(1), seg.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("检修单号已存在")));

        // 发布成功
        mockMvc.perform(post("/api/maintenance-orders/" + orderId + "/publish")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"调度甲\",\"version\":" + version
                                + ",\"requestId\":\"REQ-W-PUB-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("待停气"))
                .andExpect(jsonPath("$.data.publishedBy").value("调度甲"));

        // 同 requestId 网络重试：回放首次结果，不报错、不重复推进
        mockMvc.perform(post("/api/maintenance-orders/" + orderId + "/publish")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"调度甲\",\"version\":" + version
                                + ",\"requestId\":\"REQ-W-PUB-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publishedBy").value("调度甲"));

        // 另一操作员重复发布：409 + 统一错误结构
        mockMvc.perform(post("/api/maintenance-orders/" + orderId + "/publish")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"调度乙\",\"version\":" + versionOf(orderId) + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("已由")));

        // 发布后档案变化：新用户迁入 → 详情出现差异提示，快照行数不变
        useTenant(A);
        newUser(A, "U-W09", "绿谷小区9栋901");
        JsonNode detail = getJson("/api/maintenance-orders/" + orderId, A).path("data");
        org.assertj.core.api.Assertions.assertThat(detail.path("impacts").size()).isEqualTo(4);
        boolean hasAddedDiff = false;
        for (JsonNode diff : detail.path("diffs")) {
            if ("ADDED".equals(diff.path("diffType").asText())
                    && "U-W09".equals(diff.path("objectCode").asText())) {
                hasAddedDiff = true;
            }
        }
        org.assertj.core.api.Assertions.assertThat(hasAddedDiff).as("差异提示包含新迁入用户").isTrue();
    }

    // ---------------- 人工边界 ----------------

    @Test
    void manual_boundary_via_api_should_require_note_and_support_remove() throws Exception {
        Long orderId = prepareDraft("MO-W-BOUND");
        useTenant(A);
        GasUser outsider = newUser(A, "U-W88", "远郊路9号");

        // 缺人工说明：400
        mockMvc.perform(post("/api/maintenance-orders/" + orderId + "/boundaries")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectType\":\"GAS_USER\",\"objectId\":" + outsider.getId()
                                + ",\"note\":\"  \",\"version\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("人工说明不能为空")));

        // 正常补充
        MvcResult added = mockMvc.perform(post("/api/maintenance-orders/" + orderId + "/boundaries")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectType\":\"GAS_USER\",\"objectId\":" + outsider.getId()
                                + ",\"note\":\"远郊路支线人工核对纳入\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("MANUAL"))
                .andReturn();
        long impactId = objectMapper.readTree(added.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        // 移除人工边界
        mockMvc.perform(delete("/api/maintenance-orders/" + orderId + "/boundaries/" + impactId)
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + versionOf(orderId) + "}"))
                .andExpect(status().isOk());
        JsonNode detail = getJson("/api/maintenance-orders/" + orderId, A).path("data");
        org.assertj.core.api.Assertions.assertThat(detail.path("impacts").size()).isEqualTo(4);
    }

    // ---------------- 全链路：双闸门拦截与复气成功 ----------------

    @Test
    void full_flow_should_block_restore_until_gates_pass_then_complete() throws Exception {
        Long orderId = prepareDraft("MO-W-FLOW");

        // 发布 → 停气 → 完工
        action(orderId, "publish",
                "{\"operator\":\"调度甲\",\"version\":" + versionOf(orderId) + "}");
        action(orderId, "stop-gas",
                "{\"operator\":\"调度甲\",\"version\":" + versionOf(orderId) + "}");
        // 未停气时完工报验会被拒（先验证一次错误路径）
        JsonNode detail = getJson("/api/maintenance-orders/" + orderId, A).path("data");
        org.assertj.core.api.Assertions.assertThat(detail.path("order").path("status").asText())
                .isEqualTo("施工中");
        action(orderId, "finish-work",
                "{\"operator\":\"施工队长王\",\"version\":" + versionOf(orderId) + "}");

        // 待验收直接复气：400 非法状态跳转
        mockMvc.perform(post("/api/maintenance-orders/" + orderId + "/restore-gas")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"调度甲\",\"version\":" + versionOf(orderId) + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("非法状态跳转")));

        // 逐段验收 → 自动推进待复气
        JsonNode accepting = getJson("/api/maintenance-orders/" + orderId, A).path("data");
        long linkId = accepting.path("segments").get(0).path("id").asLong();
        mockMvc.perform(post("/api/maintenance-orders/" + orderId + "/segments/" + linkId + "/accept")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"验收员赵\",\"note\":\"验收合格\",\"version\":"
                                + versionOf(orderId) + "}"))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions
                .assertThat(getJson("/api/maintenance-orders/" + orderId, A)
                        .path("data").path("order").path("status").asText())
                .isEqualTo("待复气");

        // 用户未确认：复气被第二道闸门拦截
        mockMvc.perform(post("/api/maintenance-orders/" + orderId + "/restore-gas")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"调度甲\",\"version\":" + versionOf(orderId) + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("安全确认")));

        // 逐户确认
        JsonNode restoring = getJson("/api/maintenance-orders/" + orderId, A).path("data");
        for (JsonNode impact : restoring.path("impacts")) {
            if ("GAS_USER".equals(impact.path("objectType").asText())) {
                mockMvc.perform(post("/api/maintenance-orders/" + orderId
                                + "/impacts/" + impact.path("id").asLong() + "/confirm")
                                .header("X-Tenant", A)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"operator\":\"安全员钱\"}"))
                        .andExpect(status().isOk());
            }
        }

        // 复气成功
        mockMvc.perform(post("/api/maintenance-orders/" + orderId + "/restore-gas")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"调度甲\",\"version\":" + versionOf(orderId)
                                + ",\"requestId\":\"REQ-W-RESTORE-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("已完成"))
                .andExpect(jsonPath("$.data.restoredBy").value("调度甲"));

        // 重复复气：409
        mockMvc.perform(post("/api/maintenance-orders/" + orderId + "/restore-gas")
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"调度乙\",\"version\":" + versionOf(orderId) + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("已由")));
    }

    // ---------------- 编辑草稿 ----------------

    @Test
    void update_draft_via_api_should_rebuild_impacts() throws Exception {
        Long orderId = prepareDraft("MO-W-EDIT");
        useTenant(A);
        PipeSegment seg2 = newSegment(A, "PS-W02", "绿谷小区", "绿谷小学");

        mockMvc.perform(put("/api/maintenance-orders/" + orderId)
                        .header("X-Tenant", A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"planStartAt":"%s","leaderName":"施工队长李","scopeRemark":"调整范围",
                                 "segmentIds":[%d],"version":%d}
                                """.formatted(java.time.LocalDateTime.now().plusDays(3),
                                seg2.getId(), versionOf(orderId))))
                .andExpect(status().isOk());
        JsonNode detail = getJson("/api/maintenance-orders/" + orderId, A).path("data");
        org.assertj.core.api.Assertions.assertThat(detail.path("order").path("leaderName").asText())
                .isEqualTo("施工队长李");
        org.assertj.core.api.Assertions.assertThat(detail.path("segments").get(0)
                .path("segCode").asText()).isEqualTo("PS-W02");
    }
}
