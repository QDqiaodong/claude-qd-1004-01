package com.citygas.network.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.citygas.network.common.BizException;
import com.citygas.network.common.Dict;
import com.citygas.network.entity.PipeSegment;
import com.citygas.network.repository.PipeSegmentRepository;

/**
 * 管网管段业务。
 *
 * <p>核心规则：① 长度必须大于 0，管材与压力级制必须合法；
 * ② 报废管段不允许再登记巡检；③ 巡检时若发现老旧铸铁管则顺带置为待修。</p>
 */
@Service
public class PipeSegmentService {

    private final PipeSegmentRepository segmentRepository;

    public PipeSegmentService(PipeSegmentRepository segmentRepository) {
        this.segmentRepository = segmentRepository;
    }

    public List<PipeSegment> list(String status, String pressureLevel) {
        if (pressureLevel != null && !pressureLevel.isBlank()) {
            return segmentRepository.findByPressureLevel(pressureLevel);
        }
        return (status != null && !status.isBlank())
                ? segmentRepository.findByStatus(status) : segmentRepository.findAll();
    }

    public PipeSegment get(Long id) {
        return segmentRepository.findById(id)
                .orElseThrow(() -> new BizException("管段不存在：" + id));
    }

    @Transactional
    public PipeSegment create(PipeSegment input) {
        if (input.getSegCode() == null || input.getSegCode().isBlank()) {
            throw new BizException("管段编号不能为空");
        }
        segmentRepository.findBySegCode(input.getSegCode().trim()).ifPresent(s -> {
            throw new BizException("管段编号已存在：" + input.getSegCode());
        });
        if (!Dict.isValidMaterial(input.getMaterial())) {
            throw new BizException("管材不合法：" + input.getMaterial());
        }
        if (!Dict.isValidPressureLevel(input.getPressureLevel())) {
            throw new BizException("压力级制不合法：" + input.getPressureLevel());
        }
        // 规则①
        if (input.getLengthM() == null || input.getLengthM().signum() <= 0) {
            throw new BizException("管段长度必须大于 0");
        }
        PipeSegment s = new PipeSegment();
        s.setSegCode(input.getSegCode().trim());
        s.setStartPoint(input.getStartPoint());
        s.setEndPoint(input.getEndPoint());
        s.setMaterial(input.getMaterial());
        s.setPressureLevel(input.getPressureLevel());
        s.setLengthM(input.getLengthM());
        s.setBuriedYear(input.getBuriedYear());
        s.setStatus(input.getStatus() == null || input.getStatus().isBlank() ? "正常" : input.getStatus());
        if (!Dict.isValidSegmentStatus(s.getStatus())) {
            throw new BizException("管段状态不合法：" + s.getStatus());
        }
        return segmentRepository.save(s);
    }

    /** 登记巡检 */
    @Transactional
    public PipeSegment patrol(Long id) {
        PipeSegment s = get(id);
        // 规则②
        if ("报废".equals(s.getStatus())) {
            throw new BizException("管段已报废，不再安排巡检");
        }
        s.setLastPatrolAt(LocalDateTime.now());
        // 规则③：巡到老旧铸铁管，顺带置为待修
        if (s.isAgedCastIron() && !"待修".equals(s.getStatus())) {
            s.setStatus("待修");
        }
        return segmentRepository.save(s);
    }

    @Transactional
    public PipeSegment transfer(Long id, String target) {
        PipeSegment s = get(id);
        if (!Dict.isValidSegmentStatus(target)) {
            throw new BizException("管段状态不合法：" + target);
        }
        s.setStatus(target);
        return segmentRepository.save(s);
    }
}
