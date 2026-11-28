package com.portfolio.worksystem.model;

import java.util.List;
import lombok.Getter;

/** 재사용 페이지네이션 메타. 목록 자체는 별도 모델 속성으로 전달하고 이 객체는 페이지 메타만 담는다. */
@Getter
public class PageInfo {
    private final int page;
    private final int size;
    private final long total;
    private final int totalPages;
    private final int from;
    private final int to;

    public PageInfo(int page, int size, long total) {
        int safeSize = size < 1 ? 20 : size;
        int pages = (int) Math.max(1, Math.ceil((double) total / safeSize));
        int safePage = Math.min(Math.max(1, page), pages);
        this.size = safeSize;
        this.total = total;
        this.totalPages = pages;
        this.page = safePage;
        this.from = total == 0 ? 0 : (safePage - 1) * safeSize + 1;
        this.to = (int) Math.min(total, (long) safePage * safeSize);
    }

    public boolean isHasPrev() {
        return page > 1;
    }

    public boolean isHasNext() {
        return page < totalPages;
    }

    public int getOffset() {
        return (page - 1) * size;
    }

    /** 페이지 번호 윈도우(현재 기준 ±2, 최대 5개). */
    public List<Integer> getWindow() {
        int start = Math.max(1, page - 2);
        int end = Math.min(totalPages, start + 4);
        start = Math.max(1, end - 4);
        java.util.ArrayList<Integer> list = new java.util.ArrayList<>();
        for (int i = start; i <= end; i++) {
            list.add(i);
        }
        return list;
    }

    public static <T> List<T> slice(List<T> all, PageInfo info) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        int fromIdx = Math.min(all.size(), (info.page - 1) * info.size);
        int toIdx = Math.min(all.size(), fromIdx + info.size);
        return all.subList(fromIdx, toIdx);
    }
}
