package com.portfolio.worksystem.model;

public enum ApprovalDocumentType {
    LEAVE("연차", "연차신청서"),
    LEAVE_CANCEL("연차 취소", "연차취소신청서"),
    TRIP("출장", "출장신청서"),
    /** [UX47] 출장보고서 — 결재 완료된 출장(TRIP)에 세트로 작성, 별도 결재. */
    TRIP_REPORT("출장보고", "출장보고서"),
    FLEX_TIME("시차출근", "시차출근신청서"),
    ATTENDANCE_CORRECTION("근태정정", "근태정정신청서"),
    /** [B7] 휴직 — 본인이 신청하고 결재를 거쳐 재직상태가 바뀐다. */
    LOA("휴직", "휴직신청서");

    private final String label;
    private final String documentTitle;

    ApprovalDocumentType(String label, String documentTitle) {
        this.label = label;
        this.documentTitle = documentTitle;
    }

    public String getLabel() {
        return label;
    }

    public String getDocumentTitle() {
        return documentTitle;
    }
}
