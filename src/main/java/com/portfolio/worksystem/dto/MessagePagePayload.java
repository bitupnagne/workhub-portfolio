package com.portfolio.worksystem.dto;

import com.portfolio.worksystem.model.MessageMessage;
import java.util.List;

/** 과거 메시지 페이지(위로 스크롤 로드) 응답. */
public record MessagePagePayload(
        List<MessageMessage> messages,
        boolean hasMore
) {
}
