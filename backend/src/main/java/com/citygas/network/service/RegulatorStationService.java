package com.citygas.network.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.citygas.network.common.BizException;
import com.citygas.network.common.Dict;
import com.citygas.network.entity.RegulatorStation;
import com.citygas.network.repository.RegulatorStationRepository;

/**
 * 调压站业务。
 *
 * <p>核心规则：① 出口压力不得超过 {@code MAX_OUTLET_PRESSURE}(0.4MPa)；
 * ② 距上次检验超过 6 个月不允许投运；③ 投运前出口压力必须已核定。</p>
 */
@Service
public class RegulatorStationService {

    private final RegulatorStationRepository regulatorRepository;

    public RegulatorStationService(RegulatorStationRepository regulatorRepository) {
        this.regulatorRepository = regulatorRepository;
    }

    public List<RegulatorStation> list(String status, String stationType) {
        if (stationType != null && !stationType.isBlank()) {
            return regulatorRepository.findByStationType(stationType);
        }
        return (status != null && !status.isBlank())
                ? regulatorRepository.findByStatus(status) : regulatorRepository.findAll();
    }

    public RegulatorStation get(Long id) {
        return regulatorRepository.findById(id)
                .orElseThrow(() -> new BizException("调压站不存在：" + id));
    }

    @Transactional
    public RegulatorStation create(RegulatorStation input) {
        if (input.getStationCode() == null || input.getStationCode().isBlank()) {
            throw new BizException("调压站编号不能为空");
        }
        regulatorRepository.findByStationCode(input.getStationCode().trim()).ifPresent(r -> {
            throw new BizException("调压站编号已存在：" + input.getStationCode());
        });
        if (!Dict.isValidRegulatorType(input.getStationType())) {
            throw new BizException("调压站类型不合法：" + input.getStationType());
        }
        if (input.getInPressureMpa() == null || input.getInPressureMpa().signum() <= 0) {
            throw new BizException("进口压力必须大于 0");
        }
        // 规则①
        if (input.getOutletPressureMpa() == null || input.getOutletPressureMpa().signum() <= 0) {
            throw new BizException("出口压力必须大于 0");
        }
        if (input.getOutletPressureMpa().compareTo(Dict.MAX_OUTLET_PRESSURE) > 0) {
            throw new BizException("出口压力 " + input.getOutletPressureMpa()
                    + " MPa 超过上限 " + Dict.MAX_OUTLET_PRESSURE + " MPa");
        }
        if (input.getOutletPressureMpa().compareTo(input.getInPressureMpa()) > 0) {
            throw new BizException("出口压力不能高于进口压力");
        }
        RegulatorStation r = new RegulatorStation();
        r.setStationCode(input.getStationCode().trim());
        r.setStationName(input.getStationName() == null ? input.getStationCode() : input.getStationName());
        r.setStationType(input.getStationType());
        r.setInPressureMpa(input.getInPressureMpa());
        r.setOutletPressureMpa(input.getOutletPressureMpa());
        r.setStatus("待检");
        r.setLastInspectAt(LocalDateTime.now());
        return regulatorRepository.save(r);
    }

    /** 调整出口压力 */
    @Transactional
    public RegulatorStation setOutlet(Long id, BigDecimal outletMpa) {
        RegulatorStation r = get(id);
        if (outletMpa == null || outletMpa.signum() <= 0) {
            throw new BizException("出口压力必须大于 0");
        }
        if (outletMpa.compareTo(Dict.MAX_OUTLET_PRESSURE) > 0) {
            throw new BizException("出口压力 " + outletMpa + " MPa 超过上限 "
                    + Dict.MAX_OUTLET_PRESSURE + " MPa");
        }
        if (outletMpa.compareTo(r.getInPressureMpa()) > 0) {
            throw new BizException("出口压力不能高于进口压力 " + r.getInPressureMpa());
        }
        r.setOutletPressureMpa(outletMpa);
        return regulatorRepository.save(r);
    }

    /** 定期检验，刷新检验时间 */
    @Transactional
    public RegulatorStation inspect(Long id) {
        RegulatorStation r = get(id);
        r.setLastInspectAt(LocalDateTime.now());
        return regulatorRepository.save(r);
    }

    @Transactional
    public RegulatorStation transfer(Long id, String target) {
        RegulatorStation r = get(id);
        if (!Dict.isValidRegulatorStatus(target)) {
            throw new BizException("调压站状态不合法：" + target);
        }
        if (!Dict.canTransferRegulator(r.getStatus(), target)) {
            throw new BizException("不允许从「" + r.getStatus() + "」转为「" + target + "」");
        }
        if ("运行".equals(target)) {
            // 规则①
            if (r.getOutletPressureMpa() != null
                    && r.getOutletPressureMpa().compareTo(Dict.MAX_OUTLET_PRESSURE) > 0) {
                throw new BizException("出口压力 " + r.getOutletPressureMpa()
                        + " MPa 超压，不允许投运");
            }
            // 规则②
            if (r.isInspectOverdue()) {
                throw new BizException("距上次检验已超 "
                        + Dict.INSPECT_PERIOD_MONTHS + " 个月，请先检验再投运");
            }
        }
        r.setStatus(target);
        return regulatorRepository.save(r);
    }
}
