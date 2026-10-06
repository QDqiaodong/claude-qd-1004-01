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
import com.citygas.network.dto.BatchCreateRequest;
import com.citygas.network.dto.InspectRequest;
import com.citygas.network.entity.InspectionBatch;
import com.citygas.network.entity.InspectionTarget;
import com.citygas.network.entity.PipeSegment;
import com.citygas.network.repository.InspectionBatchRepository;
import com.citygas.network.repository.InspectionTargetRepository;
import com.citygas.network.service.InspectionService;
import com.citygas.network.tenant.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「隐患复核收口」并发关闭测试。
 *
 * <p>类级 {@link Commit}：两名操作员各自在独立线程/独立事务里关闭同一批次，
 * 悲观行锁把两人串行化——先到者成功，后到者拿到锁后在事务内复查到「已关闭」，
 * 必须收到明确冲突（409），且关闭人/关闭时间保持先到者的结果不被覆盖。</p>
 */
@Commit
class ConcurrentCloseTest extends BaseIntegrationTest {

    private static final String TENANT = "A片区";

    @Autowired
    private InspectionService inspectionService;
    @Autowired
    private InspectionTargetRepository targetRepository;
    @Autowired
    private InspectionBatchRepository batchRepository;

    @Test
    void concurrent_close_should_only_one_win_and_other_gets_conflict() throws Exception {
        Long batchId = prepareReviewingBatch();

        int n = 2;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (String operator : List.of("操作员甲", "操作员乙")) {
            futures.add(pool.submit(() -> tryClose(batchId, operator, ready, go)));
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();

        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : futures) {
            outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        long wins = outcomes.stream().filter("OK"::equals).count();
        long conflicts = outcomes.stream().filter("CONFLICT"::equals).count();
        assertThat(wins).as("恰好一人关闭成功").isEqualTo(1);
        assertThat(conflicts).as("另一人收到明确冲突").isEqualTo(1);

        TenantContext.set(TENANT);
        InspectionBatch after = batchRepository.findById(batchId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Dict.BATCH_CLOSED);
        assertThat(after.getClosedBy()).isIn("操作员甲", "操作员乙");
    }

    private Long prepareReviewingBatch() {
        TenantContext.set(TENANT);
        PipeSegment seg = newSegment(TENANT, "PS-CONC-01");
        BatchCreateRequest req = batchRequest("BATCH-CONC-CLOSE", LocalDateTime.now().plusDays(1),
                "张工", "并发关闭用批次",
                List.of(ref(Dict.TARGET_PIPE_SEGMENT, seg.getId())));
        InspectionBatch batch = inspectionService.create(req);
        inspectionService.start(batch.getId(), "巡检员甲");

        InspectionTarget link = targetRepository.findByBatchIdOrderByIdAsc(batch.getId()).get(0);
        InspectRequest inspect = new InspectRequest();
        inspect.setResult("正常");
        inspect.setOperator("巡检员甲");
        inspectionService.inspect(batch.getId(), link.getId(), inspect);
        inspectionService.requestReview(batch.getId(), "巡检员甲");

        assertThat(batchRepository.findById(batch.getId()).orElseThrow().getStatus())
                .isEqualTo(Dict.BATCH_REVIEWING);
        return batch.getId();
    }

    private String tryClose(Long batchId, String operator, CountDownLatch ready, CountDownLatch go) {
        TenantContext.set(TENANT);
        ready.countDown();
        try {
            go.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            inspectionService.close(batchId, operator);
            return "OK";
        } catch (ConflictException e) {
            return "CONFLICT";
        } catch (Exception e) {
            return "OTHER:" + e.getClass().getSimpleName() + ":" + e.getMessage();
        }
    }
}
