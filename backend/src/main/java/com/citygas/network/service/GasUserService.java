package com.citygas.network.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.citygas.network.common.BizException;
import com.citygas.network.common.Dict;
import com.citygas.network.entity.GasUser;
import com.citygas.network.repository.GasUserRepository;

/**
 * 用户抄表与入户安检业务。
 *
 * <p>核心规则：① 本期读数不得小于上期（表不能倒走）；② 单次用量超过
 * {@code MAX_READING_GAP} 判为可疑抄表；③ 安检结论为「隐患停用」直接停用该户；
 * ④ 停用户不允许抄表。</p>
 */
@Service
public class GasUserService {

    private final GasUserRepository userRepository;

    public GasUserService(GasUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public List<GasUser> list(String status) {
        return (status != null && !status.isBlank())
                ? userRepository.findByStatus(status) : userRepository.findAll();
    }

    public GasUser get(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new BizException("用户不存在：" + id));
    }

    @Transactional
    public GasUser create(GasUser input) {
        if (input.getUserNo() == null || input.getUserNo().isBlank()) {
            throw new BizException("用户编号不能为空");
        }
        userRepository.findByUserNo(input.getUserNo().trim()).ifPresent(u -> {
            throw new BizException("用户编号已存在：" + input.getUserNo());
        });
        if (input.getUserName() == null || input.getUserName().isBlank()) {
            throw new BizException("用户名不能为空");
        }
        if (input.getMeterNo() == null || input.getMeterNo().isBlank()) {
            throw new BizException("表号不能为空");
        }
        GasUser u = new GasUser();
        u.setUserNo(input.getUserNo().trim());
        u.setUserName(input.getUserName());
        u.setAddress(input.getAddress());
        u.setMeterNo(input.getMeterNo());
        u.setLastReading(input.getLastReading() == null ? BigDecimal.ZERO : input.getLastReading());
        u.setCurrentReading(input.getCurrentReading() == null
                ? u.getLastReading() : input.getCurrentReading());
        if (u.getCurrentReading().compareTo(u.getLastReading()) < 0) {
            throw new BizException("本期读数不能小于上期读数");
        }
        u.setStatus("正常");
        return userRepository.save(u);
    }

    /**
     * 抄表：把上期读数滚到本期，再写入新的本期读数。
     */
    @Transactional
    public GasUser readMeter(Long id, BigDecimal reading) {
        GasUser u = get(id);
        // 规则④
        if ("停用".equals(u.getStatus())) {
            throw new BizException("用户已停用，不允许抄表");
        }
        if (reading == null) {
            throw new BizException("本期读数不能为空");
        }
        // 规则①
        if (reading.compareTo(u.getCurrentReading()) < 0) {
            throw new BizException("本期读数 " + reading + " 小于上期读数 "
                    + u.getCurrentReading() + "，燃气表不能倒走");
        }
        BigDecimal usage = reading.subtract(u.getCurrentReading());
        // 规则②
        if (usage.compareTo(Dict.MAX_READING_GAP) > 0) {
            throw new BizException("本次用量 " + usage + " m³ 超过单次上限 "
                    + Dict.MAX_READING_GAP + " m³，判为可疑抄表，请复核");
        }
        u.setLastReading(u.getCurrentReading());
        u.setCurrentReading(reading);
        return userRepository.save(u);
    }

    /**
     * 入户安检。
     */
    @Transactional
    public GasUser check(Long id, String result) {
        GasUser u = get(id);
        if (!Dict.isValidCheckResult(result)) {
            throw new BizException("安检结论不合法：" + result);
        }
        u.setCheckResult(result);
        u.setLastCheckAt(LocalDateTime.now());
        // 规则③：隐患停用直接停用户
        if ("隐患停用".equals(result)) {
            u.setStatus("停用");
        }
        return userRepository.save(u);
    }

    @Transactional
    public GasUser transfer(Long id, String target) {
        GasUser u = get(id);
        if (!Dict.isValidUserStatus(target)) {
            throw new BizException("用户状态不合法：" + target);
        }
        u.setStatus(target);
        return userRepository.save(u);
    }
}
