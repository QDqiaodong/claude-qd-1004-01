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

import com.citygas.network.common.BizException;
import com.citygas.network.common.Dict;
import com.citygas.network.dto.MaintenanceAcceptRequest;
import com.citygas.network.dto.MaintenanceActionRequest;
import com.citygas.network.dto.MaintenanceDetailVO;
import com.citygas.network.dto.MaintenanceImpactVO;
import com.citygas.network.dto.MaintenanceOrderRequest;
import com.citygas.network.dto.MaintenanceSafetyRequest;
import com.citygas.network.entity.GasUser;
import com.citygas.network.entity.GateStation;
import com.citygas.network.entity.MaintenanceOrder;
import com.citygas.network.entity.PipeSegment;
import com.citygas.network.entity.RegulatorStation;
import com.citygas.network.repository.MaintenanceOrderRepository;
import com.citygas.network.service.MaintenanceService;
import com.citygas.network.tenant.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「发布 / 复气联动」并发测试。
 *
 * <p>类级 {@link Commit}：两名操作员在各自独立线程/独立事务里同时发布（或复气放行）
 * 同一张检修单。行级悲观锁把两人串行化——先到者推进状态，后到者拿锁后读到已推进，
 * 直接返回首次结果：冻结标记只置一次、发布人/复气人保持先到者、影响清单无半冻结行。</p>
 */
@Commit
class ConcurrentMaintenanceTest extends BaseIntegrationTest {

    private static final String TENANT = "A片区";

    @Autowired
    private MaintenanceService maintenanceService;
    @Autowired
    private MaintenanceOrderRepository orderRepository;

    @Test
    void concurrent_publish_only_one_advances_other_gets_first_result() throws Exception {
        Long orderId = prepareDraft();

        List<Outcome> outcomes = runParallel(
                () -> maintenanceService.publish(orderId, req("发布人甲", "PUB-A")),
                () -> maintenanceService.publish(orderId, req("发布人乙", "PUB-B")));

        // 两人都拿到成功结果（后到者拿到的是先到者冻结后的首次结果），无异常、无半冻结
        assertThat(outcomes).allMatch(Outcome::ok);
        assertThat(outcomes).extracting(Outcome::status)
                .containsOnly(Dict.MO_WAIT_CUTOFF);
        // 重复请求返回首次结果：两人的响应里发布人都是真正的先到者（同一位），不是各自自己
        assertThat(outcomes).extracting(Outcome::operator)
                .containsExactlyInAnyOrder(outcomes.get(0).operator(), outcomes.get(0).operator());

        TenantContext.set(TENANT);
        MaintenanceOrder after = orderRepository.findById(orderId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Dict.MO_WAIT_CUTOFF);
        assertThat(after.getFrozen()).isTrue();
        assertThat(after.getVersion()).as("冻结只发生一次（版本只递增一次）").isEqualTo(1L);
        assertThat(after.getPublishedBy()).isIn("发布人甲", "发布人乙");
    }

    @Test
    void concurrent_restore_only_one_completes_other_gets_first_result() throws Exception {
        Long orderId = prepareWaitRestore();

        List<Outcome> outcomes = runParallel(
                () -> maintenanceService.restore(orderId, req("调度员甲", "RES-A")),
                () -> maintenanceService.restore(orderId, req("调度员乙", "RES-B")));

        assertThat(outcomes).allMatch(Outcome::ok);
        assertThat(outcomes).extracting(Outcome::status).containsOnly(Dict.MO_DONE);
        assertThat(outcomes).extracting(Outcome::operator)
                .containsExactlyInAnyOrder(outcomes.get(0).operator(), outcomes.get(0).operator());

        TenantContext.set(TENANT);
        MaintenanceOrder after = orderRepository.findById(orderId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Dict.MO_DONE);
        assertThat(after.getRestoredBy()).isIn("调度员甲", "调度员乙");
    }

    @Test
    void failed_publish_under_contention_leaves_no_half_frozen_snapshot() throws Exception {
        // 停用关键对象 → 两人并发发布都必须失败，冻结标记/状态/清单/幂等表无任何半成品
        useTenant(TENANT);
        newGate(TENANT, "GS-C01", "城北门站");
        RegulatorStation regulator = newRegulator(TENANT, "RS-C01");
        regulator.setStationName("城北调压站");
        regulator.setStatus("停用");
        regulator = regulatorRepository.saveAndFlush(regulator);
        PipeSegment seg = newSegment(TENANT, "PS-C01");
        seg.setStartPoint("城北门站");
        seg.setEndPoint("城北调压站");
        segmentRepository.saveAndFlush(seg);
        Long orderId = maintenanceService.create(request("JX-C-FAIL", List.of(seg.getId()))).getId();

        List<Outcome> outcomes = runParallel(
                () -> maintenanceService.publish(orderId, req("发布人甲", "PUB-C1")),
                () -> maintenanceService.publish(orderId, req("发布人乙", "PUB-C2")));

        // 串行化后两人都应被拦截（先到者回滚释放锁，后到者同样读到停用对象）
        assertThat(outcomes).allMatch(o -> !o.ok() && o.error().contains("发布被拦截"));

        TenantContext.set(TENANT);
        MaintenanceOrder after = orderRepository.findById(orderId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Dict.MO_DRAFT);
        assertThat(after.getFrozen()).isFalse();
        assertThat(after.getPublishedAt()).isNull();
        Long rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM maintenance_impact WHERE order_id = ?", Long.class, orderId);
        assertThat(rows).as("影响清单仍完整（管段+门站+停用调压站），无半冻结").isEqualTo(3);
        Long idemRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM maintenance_idem", Long.class);
        assertThat(idemRows).as("失败请求不写幂等记录").isZero();
    }

