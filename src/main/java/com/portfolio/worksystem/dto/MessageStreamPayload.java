package com.portfolio.worksystem.dto;

import com.portfolio.worksystem.model.MessageMessage;
import com.portfolio.worksystem.model.MessageRoom;
import com.portfolio.worksystem.model.MessageRoomMember;
import com.portfolio.worksystem.model.UserAccount;
import java.util.List;

public record MessageStreamPayload(
        Long currentUserId,
        int unreadMessageCount,
        MessageRoom room,
        List<MessageRoomMember> members,
        List<UserAccount> inviteCandidates,
        List<MessageMessage> messages,
        boolean hasMore
) {
}
