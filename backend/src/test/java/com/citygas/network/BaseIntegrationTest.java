package com.citygas.network;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.citygas.network.dto.BatchCreateRequest;
import com.citygas.network.entity.GasUser;
import com.citygas.network.entity.GateStation;
import com.citygas.network.entity.PipeSegment;
import com.citygas.network.entity.RegulatorStation;
import com.citygas.network.repository.GasUserRepository;
import com.citygas.network.repository.GateStationRepository;
import com.citygas.network.repository.PipeSegmentRepository;
import com.citygas.network.repository.RegulatorStationRepository;
import com.citygas.network.tenant.TenantContext;

/**
 * 集成测试基类：H2(MySQL 模式) + 全量 Spring 容器。
 *
 * <p>每个用例前 TRUNCATE 全部业务表保证数据不串；切换租户时清空 JPA 一级缓存，
 * 避免同一测试里「先以 A 租户加载、再切 B 租户 findById 命中缓存」导致隔离验证失真。
 * 每个用例自己 {@link TenantContext#set(String)} 指定租户。</p>
 */
@SpringBootTest
public abstract class BaseIntegrationTest {

    @Autowired
    protected PipeSegmentRepository segmentRepository;
    @Autowired
    protected RegulatorStationRepository regulatorRepository;
    @Autowired
    protected GasUserRepository gasUserRepository;
    @Autowired
    protected GateStationRepository gateStationRepository;
    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected EntityManager entityManager;

    @BeforeEach
    void cleanDatabase() {
        TenantContext.set("A片区");
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : List.of(
                "maintenance_idem", "maintenance_impact", "maintenance_order",
                "inspect_idem", "inspect_hazard_history", "inspect_hazard",
                "inspect_batch_target", "inspect_batch",
                "gas_user", "pipe_segment", "regulator_station", "gate_station")) {
            jdbcTemplate.execute("TRUNCATE TABLE " + table);
        }
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        entityManager.clear();
    }

    protected void useTenant(String tenant) {
        TenantContext.set(tenant);
        // 关键：模拟真实请求里每次调用都是新会话——切租户后不允许命中上一个租户的一级缓存
        entityManager.clear();
    }

    protected PipeSegment newSegment(String tenant, String code) {
        TenantContext.set(tenant);
        PipeSegment s = new PipeSegment();
        s.setSegCode(code);
        s.setStartPoint("起点-" + code);
        s.setEndPoint("终点-" + code);
        s.setMaterial("钢管");
        s.setPressureLevel("中压");
        s.setLengthM(new BigDecimal("1200.00"));
        s.setStatus("正常");
        return segmentRepository.saveAndFlush(s);
    }

    protected RegulatorStation newRegulator(String tenant, String code) {
        TenantContext.set(tenant);
        RegulatorStation r = new RegulatorStation();
        r.setStationCode(code);
        r.setStationName("调压站-" + code);
        r.setStationType("区域调压站");
        r.setInPressureMpa(new BigDecimal("0.380"));
        r.setOutletPressureMpa(new BigDecimal("0.120"));
        r.setStatus("运行");
        return regulatorRepository.saveAndFlush(r);
    }

    protected GateStation newGate(String tenant, String code, String stationName) {
        TenantContext.set(tenant);
        GateStation g = new GateStation();
        g.setStationCode(code);
        g.setStationName(stationName == null ? "门站-" + code : stationName);
        g.setInletPressureMpa(new BigDecimal("1.200"));
        g.setOutletPressureMpa(new BigDecimal("0.380"));
        g.setDailyFlowM3(new BigDecimal("100000.00"));
        g.setOdorantMg(new BigDecimal("22.00"));
        g.setStatus("运行");
        return gateStationRepository.saveAndFlush(g);
    }

    protected GasUser newUser(String tenant, String userNo) {
        TenantContext.set(tenant);
        GasUser u = new GasUser();
        u.setUserNo(userNo);
        u.setUserName("用户-" + userNo);
        u.setAddress("测试地址 " + userNo);
        u.setMeterNo("M-" + userNo);
        u.setLastReading(BigDecimal.ZERO);
        u.setCurrentReading(BigDecimal.ZERO);
        u.setStatus("正常");
        return gasUserRepository.saveAndFlush(u);
    }

    protected BatchCreateRequest.TargetRef ref(String type, Long id) {
        BatchCreateRequest.TargetRef r = new BatchCreateRequest.TargetRef();
        r.setTargetType(type);
        r.setTargetId(id);
        return r;
    }

    protected BatchCreateRequest batchRequest(String batchNo, LocalDateTime planStartAt,
                                              String owner, String remark,
                                              List<BatchCreateRequest.TargetRef> targets) {
        BatchCreateRequest req = new BatchCreateRequest();
        req.setBatchNo(batchNo);
        req.setPlanStartAt(planStartAt);
        req.setOwnerName(owner);
        req.setRemark(remark);
        req.setTargets(targets);
        return req;
    }
}