    // ---------------- 夹具 ----------------

    private Long prepareDraft() {
        useTenant(TENANT);
        newGate(TENANT, "GS-C11", "城东门站");
        RegulatorStation regulator = newRegulator(TENANT, "RS-C11");
        regulator.setStationName("兴业路调压站");
        regulatorRepository.saveAndFlush(regulator);
        PipeSegment seg1 = newSegment(TENANT, "PS-C11");
        seg1.setStartPoint("城东门站");
        seg1.setEndPoint("兴业路调压站");
        segmentRepository.saveAndFlush(seg1);
        return maintenanceService.create(request("JX-C-PUB", List.of(seg1.getId()))).getId();
    }

    private Long prepareWaitRestore() {
        useTenant(TENANT);
        newGate(TENANT, "GS-C21", "城南门站");
        RegulatorStation regulator = newRegulator(TENANT, "RS-C21");
        regulator.setStationName("学院路调压站");
        regulatorRepository.saveAndFlush(regulator);
        PipeSegment seg = newSegment(TENANT, "PS-C21");
        seg.setStartPoint("城南门站");
        seg.setEndPoint("学院路调压站");
        segmentRepository.saveAndFlush(seg);
        GasUser user = newUser(TENANT, "U-C21");
        user.setAddress("学院路调压站旁5号");
        gasUserRepository.saveAndFlush(user);

        Long id = maintenanceService.create(request("JX-C-RES", List.of(seg.getId()))).getId();
        maintenanceService.publish(id, req("发布人甲", "PUB-READY"));
        maintenanceService.cutoff(id, req("调度员甲", "CUT-READY"));
        maintenanceService.startWork(id, req("施工员甲", "START-READY"));
        maintenanceService.finishWork(id, req("施工员甲", "FIN-READY"));

        MaintenanceDetailVO d = maintenanceService.detail(id);
        MaintenanceImpactVO segVo = d.getImpacts().stream()
                .filter(i -> Dict.TARGET_PIPE_SEGMENT.equals(i.getTargetType()))
                .findFirst().orElseThrow();
        MaintenanceAcceptRequest pass = new MaintenanceAcceptRequest();
        pass.setOperator("验收员甲");
        pass.setPassed(true);
        maintenanceService.accept(id, segVo.getId(), pass);
        maintenanceService.requestRestore(id, req("验收员甲", "REQ-READY"));

        MaintenanceImpactVO userVo = maintenanceService.detail(id).getImpacts().stream()
                .filter(i -> Dict.TARGET_GAS_USER.equals(i.getTargetType())).findFirst().orElseThrow();
        MaintenanceSafetyRequest safety = new MaintenanceSafetyRequest();
        safety.setOperator("安检员甲");
        maintenanceService.confirmSafety(id, userVo.getId(), safety);
        return id;
    }

    private MaintenanceOrderRequest request(String no, List<Long> segIds) {
        MaintenanceOrderRequest req = new MaintenanceOrderRequest();
        req.setOrderNo(no);
        req.setPlanStartAt(LocalDateTime.now().plusDays(1));
        req.setOwnerName("施工负责人甲");
        req.setRemark("并发测试");
        req.setSegmentIds(segIds);
        return req;
    }

    private MaintenanceActionRequest req(String operator, String requestId) {
        MaintenanceActionRequest r = new MaintenanceActionRequest();
        r.setOperator(operator);
        r.setRequestId(requestId);
        return r;
    }

    // ---------------- 并行执行器 ----------------

    private List<Outcome> runParallel(java.util.function.Supplier<MaintenanceOrder> a,
                                      java.util.function.Supplier<MaintenanceOrder> b)
            throws Exception {
        int n = 2;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        futures.add(pool.submit(() -> runOne(a, ready, go)));
        futures.add(pool.submit(() -> runOne(b, ready, go)));
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> f : futures) {
            outcomes.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }

    private Outcome runOne(java.util.function.Supplier<MaintenanceOrder> job,
                           CountDownLatch ready, CountDownLatch go) {
        TenantContext.set(TENANT);
        ready.countDown();
        try {
            go.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            MaintenanceOrder o = job.get();
            return new Outcome(true, o.getStatus(),
                    o.getPublishedBy() != null ? o.getPublishedBy() : o.getRestoredBy(), null);
        } catch (BizException e) {
            return new Outcome(false, null, null, e.getMessage() == null ? "" : e.getMessage());
        } catch (Exception e) {
            return new Outcome(false, null, null,
                    "OTHER:" + e.getClass().getSimpleName() + ":" + e.getMessage());
        }
    }

    private record Outcome(boolean ok, String status, String operator, String error) {
    }
}
