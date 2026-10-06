package com.citygas.network;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.citygas.network.common.Dict;
import com.citygas.network.entity.InspectionBatch;
import com.citygas.network.repository.InspectionBatchRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批次列表「筛选 + 分页」接口测试（MockMvc + H2）：
 * ① 关键字（批次编号/负责人）只命中当前租户；
 * ② 状态 + 关键字 + 「只看过期未执行」任意组合都取交集，不丢条件；
 * ③ 翻页 / 改页大小时筛选条件保留，绝不混入其它租户或上一轮结果；
 * ④ 空结果返回统一分页结构，非法状态返回统一错误结构，空筛选等价于不筛选。
 */
@AutoConfigureMockMvc
class InspectionSearchWebTest extends BaseIntegrationTest {

    private static final String A = "A片区";
    private static final String B = "B片区";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private InspectionBatchRepository batchRepository;

    /** 直接落库一个批次（绕过创建流程，专注列表查询语义），返回带 id 的实体。 */
    private InspectionBatch seed(String tenant, String batchNo, String owner,
                                 String status, LocalDateTime planStartAt) {
        useTenant(tenant);
        InspectionBatch b = new InspectionBatch();
        b.setBatchNo(batchNo);
        b.setOwnerName(owner);
        b.setStatus(status);
        b.setPlanStartAt(planStartAt);
        b.setRemark("说明-" + batchNo);
        b.setCreatedAt(LocalDateTime.now());
        b.setUpdatedAt(LocalDateTime.now());
        return batchRepository.saveAndFlush(b);
    }

