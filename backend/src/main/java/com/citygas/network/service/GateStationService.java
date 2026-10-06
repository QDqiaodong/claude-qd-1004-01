package com.citygas.network.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.citygas.network.common.BizException;
import com.citygas.network.common.Dict;
import com.citygas.network.entity.GateStation;
import com.citygas.network.repository.GateStationRepository;

/**
 * 门站业务。
 *
 * <p>核心规则：① 投运（转「运行」）时进站压力不得低于 {@code MIN_INLET_PRESSURE}；
 * ② 加臭剂单耗不得低于 {@code MIN_ODORANT}；③ 状态流转按词典走。</p>
 */
@Service
public class GateStationService {

    private final GateStationRepository gateRepository;

    public GateStationService(GateStationRepository gateRepository) {
        this.gateRepository = gateRepository;
    }

    public List<GateStation> list(String status) {
        return (status != null && !status.isBlank())
                ? gateRepository.findByStatus(status) : gateRepository.findAll();
    }

    public GateStation get(Long id) {
        return gateRepository.findById(id)
                .orElseThrow(() -> new BizException("门站不存在：" + id));
    }

    @Transactional
    public GateStation create(GateStation input) {
        if (input.getStationCode() == null || input.getStationCode().isBlank()) {
            throw new BizException("门站编号不能为空");
        }
        gateRepository.findByStationCode(input.getStationCode().trim()).ifPresent(g -> {
            throw new BizException("门站编号已存在：" + input.getStationCode());
        });
        if (input.getInletPressureMpa() == null || input.getInletPressureMpa().signum() <= 0) {
            throw new BizException("进站压力必须大于 0");
        }
        if (input.getOutletPressureMpa() == null || input.getOutletPressureMpa().signum() <= 0) {
            throw new BizException("出站压力必须大于 0");
        }
        if (input.getOutletPressureMpa().compareTo(input.getInletPressureMpa()) > 0) {
            throw new BizException("出站压力不能高于进站压力");
        }
        GateStation g = new GateStation();
        g.setStationCode(input.getStationCode().trim());
        g.setStationName(input.getStationName() == null ? input.getStationCode() : input.getStationName());
        g.setInletPressureMpa(input.getInletPressureMpa());
        g.setOutletPressureMpa(input.getOutletPressureMpa());
        g.setDailyFlowM3(input.getDailyFlowM3() == null ? BigDecimal.ZERO : input.getDailyFlowM3());
        g.setOdorantMg(input.getOdorantMg() == null ? BigDecimal.ZERO : input.getOdorantMg());
        g.setStatus("检修");
        return gateRepository.save(g);
    }

    @Transactional
    public GateStation update(Long id, GateStation input) {
        GateStation g = get(id);
        if (input.getInletPressureMpa() != null) {
            if (input.getInletPressureMpa().signum() <= 0) {
                throw new BizException("进站压力必须大于 0");
            }
            g.setInletPressureMpa(input.getInletPressureMpa());
        }
        if (input.getOutletPressureMpa() != null) {
            if (input.getOutletPressureMpa().signum() <= 0) {
                throw new BizException("出站压力必须大于 0");
            }
            if (input.getOutletPressureMpa().compareTo(g.getInletPressureMpa()) > 0) {
                throw new BizException("出站压力不能高于进站压力 " + g.getInletPressureMpa());
            }
            g.setOutletPressureMpa(input.getOutletPressureMpa());
        }
        if (input.getDailyFlowM3() != null) {
            g.setDailyFlowM3(input.getDailyFlowM3());
        }
        if (input.getOdorantMg() != null) {
            g.setOdorantMg(input.getOdorantMg());
        }
        return gateRepository.save(g);
    }

    /**
     * 状态流转。转「运行」即投运，要过进气压力与加臭两项校验。
     */
    @Transactional
    public GateStation transfer(Long id, String target) {
        GateStation g = get(id);
        if (!Dict.isValidGateStatus(target)) {
            throw new BizException("门站状态不合法：" + target);
        }
        if (!Dict.canTransferGate(g.getStatus(), target)) {
            throw new BizException("不允许从「" + g.getStatus() + "」转为「" + target + "」");
        }
        if ("运行".equals(target)) {
            // 规则①
            if (g.getInletPressureMpa() != null
                    && g.getInletPressureMpa().compareTo(Dict.MIN_INLET_PRESSURE) < 0) {
                throw new BizException("进站压力 " + g.getInletPressureMpa()
                        + " MPa 低于下限 " + Dict.MIN_INLET_PRESSURE + " MPa，不能投运");
            }
            // 规则②
            if (g.getOdorantMg() != null && g.getOdorantMg().compareTo(Dict.MIN_ODORANT) < 0) {
                throw new BizException("加臭剂单耗 " + g.getOdorantMg()
                        + " mg/m³ 低于下限 " + Dict.MIN_ODORANT + "，不能投运");
            }
        }
        g.setStatus(target);
        return gateRepository.save(g);
    }
}
