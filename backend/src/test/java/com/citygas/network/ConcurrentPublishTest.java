package com.citygas.network;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.Commit;

import com.citygas.network.common.ConflictException;
import com.citygas.network.common.Dict;
import com.citygas.network.dto.AcceptRequest;
import com.citygas.network.dto.ConfirmRequest;
import com.citygas.network.dto.MaintActionRequest;
import com.citygas.network.dto.MaintCreateRequest;
import com.citygas.network.entity.MaintenanceImpact;
import com.citygas.network.entity.MaintenanceOrder;
import com.citygas.network.entity.MaintenanceSegment;
import com.citygas.network.entity.PipeSegment;
import com.citygas.network.repository.MaintenanceImpactRepository;
import com.citygas.network.repository.MaintenanceOrderRepository;
import com.citygas.network.repository.MaintenanceSegmentRepository;
import com.citygas.network.service.MaintenanceService;
import com.citygas.network.tenant.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「发布 / 复气」并发收口测试。
 *
 * <p>类级 {@link Commit}：两名操作员各自在独立线程/独立事务里推进同一检修单，
 * 悲观行锁把两人串行化——先到者推进状态，后到者拿到锁后在事务内复查到状态已推进，
 * 必须收到明确冲突（409），且发布人/复气人保持先到者的结果不被覆盖；
 * 未成功的请求事务整体回滚，不留半冻结快照或部分状态。</p>
 */
@Commit
class ConcurrentPublishTest extends BaseIntegrationTest {

    private static final String TENANT = "A片区";

    @Autowired
    private MaintenanceService maintenanceService;
    @Autowired
    private MaintenanceOrderRepository orderRepository;
    @Autowired
    private MaintenanceSegmentRepository segmentLinkRepository;
    @Autowired
    private MaintenanceImpactRepository impactRepository;

    @Test
    void concurrent_publish_should_only_one_win_and_snapshot_stays_consistent() throws Exception {
        Long orderId = prepareDraftOrder();

        List<String> outcomes = runConcurrently(() -> {
            MaintenanceOrder current = orderRepository.findScopedById(orderId).orElseThrow();
            maintenanceService.publish(orderId, action("调度员-" + Thread.currentThread().getId(),
                    current.getVersion(), null));
        });

        long wins = outcomes.stream().filter("OK"::equals).count();
        long conflicts = outcomes.stream().filter("CONFLICT"::equals).count();
        assertThat(wins).as("恰好一人发布成功").isEqualTo(1);
        assertThat(conflicts).as("另一人收到明确冲突").isEqualTo(1);

        TenantContext.set(TENANT);
        MaintenanceOrder after = orderRepository.findScopedById(orderId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Dict.MAINT_PUBLISHED);
        assertThat(after.getPublishedBy()).startsWith("调度员-");
        // 快照完整冻结：4 行影响、1 段管段，绝无半冻结或重复行
        assertThat(impactRepository.findByOrderIdOrderByIdAsc(orderId)).hasSize(4);
        assertThat(segmentLinkRepository.findByOrderIdOrderByIdAsc(orderId)).hasSize(1);
    }

    @Test
    void concurrent_restore_should_only_one_win() throws Exception {
        Long orderId = prepareRestoringOrder();

        List<String> outcomes = runConcurrently(() -> {
            MaintenanceOrder current = orderRepository.findScopedById(orderId).orElseThrow();
            maintenanceService.restoreGas(orderId, action("调度员-" + Thread.currentThread().getId(),
                    current.getVersion(), null));
        });

        long wins = outcomes.stream().filter("OK"::equals).count();
        long conflicts = outcomes.stream().filter("CONFLICT"::equals).count();
        assertThat(wins).as("恰好一人复气成功").isEqualTo(1);
        assertThat(conflicts).as("另一人收到明确冲突").isEqualTo(1);

        TenantContext.set(TENANT);
        MaintenanceOrder after = orderRepository.findScopedById(orderId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Dict.MAINT_DONE);
        assertThat(after.getRestoredBy()).startsWith("调度员-");
        assertThat(after.getCompletedAt()).isNotNull();
    }

    // ---------------- 数据准备与并发执行 ----------------

    private MaintActionRequest action(String operator, Long version, String requestId) {
        MaintActionRequest req = new MaintActionRequest();
        req.setOperator(operator);
        req.setVersion(version);
        req.setRequestId(requestId);
        return req;
    }

    /** 标准拓扑草稿：门站—管段—调压箱—两户居民，影响清单 4 行 */
    private Long prepareDraftOrder() {
        TenantContext.set(TENANT);
        newGateStation(TENANT, "GS-C01", "青云门站");
        newRegulator(TENANT, "RS-C01", "绿谷小区调压箱");
        PipeSegment seg = newSegment(TENANT, "PS-C01", "青云门站", "绿谷小区");
        newUser(TENANT, "U-C01", "绿谷小区1栋101");
        newUser(TENANT, "U-C02", "绿谷小区2栋202");

        MaintCreateRequest req = new MaintCreateRequest();
        req.setOrderNo("MO-CONC-PUB");
        req.setPlanStartAt(LocalDateTime.now().plusDays(1));
        req.setLeaderName("施工队长王");
        req.setScopeRemark("并发发布用检修单");
        req.setSegmentIds(List.of(seg.getId()));
        MaintenanceOrder order = maintenanceService.create(req);
        assertThat(impactRepository.findByOrderIdOrderByIdAsc(order.getId())).hasSize(4);
        return order.getId();
    }

    /** 推进到「待复气」：发布 → 停气 → 完工 → 全部验收 → 全部用户确认 */
    private Long prepareRestoringOrder() {
        Long orderId = prepareDraftOrder();
        TenantContext.set(TENANT);
        MaintenanceOrder o = orderRepository.findScopedById(orderId).orElseThrow();
        o = maintenanceService.publish(orderId, action("调度甲", o.getVersion(), null));
        o = maintenanceService.stopGas(orderId, action("调度甲", o.getVersion(), null));
        o = maintenanceService.finishWork(orderId, action("施工队长王", o.getVersion(), null));
        for (MaintenanceSegment link : segmentLinkRepository.findByOrderIdOrderByIdAsc(orderId)) {
            AcceptRequest accept = new AcceptRequest();
            accept.setOperator("验收员赵");
            accept.setNote("验收合格");
            accept.setVersion(orderRepository.findScopedById(orderId).orElseThrow().getVersion());
            maintenanceService.accept(orderId, link.getId(), accept);
        }
        for (MaintenanceImpact impact : impactRepository.findByOrderIdOrderByIdAsc(orderId)) {
            if (Dict.IMPACT_GAS_USER.equals(impact.getObjectType())) {
                ConfirmRequest confirm = new ConfirmRequest();
                confirm.setOperator("安全员钱");
                maintenanceService.confirmUser(orderId, impact.getId(), confirm);
            }
        }
        assertThat(orderRepository.findScopedById(orderId).orElseThrow().getStatus())
                .isEqualTo(Dict.MAINT_RESTORING);
        return orderId;
    }

    /** 两名操作员同时执行同一动作：只有一人推进状态，另一人 409 */
    private List<String> runConcurrently(RunnableWithTenant task) throws Exception {
        int n = 2;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                TenantContext.set(TENANT);
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                try {
                    task.run();
                    return "OK";
                } catch (ConflictException e) {
                    return "CONFLICT";
                } catch (Exception e) {
                    return "OTHER:" + e.getClass().getSimpleName() + ":" + e.getMessage();
                }
            }));
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : futures) {
            outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }

    private interface RunnableWithTenant {
        void run();
    }
}