    private JsonNode list(String tenant, java.util.function.UnaryOperator<MockHttpServletRequestBuilder> custom)
            throws Exception {
        MvcResult r = mockMvc.perform(custom.apply(get("/api/inspection-batches"))
                        .header("X-Tenant", tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andReturn();
        // 按字节解析：JSON 规范即 UTF-8，避免 MockHttpServletResponse 默认 ISO-8859-1 把中文解码成乱码
        return objectMapper.readTree(r.getResponse().getContentAsByteArray()).path("data");
    }

    private static List<Long> ids(JsonNode data) {
        List<Long> ids = new ArrayList<>();
        data.path("items").forEach(n -> ids.add(n.path("id").asLong()));
        return ids;
    }

    private static List<String> batchNos(JsonNode data) {
        List<String> nos = new ArrayList<>();
        data.path("items").forEach(n -> nos.add(n.path("batchNo").asText()));
        return nos;
    }

    // ---------------- 多租户边界：关键字只命中当前租户 ----------------

    @Test
    void keyword_search_should_only_match_current_tenant() throws Exception {
        LocalDateTime future = LocalDateTime.now().plusDays(1);
        InspectionBatch a1 = seed(A, "XJ-2026-001", "张三", Dict.BATCH_PENDING, future);
        InspectionBatch a2 = seed(A, "XJ-2026-002", "李四", Dict.BATCH_PENDING, future);
        InspectionBatch a3 = seed(A, "PQ-100", "王五", Dict.BATCH_PENDING, future);
        // 其它租户：编号与负责人都和 A 租户撞车，最容易混入
        InspectionBatch b1 = seed(B, "XJ-2026-001", "张三", Dict.BATCH_PENDING, future);

        // 按编号关键字：A 租户只能看到本租户的两条
        JsonNode byNo = list(A, req -> req.param("keyword", "XJ-2026"));
        assertThat(byNo.path("total").asLong()).isEqualTo(2);
        assertThat(ids(byNo)).containsExactlyInAnyOrder(a1.getId(), a2.getId());
        assertThat(ids(byNo)).doesNotContain(b1.getId());

        // 按负责人关键字：B 租户的张三不得出现
        JsonNode byOwner = list(A, req -> req.param("keyword", "张三"));
        assertThat(byOwner.path("total").asLong()).isEqualTo(1);
        assertThat(ids(byOwner)).containsExactly(a1.getId());

        // B 租户视角：同名批次只能查到自己的那一条
        JsonNode bView = list(B, req -> req.param("keyword", "XJ-2026"));
        assertThat(bView.path("total").asLong()).isEqualTo(1);
        assertThat(ids(bView)).containsExactly(b1.getId());

        // 不带筛选的列表同样隔离
        JsonNode plainB = list(B, req -> req);
        assertThat(plainB.path("total").asLong()).isEqualTo(1);
        JsonNode plainA = list(A, req -> req);
        assertThat(ids(plainA)).containsExactlyInAnyOrder(a1.getId(), a2.getId(), a3.getId());
    }

    // ---------------- 组合筛选：状态 × 关键字取交集 ----------------

    @Test
    void status_and_keyword_should_combine_as_intersection() throws Exception {
        LocalDateTime future = LocalDateTime.now().plusDays(1);
        InspectionBatch c1 = seed(A, "CB-1", "张三", Dict.BATCH_PENDING, future);
        InspectionBatch c2 = seed(A, "CB-2", "张三", Dict.BATCH_RUNNING, future);
        seed(A, "CB-3", "李四", Dict.BATCH_RUNNING, future);

        JsonNode runZhang = list(A, req -> req.param("status", "执行中").param("keyword", "张三"));
        assertThat(ids(runZhang)).containsExactly(c2.getId());

        JsonNode pendingCb = list(A, req -> req.param("status", "待执行").param("keyword", "CB"));
        assertThat(ids(pendingCb)).containsExactly(c1.getId());

        // 交集为空：正常返回统一分页结构，而不是报错或漏出别的状态
        JsonNode closedCb = list(A, req -> req.param("status", "已关闭").param("keyword", "CB"));
        assertThat(closedCb.path("total").asLong()).isEqualTo(0);
        assertThat(closedCb.path("items").isEmpty()).isTrue();
    }

    // ---------------- 组合筛选：只看过期未执行 × 状态 × 关键字 ----------------

    @Test
    void overdue_only_should_combine_with_status_and_keyword() throws Exception {
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
        LocalDateTime tomorrow = LocalDateTime.now().plusDays(1);
        InspectionBatch o1 = seed(A, "OD-1", "张三", Dict.BATCH_PENDING, yesterday); // 过期未执行
        seed(A, "OD-2", "李四", Dict.BATCH_PENDING, tomorrow);                       // 未过期
        seed(A, "OD-3", "张三", Dict.BATCH_RUNNING, yesterday);                      // 已执行不算
        InspectionBatch o4 = seed(A, "OD-4", "李四", Dict.BATCH_PENDING, yesterday); // 过期未执行
        InspectionBatch bOverdue = seed(B, "OD-1", "张三", Dict.BATCH_PENDING, yesterday);

        JsonNode overdue = list(A, req -> req.param("overdueOnly", "true"));
        assertThat(ids(overdue)).containsExactlyInAnyOrder(o1.getId(), o4.getId());

        JsonNode overdueZhang = list(A, req -> req.param("overdueOnly", "true").param("keyword", "张三"));
        assertThat(ids(overdueZhang)).containsExactly(o1.getId());
        assertThat(ids(overdueZhang)).doesNotContain(bOverdue.getId());

        // 状态条件不被「只看过期」吞掉：过期未执行 ∩ 待执行 = 全部过期未执行
        JsonNode overduePending = list(A, req -> req.param("overdueOnly", "true").param("status", "待执行"));
        assertThat(ids(overduePending)).containsExactlyInAnyOrder(o1.getId(), o4.getId());

        // 过期未执行 ∩ 执行中 = 空（交集语义，而不是忽略状态）
        JsonNode overdueRunning = list(A, req -> req.param("overdueOnly", "true").param("status", "执行中"));
        assertThat(overdueRunning.path("total").asLong()).isEqualTo(0);
        assertThat(overdueRunning.path("items").isEmpty()).isTrue();
    }

    // ---------------- 翻页 / 改页大小：筛选条件保留，不混入其它租户 ----------------

    @Test
    void pagination_should_keep_filters_and_never_mix_other_tenants() throws Exception {
        LocalDateTime future = LocalDateTime.now().plusDays(1);
        List<Long> pagedIds = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            pagedIds.add(seed(A, String.format("PG-%02d", i), "分页员", Dict.BATCH_PENDING, future).getId());
        }
        seed(A, "OT-1", "其他人", Dict.BATCH_PENDING, future);
        seed(A, "OT-2", "其他人", Dict.BATCH_PENDING, future);
        seed(B, "PG-B1", "分页员", Dict.BATCH_PENDING, future);
        seed(B, "PG-B2", "分页员", Dict.BATCH_PENDING, future);

        // 第一页：10 条，共 12 条 2 页
        JsonNode page0 = list(A, req -> req.param("keyword", "分页员").param("page", "0").param("size", "10"));
        assertThat(page0.path("total").asLong()).isEqualTo(12);
        assertThat(page0.path("totalPages").asInt()).isEqualTo(2);
        assertThat(page0.path("items").size()).isEqualTo(10);
        assertThat(ids(page0)).allMatch(pagedIds::contains);

        // 第二页：条件保留，仍只命中「分页员」，其余 2 条，不混入「其他人」或 B 租户
        JsonNode page1 = list(A, req -> req.param("keyword", "分页员").param("page", "1").param("size", "10"));
        assertThat(page1.path("total").asLong()).isEqualTo(12);
        assertThat(page1.path("items").size()).isEqualTo(2);
        assertThat(ids(page1)).allMatch(pagedIds::contains);
        assertThat(ids(page1)).doesNotContainAnyElementsOf(ids(page0));
        page1.path("items").forEach(n -> assertThat(n.path("ownerName").asText()).isEqualTo("分页员"));

        // 改页大小：按 size=5 重新切页，最后一页 2 条，总数不变
        JsonNode sized = list(A, req -> req.param("keyword", "分页员").param("page", "2").param("size", "5"));
        assertThat(sized.path("total").asLong()).isEqualTo(12);
        assertThat(sized.path("totalPages").asInt()).isEqualTo(3);
        assertThat(sized.path("items").size()).isEqualTo(2);
        assertThat(ids(sized)).allMatch(pagedIds::contains);

        // B 租户同样的关键字只能看到自己的 2 条
        JsonNode bView = list(B, req -> req.param("keyword", "分页员"));
        assertThat(bView.path("total").asLong()).isEqualTo(2);
        assertThat(batchNos(bView)).containsExactlyInAnyOrder("PG-B1", "PG-B2");

        // 非法分页参数兜底：负页码按第一页处理，size=0 收敛到合法范围，均不报错
        JsonNode negPage = list(A, req -> req.param("page", "-1").param("size", "10"));
        assertThat(negPage.path("page").asInt()).isEqualTo(0);
        JsonNode zeroSize = list(A, req -> req.param("size", "0"));
        assertThat(zeroSize.path("size").asInt()).isGreaterThanOrEqualTo(1);
    }

    // ---------------- 空结果：统一分页结构 ----------------

    @Test
    void empty_result_should_return_unified_page_structure() throws Exception {
        seed(A, "XJ-ONLY", "张三", Dict.BATCH_PENDING, LocalDateTime.now().plusDays(1));

        JsonNode data = list(A, req -> req.param("keyword", "不存在的关键字"));
        assertThat(data.path("total").asLong()).isEqualTo(0);
        assertThat(data.path("totalPages").asInt()).isEqualTo(0);
        assertThat(data.path("page").asInt()).isEqualTo(0);
        assertThat(data.path("size").asInt()).isEqualTo(10);
        assertThat(data.path("items").isArray()).isTrue();
        assertThat(data.path("items").isEmpty()).isTrue();
    }

    // ---------------- 异常反馈：非法状态统一错误结构，空筛选等价不筛选 ----------------

    @Test
    void invalid_status_should_return_unified_error_and_blank_filters_should_pass() throws Exception {
        seed(A, "XJ-ONLY", "张三", Dict.BATCH_PENDING, LocalDateTime.now().plusDays(1));

        // 非法状态：400 + 统一错误结构 {ok:false, message}
        mockMvc.perform(get("/api/inspection-batches")
                        .header("X-Tenant", A)
                        .param("status", "不存在状态"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("批次状态不合法")));

        // 空状态串：等价于不筛选，正常返回
        JsonNode blankStatus = list(A, req -> req.param("status", ""));
        assertThat(blankStatus.path("total").asLong()).isEqualTo(1);

        // 空白关键字：等价于不筛选，正常返回
        JsonNode blankKeyword = list(A, req -> req.param("keyword", "   "));
        assertThat(blankKeyword.path("total").asLong()).isEqualTo(1);

        // 完全不带参数：正常返回统一分页结构
        JsonNode noParam = list(A, req -> req);
        assertThat(noParam.path("total").asLong()).isEqualTo(1);
    }
}
