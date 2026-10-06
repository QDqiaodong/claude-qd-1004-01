package com.citygas.network.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import lombok.Getter;
import lombok.Setter;

/** 统一分页结构：列表接口固定返回 {items,page,size,total,totalPages} */
@Getter
@Setter
public class PageResponse<T> {

    private List<T> items;
    private int page;
    private int size;
    private long total;
    private int totalPages;

    public static <T> PageResponse<T> of(Page<T> p) {
        PageResponse<T> r = new PageResponse<>();
        r.items = p.getContent();
        r.page = p.getNumber();
        r.size = p.getSize();
        r.total = p.getTotalElements();
        r.totalPages = p.getTotalPages();
        return r;
    }
}
