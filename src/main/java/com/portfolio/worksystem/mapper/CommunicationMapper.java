package com.portfolio.worksystem.mapper;

import com.portfolio.worksystem.model.EmployeePresence;
import com.portfolio.worksystem.model.AdminNotice;
import com.portfolio.worksystem.model.NoticeComment;
import com.portfolio.worksystem.model.NoticePoll;
import com.portfolio.worksystem.model.PollOption;
import com.portfolio.worksystem.model.MessageMessage;
import com.portfolio.worksystem.model.MessageAttachment;
import com.portfolio.worksystem.model.MessageRoom;
import com.portfolio.worksystem.model.MessageRoomMember;
import com.portfolio.worksystem.model.Notification;
import com.portfolio.worksystem.model.UserAccount;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CommunicationMapper {
    int countUnreadNotifications(Long userId);
    List<Notification> findNotifications(Long userId);
    List<Notification> findNoticeFeed(Long userId);
    List<AdminNotice> findAdminNotices();
    AdminNotice findAdminNotice(Long id);
    void insertAdminNotice(AdminNotice notice);
    void updateAdminNotice(AdminNotice notice);
    void updateAdminNoticeLinkUrl(@Param("id") Long id, @Param("linkUrl") String linkUrl, @Param("updatedBy") Long updatedBy);
    void deactivateAdminNotice(@Param("id") Long id, @Param("updatedBy") Long updatedBy);
    void insertNoticeNotifications(@Param("noticeId") Long noticeId, @Param("senderUserId") Long senderUserId, @Param("body") String body, @Param("linkUrl") String linkUrl);
    void deleteNoticeNotifications(Long noticeId);

    // 투표
    void insertPoll(NoticePoll poll);
    void insertPollOption(PollOption option);
    void deletePollByNotice(Long noticeId);
    NoticePoll findPollByNotice(Long noticeId);
    List<PollOption> findPollOptions(Long pollId);
    int insertPollVote(@Param("pollId") Long pollId, @Param("optionId") Long optionId, @Param("voterToken") String voterToken);
    Long findVotedOptionId(@Param("pollId") Long pollId, @Param("voterToken") String voterToken);
    long countPollParticipants(Long pollId);
    long countOptionVotes(Long optionId);
    long countActiveUsers(@Param("includeContractors") boolean includeContractors);

    // 댓글
    void insertNoticeComment(NoticeComment comment);
    List<NoticeComment> findNoticeComments(Long noticeId);
    NoticeComment findNoticeComment(Long id);
    int deactivateNoticeComment(@Param("id") Long id);
    void insertNotification(Notification notification);
    void markNotificationRead(@Param("notificationId") Long notificationId, @Param("userId") Long userId);
    int deleteNotification(@Param("notificationId") Long notificationId, @Param("userId") Long userId);
    int markNoticeNotificationsRead(@Param("noticeId") Long noticeId, @Param("userId") Long userId);
    int markMessageRoomNotificationsRead(@Param("roomId") Long roomId, @Param("userId") Long userId);
    int markWorkMemoNotificationsRead(@Param("memoId") Long memoId, @Param("userId") Long userId);
    void markAllNotificationsRead(Long userId);

    int countUnreadMessages(Long userId);
    List<MessageRoom> findRooms(Long userId);
    MessageRoom findRoom(@Param("roomId") Long roomId, @Param("userId") Long userId);
    Long findDirectRoom(@Param("userId") Long userId, @Param("recipientUserId") Long recipientUserId);
    List<MessageRoomMember> findRoomMembers(Long roomId);
    List<Long> findRoomMemberUserIds(Long roomId);
    List<UserAccount> findInviteCandidates(@Param("roomId") Long roomId, @Param("currentUserId") Long currentUserId);
    Boolean isRoomMember(@Param("roomId") Long roomId, @Param("userId") Long userId);
    void insertRoom(MessageRoom room);
    void insertRoomMember(@Param("roomId") Long roomId, @Param("userId") Long userId);
    void deleteRoomMember(@Param("roomId") Long roomId, @Param("userId") Long userId);
    void insertMessage(MessageMessage message);
    void insertMessageAttachment(MessageAttachment attachment);
    List<MessageAttachment> findAttachmentsByRoom(Long roomId);
    MessageAttachment findAttachment(@Param("attachmentId") Long attachmentId, @Param("userId") Long userId);
    List<MessageAttachment> findExpiredAttachments();
    void deleteAttachment(Long attachmentId);
    void touchRoom(Long roomId);
    List<MessageMessage> findMessages(Long roomId);
    // 페이지네이션: 최근 limit개(DESC) / 특정 id 이전 limit개(DESC). 표시 시 호출부에서 역순 정렬.
    List<MessageMessage> findRecentMessages(@Param("roomId") Long roomId, @Param("limit") int limit);
    List<MessageMessage> findMessagesBefore(@Param("roomId") Long roomId, @Param("beforeId") Long beforeId, @Param("limit") int limit);
    boolean existsMessageBefore(@Param("roomId") Long roomId, @Param("beforeId") Long beforeId);
    Long findLastMessageId(Long roomId);
    int markRoomRead(@Param("roomId") Long roomId, @Param("userId") Long userId, @Param("messageId") Long messageId);
    List<Long> findRoomRecipientIds(@Param("roomId") Long roomId, @Param("senderUserId") Long senderUserId);
    Boolean isRoomMuted(@Param("roomId") Long roomId, @Param("userId") Long userId);
    void upsertRoomSettings(@Param("roomId") Long roomId,
                            @Param("userId") Long userId,
                            @Param("customTitle") String customTitle,
                            @Param("pinned") Boolean pinned,
                            @Param("muted") Boolean muted,
                            @Param("opacityPercent") Integer opacityPercent);
    List<Long> findSystemAccountUserIds();

    List<EmployeePresence> findEmployeePresence(@Param("today") LocalDate today);
}
