package com.portfolio.worksystem.service;

import com.portfolio.worksystem.dto.MessageForm;
import com.portfolio.worksystem.dto.MessageRoomForm;
import com.portfolio.worksystem.dto.MessageRoomSettingsForm;
import com.portfolio.worksystem.dto.NoticeForm;
import com.portfolio.worksystem.dto.PollView;
import com.portfolio.worksystem.dto.OrgTreeNode;
import com.portfolio.worksystem.dto.RightPanelGroup;
import com.portfolio.worksystem.dto.RightPanelPayload;
import com.portfolio.worksystem.mapper.CommunicationMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.AdminNotice;
import com.portfolio.worksystem.model.EmployeePresence;
import com.portfolio.worksystem.model.OrganizationNode;
import com.portfolio.worksystem.model.Team;
import com.portfolio.worksystem.model.MessageMessage;
import com.portfolio.worksystem.model.MessageAttachment;
import com.portfolio.worksystem.model.MessageRoom;
import com.portfolio.worksystem.model.MessageRoomMember;
import com.portfolio.worksystem.model.NoticeComment;
import com.portfolio.worksystem.model.NoticePoll;
import com.portfolio.worksystem.model.Notification;
import com.portfolio.worksystem.model.PollOption;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.util.NoticeContentUtils;
import com.portfolio.worksystem.websocket.RealtimeHub;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@RequiredArgsConstructor
@Service
@lombok.extern.slf4j.Slf4j
public class CommunicationService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.portfolio.worksystem.config.ReleaseFeatures releaseFeatures = new com.portfolio.worksystem.config.ReleaseFeatures();
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private static final long MAX_MESSAGE_ATTACHMENT_BYTES = 100L * 1024L * 1024L;
    private static final long MESSAGE_ATTACHMENT_TTL_DAYS = 30L;

    private final CommunicationMapper communicationMapper;
    private final UserMapper userMapper;
    private final RealtimeHub realtimeHub;
    private final OrganizationService organizationService;
    private final AnonymityService anonymityService;
    private final PresenceCacheService presenceCacheService;
    private final SystemSettingsService systemSettingsService;

    @Value("${app.upload.message-attachment-dir:uploads/message-attachments}")
    private String messageAttachmentDir;

    @Value("${app.portfolio.public-demo:${PORTFOLIO_PUBLIC_DEMO:false}}")
    private boolean publicDemo;

    public int unreadNotificationCount(CustomUserDetails currentUser) {
        if (currentUser == null || !releaseFeatures.isAttendanceEnabled()) return 0;
        if (releaseFeatures.isDesktopEnabled()) return communicationMapper.countUnreadNotifications(currentUser.getUserId());
        return (int) notifications(currentUser).stream().filter(Notification::isUnread).count();
    }

    public int unreadMessageCount(CustomUserDetails currentUser) {
        return currentUser == null || !releaseFeatures.isMessagingEnabled() ? 0 : communicationMapper.countUnreadMessages(currentUser.getUserId());
    }

    public List<Notification> notifications(CustomUserDetails currentUser) {
        if (!releaseFeatures.isAttendanceEnabled()) return List.of();
        return communicationMapper.findNotifications(currentUser.getUserId()).stream().filter(releaseFeatures::allowsNotification).toList();
    }

    /** 대시보드 공지사항 피드: 사용자가 받은 공지(ADMIN_NOTICE) 알림 + 투표 기간. 메시지/기타 알림 제외. */
    public List<Notification> noticeFeed(CustomUserDetails currentUser) {
        return communicationMapper.findNoticeFeed(currentUser.getUserId());
    }

    public List<AdminNotice> adminNotices(CustomUserDetails currentUser) {
        requireNoticeManager(currentUser);
        return communicationMapper.findAdminNotices();
    }

    /** 사용자용 공지 목록 — 활성 공지만(관리자 가드 없음). */
    public List<AdminNotice> activeNotices() {
        return communicationMapper.findAdminNotices().stream()
                .filter(n -> Boolean.TRUE.equals(n.getActive()))
                .toList();
    }

    public AdminNotice adminNotice(Long id, CustomUserDetails currentUser) {
        requireNoticeManager(currentUser);
        AdminNotice notice = communicationMapper.findAdminNotice(id);
        if (notice == null) {
            throw new BusinessException("공지사항을 찾을 수 없습니다.");
        }
        return notice;
    }

    @Transactional
    public AdminNotice publicNotice(Long id, CustomUserDetails currentUser) {
        AdminNotice notice = communicationMapper.findAdminNotice(id);
        if (notice == null || !Boolean.TRUE.equals(notice.getActive())) {
            throw new BusinessException("공지사항을 찾을 수 없습니다.");
        }
        if (currentUser != null && communicationMapper.markNoticeNotificationsRead(notice.getId(), currentUser.getUserId()) > 0) {
            publishPanelChanged(currentUser.getUserId(), "notice-read");
        }
        return notice;
    }

    public String noticeBodyHtml(AdminNotice notice) {
        return NoticeContentUtils.sanitizeHtml(notice == null ? null : notice.getBody());
    }

    private void savePoll(Long noticeId, NoticeForm form) {
        if (!Boolean.TRUE.equals(form.getPollEnabled())) {
            return;
        }
        NoticePoll existingPoll = communicationMapper.findPollByNotice(noticeId);
        if (existingPoll != null && communicationMapper.countPollParticipants(existingPoll.getId()) > 0
                && !StringUtils.hasText(form.getPollTitle())) {
            return;
        }
        String title = form.getPollTitle() == null ? "" : form.getPollTitle().trim();
        if (!StringUtils.hasText(title)) {
            throw new BusinessException("투표 기능을 사용할 때는 투표 제목을 입력하세요.");
        }
        if (existingPoll != null && communicationMapper.countPollParticipants(existingPoll.getId()) > 0) {
            throw new BusinessException("이미 투표가 진행되어 투표 내용을 수정할 수 없습니다.");
        }
        List<String> options = new ArrayList<>();
        if (form.getPollOptions() != null) {
            for (String o : form.getPollOptions()) {
                String v = o == null ? "" : o.trim();
                if (StringUtils.hasText(v) && !options.contains(v)) {
                    options.add(v);
                }
            }
        }
        if (options.size() < 2) {
            throw new BusinessException("투표 항목을 2개 이상 입력하세요.");
        }
        if (form.getPollStartDate() == null || form.getPollEndDate() == null) {
            throw new BusinessException("투표 시작/종료 일시를 입력하세요.");
        }
        if (form.getPollEndDate().isBefore(form.getPollStartDate())) {
            throw new BusinessException("투표 종료 일시는 시작 일시 이후여야 합니다.");
        }
        communicationMapper.deletePollByNotice(noticeId);
        NoticePoll poll = new NoticePoll();
        poll.setNoticeId(noticeId);
        poll.setTitle(title);
        poll.setStartDate(form.getPollStartDate());
        poll.setEndDate(form.getPollEndDate());
        poll.setIncludeContractors(form.isPollIncludeContractorsValue());
        communicationMapper.insertPoll(poll);
        int order = 0;
        for (String opt : options) {
            PollOption po = new PollOption();
            po.setPollId(poll.getId());
            po.setOptionText(opt);
            po.setSortOrder(order++);
            communicationMapper.insertPollOption(po);
        }
    }

    public PollView noticePoll(Long noticeId, CustomUserDetails currentUser) {
        NoticePoll poll = communicationMapper.findPollByNotice(noticeId);
        if (poll == null) {
            return null;
        }
        List<PollOption> options = communicationMapper.findPollOptions(poll.getId());
        LocalDateTime now = LocalDateTime.now(SERVICE_ZONE);
        boolean resultsVisible = now.isAfter(poll.getEndDate());
        boolean open = !now.isBefore(poll.getStartDate()) && !now.isAfter(poll.getEndDate());
        String token = anonymityService.voterToken(poll.getId(), currentUser.getUserId());
        Long votedOptionId = communicationMapper.findVotedOptionId(poll.getId(), token);
        long participants = communicationMapper.countPollParticipants(poll.getId());
        boolean includeContractors = !Boolean.FALSE.equals(poll.getIncludeContractors());
        long total = communicationMapper.countActiveUsers(includeContractors);
        if (resultsVisible) {
            for (PollOption o : options) {
                o.setVoteCount(communicationMapper.countOptionVotes(o.getId()));
            }
        }
        PollView v = new PollView();
        v.setPollId(poll.getId());
        v.setTitle(poll.getTitle());
        v.setStartDate(poll.getStartDate());
        v.setEndDate(poll.getEndDate());
        v.setIncludeContractors(includeContractors);
        v.setOptions(options);
        v.setOpen(open);
        v.setResultsVisible(resultsVisible);
        v.setVoted(votedOptionId != null);
        v.setVotedOptionId(votedOptionId);
        v.setParticipantCount(participants);
        v.setTotalEligible(total);
        v.setNonParticipantCount(Math.max(0, total - participants));
        v.setLocked(participants > 0);
        return v;
    }

    public PollView pollForEdit(Long noticeId, CustomUserDetails currentUser) {
        requireNoticeManager(currentUser);
        NoticePoll poll = communicationMapper.findPollByNotice(noticeId);
        if (poll == null) {
            return null;
        }
        long participants = communicationMapper.countPollParticipants(poll.getId());
        boolean includeContractors = !Boolean.FALSE.equals(poll.getIncludeContractors());
        long total = communicationMapper.countActiveUsers(includeContractors);
        PollView v = new PollView();
        v.setPollId(poll.getId());
        v.setTitle(poll.getTitle());
        v.setStartDate(poll.getStartDate());
        v.setEndDate(poll.getEndDate());
        v.setIncludeContractors(includeContractors);
        v.setOptions(communicationMapper.findPollOptions(poll.getId())); // 텍스트만, 표수 미설정
        v.setParticipantCount(participants);
        v.setTotalEligible(total);
        v.setNonParticipantCount(Math.max(0, total - participants));
        v.setLocked(participants > 0);
        return v;
    }

    @Transactional
    public void votePoll(CustomUserDetails currentUser, Long noticeId, Long optionId) {
        NoticePoll poll = communicationMapper.findPollByNotice(noticeId);
        if (poll == null) {
            throw new BusinessException("투표를 찾을 수 없습니다.");
        }
        LocalDateTime now = LocalDateTime.now(SERVICE_ZONE);
        if (now.isBefore(poll.getStartDate())) {
            throw new BusinessException("아직 투표 기간이 아닙니다.");
        }
        if (now.isAfter(poll.getEndDate())) {
            throw new BusinessException("투표가 종료되었습니다.");
        }
        if (Boolean.FALSE.equals(poll.getIncludeContractors())) {
            UserAccount voter = userMapper.findById(currentUser.getUserId());
            if (voter != null && Boolean.TRUE.equals(voter.getContractor())) {
                throw new BusinessException("도급 직원은 이 투표 대상에 포함되지 않습니다.");
            }
        }
        boolean valid = communicationMapper.findPollOptions(poll.getId()).stream()
                .anyMatch(o -> Objects.equals(o.getId(), optionId));
        if (!valid) {
            throw new BusinessException("투표 항목을 선택하세요.");
        }
        String token = anonymityService.voterToken(poll.getId(), currentUser.getUserId());
        if (communicationMapper.findVotedOptionId(poll.getId(), token) != null) {
            throw new BusinessException("이미 투표하셨습니다.");
        }
        communicationMapper.insertPollVote(poll.getId(), optionId, token);
    }

    public UserAccount userBrief(Long userId) {
        return userId == null ? null : userMapper.findById(userId);
    }

    public List<NoticeComment> noticeComments(Long noticeId) {
        List<NoticeComment> all = communicationMapper.findNoticeComments(noticeId);
        List<NoticeComment> roots = new ArrayList<>();
        Map<Long, NoticeComment> byId = new java.util.LinkedHashMap<>();
        for (NoticeComment c : all) {
            byId.put(c.getId(), c);
        }
        for (NoticeComment c : all) {
            if (c.getParentId() != null && byId.containsKey(c.getParentId())) {
                byId.get(c.getParentId()).getReplies().add(c);
            } else {
                roots.add(c);
            }
        }
        return roots;
    }

    @Transactional
    public void addNoticeComment(CustomUserDetails currentUser, Long noticeId, String body,
                                 boolean anonymous, Long parentId) {
        AdminNotice notice = communicationMapper.findAdminNotice(noticeId);
        if (notice == null) {
            throw new BusinessException("공지사항을 찾을 수 없습니다.");
        }
        if (parentId != null) {
            NoticeComment parent = communicationMapper.findNoticeComment(parentId);
            if (parent == null || !Objects.equals(parent.getNoticeId(), noticeId)) {
                throw new BusinessException("답글 대상 댓글을 찾을 수 없습니다.");
            }
            if (parent.getParentId() != null) {
                throw new BusinessException("답글에는 답글을 달 수 없습니다.");
            }
        }
        String text = body == null ? "" : body.trim();
        if (!StringUtils.hasText(text)) {
            throw new BusinessException("댓글 내용을 입력하세요.");
        }
        if (text.length() > 1000) {
            text = text.substring(0, 1000);
        }
        NoticeComment c = new NoticeComment();
        c.setNoticeId(noticeId);
        c.setParentId(parentId);
        c.setBody(text);
        if (anonymous) {
            if (!Boolean.TRUE.equals(notice.getAllowAnonymous())) {
                throw new BusinessException("이 공지는 익명 댓글을 허용하지 않습니다.");
            }
            c.setAnonymous(true);
            c.setAuthorUserId(null);
            c.setAuthorToken(anonymityService.commentToken(noticeId, currentUser.getUserId()));
        } else {
            c.setAnonymous(false);
            c.setAuthorUserId(currentUser.getUserId());
            c.setAuthorToken(null);
        }
        communicationMapper.insertNoticeComment(c);
    }

    @Transactional
    public void deleteNoticeComment(CustomUserDetails currentUser, Long commentId) {
        // [감사] 작성자 본인 또는 공지 관리자만 삭제 가능(기존엔 관리자만 → 본인 삭제 허용).
        NoticeComment comment = communicationMapper.findNoticeComment(commentId);
        if (comment == null) {
            throw new BusinessException("댓글을 찾을 수 없습니다.");
        }
        boolean author = currentUser != null
                && java.util.Objects.equals(comment.getAuthorUserId(), currentUser.getUserId());
        if (!author && !organizationService.canManageNotice(currentUser)) {
            throw new BusinessException("본인 댓글 또는 공지 관리자만 삭제할 수 있습니다.");
        }
        communicationMapper.deactivateNoticeComment(commentId);
    }

    @Transactional
    public void createAdminNotice(CustomUserDetails currentUser, NoticeForm form) {
        requireNoticeManager(currentUser);
        AdminNotice notice = new AdminNotice();
        notice.setTitle(requiredTrim(form.getTitle(), "공지 제목을 입력하세요."));
        notice.setBody(sanitizedNoticeBody(form.getBody()));
        notice.setAllowAnonymous(form.isAllowAnonymousValue());
        notice.setCreatedBy(currentUser.getUserId());
        notice.setUpdatedBy(currentUser.getUserId());
        communicationMapper.insertAdminNotice(notice);
        notice.setLinkUrl(defaultNoticeUrl(notice.getId()));
        communicationMapper.updateAdminNoticeLinkUrl(notice.getId(), notice.getLinkUrl(), currentUser.getUserId());
        savePoll(notice.getId(), form);
        if (form.shouldSendNotification()) {
            communicationMapper.insertNoticeNotifications(notice.getId(), currentUser.getUserId(), notice.getPlainBody(), notice.getLinkUrl());
            publishPanelChangedAllActiveUsers("admin-notice");
        }
    }

    @Transactional
    public void updateAdminNotice(CustomUserDetails currentUser, NoticeForm form) {
        requireNoticeManager(currentUser);
        AdminNotice notice = adminNotice(form.getId(), currentUser);
        notice.setTitle(requiredTrim(form.getTitle(), "공지 제목을 입력하세요."));
        notice.setBody(sanitizedNoticeBody(form.getBody()));
        notice.setAllowAnonymous(form.isAllowAnonymousValue());
        notice.setLinkUrl(defaultNoticeUrl(notice.getId()));
        notice.setActive(true);
        notice.setUpdatedBy(currentUser.getUserId());
        communicationMapper.updateAdminNotice(notice);
        savePoll(notice.getId(), form);
        if (form.shouldSendNotification()) {
            communicationMapper.deleteNoticeNotifications(notice.getId());
            communicationMapper.insertNoticeNotifications(notice.getId(), currentUser.getUserId(), notice.getPlainBody(), notice.getLinkUrl());
            publishPanelChangedAllActiveUsers("admin-notice-update");
        }
    }

    @Transactional
    public void deleteAdminNotice(CustomUserDetails currentUser, Long id) {
        requireNoticeManager(currentUser);
        communicationMapper.deactivateAdminNotice(id, currentUser.getUserId());
        communicationMapper.deleteNoticeNotifications(id);
        publishPanelChangedAllActiveUsers("admin-notice-delete");
    }

    private void requireVisibleNotification(CustomUserDetails user, Long notificationId) {
        if (notifications(user).stream().noneMatch(notification -> Objects.equals(notificationId, notification.getId()))) {
            throw new BusinessException("알림을 찾을 수 없습니다.");
        }
    }

    @Transactional
    public void markNotificationRead(CustomUserDetails currentUser, Long notificationId) {
        requireVisibleNotification(currentUser, notificationId);
        communicationMapper.markNotificationRead(notificationId, currentUser.getUserId());
        publishPanelChanged(currentUser.getUserId(), "notification-read");
    }

    @Transactional
    public void deleteNotification(CustomUserDetails currentUser, Long notificationId) {
        requireVisibleNotification(currentUser, notificationId);
        communicationMapper.deleteNotification(notificationId, currentUser.getUserId());
        publishPanelChanged(currentUser.getUserId(), "notification-delete");
    }

    @Transactional
    public void markAllNotificationsRead(CustomUserDetails currentUser) {
        for (Notification notification : notifications(currentUser)) {
            if (notification.isUnread()) communicationMapper.markNotificationRead(notification.getId(), currentUser.getUserId());
        }
        publishPanelChanged(currentUser.getUserId(), "notification-read-all");
    }

    @Transactional
    public void markMessageRoomNotificationsRead(CustomUserDetails currentUser, Long roomId) {
        int changed = communicationMapper.markMessageRoomNotificationsRead(roomId, currentUser.getUserId());
        if (changed > 0) {
            publishPanelChanged(currentUser.getUserId(), "msg-notification-read");
        }
    }

    public void markWorkMemoNotificationsRead(CustomUserDetails currentUser, Long memoId) {
        int changed = communicationMapper.markWorkMemoNotificationsRead(memoId, currentUser.getUserId());
        if (changed > 0) {
            publishPanelChanged(currentUser.getUserId(), "work-memo-notification-read");
        }
    }

    // 결재 흐름 등에서 특정 사용자에게 알림을 보내고 종/우측패널을 실시간 갱신한다.
    @Transactional
    public void notifyUser(Long recipientUserId, Long senderUserId, String type,
                           String title, String body, String linkUrl) {
        if (recipientUserId == null) {
            return;
        }
        Notification n = new Notification();
        n.setRecipientUserId(recipientUserId);
        n.setSenderUserId(senderUserId);
        n.setType(type);
        n.setTitle(title);
        n.setBody(body);
        n.setLinkUrl(linkUrl);
        communicationMapper.insertNotification(n);
        publishPanelChanged(recipientUserId, "approval");
    }

    public List<MessageRoom> rooms(CustomUserDetails currentUser) {
        List<MessageRoom> rooms = communicationMapper.findRooms(currentUser.getUserId());
        rooms.forEach(room -> {
            applyDisplayRoomTitle(room);
            applyAvatarMembers(room, currentUser.getUserId());
        });
        return rooms;
    }

    public List<UserAccount> recipientCandidates(CustomUserDetails currentUser) {
        Set<Long> hiddenSystemUserIds = hiddenSystemUserIds();
        return userMapper.findAllActive().stream()
                .filter(user -> !Objects.equals(user.getId(), currentUser.getUserId()))
                .filter(user -> !hiddenSystemUserIds.contains(user.getId()))
                .toList();
    }

    public List<UserAccount> inviteCandidates(CustomUserDetails currentUser, Long roomId) {
        requireRoomMember(currentUser, roomId);
        Set<Long> hiddenSystemUserIds = hiddenSystemUserIds();
        return communicationMapper.findInviteCandidates(roomId, currentUser.getUserId()).stream()
                .filter(user -> !hiddenSystemUserIds.contains(user.getId()))
                .toList();
    }

    public RightPanelPayload rightPanel(CustomUserDetails currentUser) {
        List<Notification> recentNotifications = notifications(currentUser).stream()
                .filter(notification -> !("MESSAGE".equalsIgnoreCase(notification.getType()) && notification.getReadAt() != null))
                .limit(20)
                .toList();
        List<MessageRoom> recentRooms = releaseFeatures.isMessagingEnabled()
                ? rooms(currentUser).stream().limit(30).toList() : List.of();
        return new RightPanelPayload(
                currentUser.getUserId(),
                unreadNotificationCount(currentUser),
                unreadMessageCount(currentUser),
                recentNotifications,
                recentRooms,
                employeePresenceGroups(currentUser),
                buildOrgTree(currentUser)
        );
    }

    private List<OrgTreeNode> buildOrgTree(CustomUserDetails currentUser) {
        Set<Long> hidden = hiddenSystemUserIds();
        List<EmployeePresence> users = panelEmployees(currentUser).stream()
                .filter(u -> !hidden.contains(u.getUserId()))
                .toList();
        Map<Long, String> orgRoles = organizationService.orgRoleLabels();
        Map<Long, EmployeePresence> byUserId = users.stream()
                .collect(java.util.stream.Collectors.toMap(EmployeePresence::getUserId, u -> u, (a, b) -> a, LinkedHashMap::new));
        Map<Long, List<EmployeePresence>> byTeam = new LinkedHashMap<>();
        for (EmployeePresence u : users) {
            enrichPresence(u);
            u.setOrgRole(orgRoles.get(u.getUserId()));
            byTeam.computeIfAbsent(u.getTeamId() == null ? -1L : u.getTeamId(), k -> new ArrayList<>()).add(u);
        }
        return organizationService.findOrganizationTree().stream()
                .map(n -> toOrgTreeNode(n, byTeam, byUserId))
                .toList();
    }

    private OrgTreeNode toOrgTreeNode(OrganizationNode n,
                                      Map<Long, List<EmployeePresence>> byTeam,
                                      Map<Long, EmployeePresence> byUserId) {
        Team t = n.getTeam();
        OrgTreeNode node = new OrgTreeNode();
        node.setTeamId(t.getId());
        node.setName(t.getName());
        node.setNodeType(t.getNodeType());
        if (StringUtils.hasText(t.getLeaderName())) {
            node.setLeaderName(t.getLeaderName());
            node.setLeaderRole(orgRoleByType(t.getNodeType()));
        }
        List<EmployeePresence> members = new ArrayList<>(byTeam.getOrDefault(t.getId(), new ArrayList<>()));
        if (t.getLeaderUserId() != null && members.stream().noneMatch(m -> t.getLeaderUserId().equals(m.getUserId()))) {
            EmployeePresence leader = byUserId.get(t.getLeaderUserId());
            if (leader != null) {
                members.add(0, leader);
            }
        }
        node.setMembers(members);
        node.setChildren(n.getChildren().stream().map(c -> toOrgTreeNode(c, byTeam, byUserId)).toList());
        return node;
    }

    private String orgRoleByType(String nodeType) {
        if ("ROOT".equals(nodeType)) {
            return "대표";
        }
        if ("DIVISION".equals(nodeType)) {
            return "본부장";
        }
        if ("TEAM".equals(nodeType)) {
            return "팀장";
        }
        return "조직장";
    }
    @Transactional
    public Long createRoom(CustomUserDetails currentUser, MessageRoomForm form) {
        Set<Long> memberIds = normalizeRecipients(currentUser, form.recipientIds());
        List<UserAccount> recipients = memberIds.stream()
                .filter(id -> !Objects.equals(id, currentUser.getUserId()))
                .map(userMapper::findById)
                .filter(user -> user != null && Boolean.TRUE.equals(user.getActive()))
                .toList();
        if (recipients.isEmpty()) {
            throw new BusinessException("대화할 직원을 선택하세요.");
        }

        MessageRoom room = new MessageRoom();
        room.setTitle(resolveRoomTitle(form.title(), recipients));
        room.setCreatedBy(currentUser.getUserId());
        communicationMapper.insertRoom(room);

        communicationMapper.insertRoomMember(room.getId(), currentUser.getUserId());
        for (Long memberId : memberIds) {
            communicationMapper.insertRoomMember(room.getId(), memberId);
        }

        String firstMessage = trimToNull(form.firstMessage());
        if (firstMessage != null) {
            sendMessageInternal(currentUser, room.getId(), firstMessage, true);
        } else {
            publishRoomChanged(room.getId(), communicationMapper.findRoomMemberUserIds(room.getId()));
        }
        return room.getId();
    }

    public MessageRoom room(CustomUserDetails currentUser, Long roomId) {
        MessageRoom room = communicationMapper.findRoom(roomId, currentUser.getUserId());
        if (room == null) {
            throw new BusinessException("메시지방을 찾을 수 없습니다.");
        }
        applyDisplayRoomTitle(room);
        applyAvatarMembers(room, currentUser.getUserId());
        return room;
    }

    private void applyAvatarMembers(MessageRoom room, Long viewerUserId) {
        List<MessageRoomMember> avatars = communicationMapper.findRoomMembers(room.getId()).stream()
                .filter(member -> !Objects.equals(member.getUserId(), viewerUserId))
                .sorted(java.util.Comparator.comparing(member -> member.getUserName() == null ? "" : member.getUserName()))
                .limit(4)
                .toList();
        room.setAvatarMembers(avatars);
    }

    public List<MessageRoomMember> members(CustomUserDetails currentUser, Long roomId) {
        requireRoomMember(currentUser, roomId);
        List<MessageRoomMember> members = communicationMapper.findRoomMembers(roomId);
        // [O-1] 사이드패널과 동일한 방식으로 presence를 채워서 반환한다.
        java.util.Map<Long, EmployeePresence> byUser = new java.util.HashMap<>();
        for (EmployeePresence ep : presenceCacheService.employeePresence(LocalDate.now(SERVICE_ZONE))) {
            enrichPresence(ep);
            byUser.put(ep.getUserId(), ep);
        }
        for (MessageRoomMember m : members) {
            EmployeePresence ep = byUser.get(m.getUserId());
            if (ep != null) {
                m.setOnline(ep.isOnline());
                m.setStatusLabel(ep.getStatusLabel());
                m.setStatusClass(ep.getStatusClass());
            } else {
                m.setStatusLabel("미출근");
                m.setStatusClass("scheduled");
                m.setOnline(false);
            }
        }
        return members;
    }

    @Transactional
    public List<MessageMessage> messages(CustomUserDetails currentUser, Long roomId) {
        requireRoomMember(currentUser, roomId);
        List<MessageMessage> messages = communicationMapper.findMessages(roomId);
        attachMessageFiles(roomId, messages);
        Long lastMessageId = messages.isEmpty() ? null : messages.get(messages.size() - 1).getId();
        if (lastMessageId != null && communicationMapper.markRoomRead(roomId, currentUser.getUserId(), lastMessageId) > 0) {
            publishPanelChanged(currentUser.getUserId(), "message-read");
        }
        return messages;
    }

    /** 최근 limit개 메시지(오름차순) — 페이지네이션 초기 로드. 읽음 처리 포함(최신까지). */
    public List<MessageMessage> recentMessages(CustomUserDetails currentUser, Long roomId, int limit) {
        requireRoomMember(currentUser, roomId);
        List<MessageMessage> messages = communicationMapper.findRecentMessages(roomId, Math.max(1, limit));
        java.util.Collections.reverse(messages);
        attachMessageFiles(roomId, messages);
        Long lastMessageId = messages.isEmpty() ? null : messages.get(messages.size() - 1).getId();
        if (lastMessageId != null && communicationMapper.markRoomRead(roomId, currentUser.getUserId(), lastMessageId) > 0) {
            publishPanelChanged(currentUser.getUserId(), "message-read");
        }
        return messages;
    }

    /** beforeId 이전 limit개 메시지(오름차순) — 위로 스크롤 시 과거분 로드. 읽음 처리 없음. */
    public List<MessageMessage> messagesBefore(CustomUserDetails currentUser, Long roomId, Long beforeId, int limit) {
        requireRoomMember(currentUser, roomId);
        if (beforeId == null) {
            return List.of();
        }
        List<MessageMessage> messages = communicationMapper.findMessagesBefore(roomId, beforeId, Math.max(1, limit));
        java.util.Collections.reverse(messages);
        attachMessageFiles(roomId, messages);
        return messages;
    }

    public boolean hasMessagesBefore(CustomUserDetails currentUser, Long roomId, Long beforeId) {
        if (beforeId == null) {
            return false;
        }
        requireRoomMember(currentUser, roomId);
        return communicationMapper.existsMessageBefore(roomId, beforeId);
    }

    private void attachMessageFiles(Long roomId, List<MessageMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        Map<Long, MessageMessage> byId = new LinkedHashMap<>();
        for (MessageMessage message : messages) {
            byId.put(message.getId(), message);
        }
        for (MessageAttachment attachment : communicationMapper.findAttachmentsByRoom(roomId)) {
            MessageMessage message = byId.get(attachment.getMessageId());
            if (message != null) {
                message.getAttachments().add(attachment);
            }
        }
    }

    @Transactional
    public void sendMessage(CustomUserDetails currentUser, Long roomId, MessageForm form) {
        String body = trimToNull(form.body());
        if (body == null) {
            throw new BusinessException("메시지 내용을 입력하세요.");
        }
        sendMessageInternal(currentUser, roomId, body, false);
    }

    @Transactional
    public void sendMessageAttachments(CustomUserDetails currentUser, Long roomId, String body, List<MultipartFile> files) {
        requireRoomMember(currentUser, roomId);
        List<MultipartFile> actualFiles = files == null ? List.of() : files.stream()
                .filter(file -> file != null && !file.isEmpty())
                .toList();
        if (actualFiles.isEmpty()) {
            throw new BusinessException("첨부할 파일을 선택하세요.");
        }
        long totalSize = actualFiles.stream().mapToLong(MultipartFile::getSize).sum();
        if (totalSize > MAX_MESSAGE_ATTACHMENT_BYTES) {
            throw new BusinessException("메시지 첨부파일은 총 100MB까지 업로드할 수 있습니다.");
        }
        for (MultipartFile file : actualFiles) {
            if (file.getSize() > MAX_MESSAGE_ATTACHMENT_BYTES) {
                throw new BusinessException("파일 1개는 100MB까지 업로드할 수 있습니다.");
            }
            systemSettingsService.validateUploadExtension(file.getOriginalFilename());
        }

        long startedAt = System.currentTimeMillis();
        MessageMessage message = new MessageMessage();
        message.setRoomId(roomId);
        message.setSenderUserId(currentUser.getUserId());
        message.setBody(StringUtils.hasText(body) ? body.trim() : "");
        communicationMapper.insertMessage(message);

        for (MultipartFile file : actualFiles) {
            storeMessageAttachment(message, file);
        }

        communicationMapper.touchRoom(roomId);
        communicationMapper.markRoomRead(roomId, currentUser.getUserId(), message.getId());
        List<Long> memberIds = communicationMapper.findRoomMemberUserIds(roomId);
        notifyMessageRecipients(currentUser, roomId, attachmentNotificationBody(currentUser, actualFiles), memberIds);
        attachMessageFiles(roomId, List.of(message));
        publishRoomChanged(roomId, memberIds, message);
        // 대용량 전송 체감 진단용 — 서버측 저장 소요시간(업로드 수신 이후 단계만)
        log.info("message attachments stored: roomId={} files={} totalBytes={} took={}ms",
                roomId, actualFiles.size(), totalSize, System.currentTimeMillis() - startedAt);
    }
    @Transactional
    public Long openDirectRoom(CustomUserDetails currentUser, Long recipientUserId) {
        if (Objects.equals(currentUser.getUserId(), recipientUserId)) {
            throw new BusinessException("본인에게는 메시지를 보낼 수 없습니다.");
        }
        UserAccount recipient = userMapper.findById(recipientUserId);
        if (recipient == null || !Boolean.TRUE.equals(recipient.getActive())) {
            throw new BusinessException("메시지를 보낼 직원을 찾을 수 없습니다.");
        }

        Long existingRoomId = communicationMapper.findDirectRoom(currentUser.getUserId(), recipientUserId);
        if (existingRoomId != null) {
            return existingRoomId;
        }

        MessageRoom room = new MessageRoom();
        room.setTitle(recipient.getName() + "님과의 대화");
        room.setCreatedBy(currentUser.getUserId());
        communicationMapper.insertRoom(room);
        communicationMapper.insertRoomMember(room.getId(), currentUser.getUserId());
        communicationMapper.insertRoomMember(room.getId(), recipientUserId);
        publishPanelChanged(List.of(currentUser.getUserId(), recipientUserId), "room-opened");
        return room.getId();
    }

    @Transactional
    public MessageRoom updateRoomSettings(CustomUserDetails currentUser, Long roomId, MessageRoomSettingsForm form) {
        requireRoomMember(currentUser, roomId);
        MessageRoom room = communicationMapper.findRoom(roomId, currentUser.getUserId());
        if (room == null) {
            throw new BusinessException("메시지방을 찾을 수 없습니다.");
        }
        String customTitle = trimToNull(form.customTitle());
        Boolean pinned = form.pinned() == null ? Boolean.TRUE.equals(room.getPinned()) : form.pinned();
        Boolean muted = form.muted() == null ? Boolean.TRUE.equals(room.getMuted()) : form.muted();
        int opacity = form.opacityPercent() == null ? (room.getOpacityPercent() == null ? 100 : room.getOpacityPercent()) : form.opacityPercent();
        opacity = Math.max(0, Math.min(100, opacity));
        communicationMapper.upsertRoomSettings(roomId, currentUser.getUserId(), customTitle, pinned, muted, opacity);
        publishPanelChanged(currentUser.getUserId(), "room-settings");
        MessageRoom updated = communicationMapper.findRoom(roomId, currentUser.getUserId());
        applyDisplayRoomTitle(updated);
        applyAvatarMembers(updated, currentUser.getUserId());
        return updated;
    }

    @Transactional
    public void inviteMembers(CustomUserDetails currentUser, Long roomId, List<Long> userIds) {
        requireRoomMember(currentUser, roomId);
        if (userIds == null || userIds.isEmpty()) {
            throw new BusinessException("초대할 직원을 선택하세요.");
        }
        Set<Long> candidateIds = new LinkedHashSet<>(userIds);
        candidateIds.remove(currentUser.getUserId());

        List<UserAccount> invitees = new ArrayList<>();
        for (Long userId : candidateIds) {
            if (userId == null || Boolean.TRUE.equals(communicationMapper.isRoomMember(roomId, userId))) {
                continue;
            }
            UserAccount invitee = userMapper.findById(userId);
            if (invitee != null && Boolean.TRUE.equals(invitee.getActive())) {
                communicationMapper.insertRoomMember(roomId, invitee.getId());
                invitees.add(invitee);
            }
        }
        if (invitees.isEmpty()) {
            throw new BusinessException("새로 초대할 직원을 선택하세요.");
        }

        String actorName = displayName(currentUser);
        String body = actorName + "님이 " + inviteSummary(invitees) + " 초대했습니다.";
        insertSystemMessage(currentUser, roomId, body, invitees.stream().map(UserAccount::getId).toList(), "메시지방 초대");
    }

    private String inviteSummary(List<UserAccount> invitees) {
        List<String> names = invitees.stream()
                .map(UserAccount::getName)
                .filter(StringUtils::hasText)
                .toList();
        if (names.isEmpty()) {
            return "직원을";
        }
        return String.join(", ", names) + "님을";
    }

    @Transactional
    public void leaveRoom(CustomUserDetails currentUser, Long roomId) {
        requireRoomMember(currentUser, roomId);
        List<Long> beforeMembers = communicationMapper.findRoomMemberUserIds(roomId);
        UserAccount user = userMapper.findById(currentUser.getUserId());
        String name = user == null ? currentUser.getName() : user.getName();
        insertSystemMessage(currentUser, roomId, name + "님이 채팅방을 나가셨습니다.", List.of(), "메시지방 알림");
        communicationMapper.deleteRoomMember(roomId, currentUser.getUserId());
        List<Long> remainingMembers = beforeMembers.stream()
                .filter(id -> !Objects.equals(id, currentUser.getUserId()))
                .toList();
        publishPanelChanged(remainingMembers, "room-left");
        publishPanelChanged(currentUser.getUserId(), "room-left");
    }

    private void sendMessageInternal(CustomUserDetails currentUser, Long roomId, String body, boolean firstMessage) {
        requireRoomMember(currentUser, roomId);
        MessageMessage message = new MessageMessage();
        message.setRoomId(roomId);
        message.setSenderUserId(currentUser.getUserId());
        message.setBody(body);
        communicationMapper.insertMessage(message);
        communicationMapper.touchRoom(roomId);
        communicationMapper.markRoomRead(roomId, currentUser.getUserId(), message.getId());
        List<Long> memberIds = communicationMapper.findRoomMemberUserIds(roomId);
        notifyMessageRecipients(currentUser, roomId, body, memberIds);
        publishRoomChanged(roomId, memberIds, message);
    }

    private void insertSystemMessage(CustomUserDetails currentUser, Long roomId, String body,
                                     Collection<Long> notificationRecipients, String notificationTitle) {
        requireRoomMember(currentUser, roomId);
        MessageMessage message = new MessageMessage();
        message.setRoomId(roomId);
        message.setSenderUserId(null);
        message.setBody(body);
        communicationMapper.insertMessage(message);
        communicationMapper.touchRoom(roomId);
        communicationMapper.markRoomRead(roomId, currentUser.getUserId(), message.getId());
        List<Long> memberIds = communicationMapper.findRoomMemberUserIds(roomId);
        for (Long recipientUserId : notificationRecipients) {
            if (recipientUserId != null && !Objects.equals(recipientUserId, currentUser.getUserId())) {
                insertMessageNotification(recipientUserId, currentUser.getUserId(), notificationTitle, body, roomId);
            }
        }
        publishRoomChanged(roomId, memberIds, message);
    }

    private void notifyMessageRecipients(CustomUserDetails currentUser, Long roomId, String body, Collection<Long> memberIds) {
        String senderName = displayName(currentUser);
        String title = senderName + "님의 메시지";
        String notificationBody = senderName + ": " + body;
        for (Long memberId : memberIds) {
            if (memberId != null && !Objects.equals(memberId, currentUser.getUserId())) {
                insertMessageNotification(memberId, currentUser.getUserId(), title, notificationBody, roomId);
            }
        }
    }

    private void storeMessageAttachment(MessageMessage message, MultipartFile file) {
        String original = cleanAttachmentName(file.getOriginalFilename());
        // ★ 클라이언트가 보낸 Content-Type 을 저장하지 않는다.
        //   다운로드는 이미지일 때 inline 으로, 저장된 Content-Type 그대로 서빙한다.
        //   그래서 예전에는 `x.png` 라는 이름의 HTML 을 Content-Type: text/html 로 올리면
        //   (확장자 화이트리스트는 .png 라 통과) 오피스 오리진에서 inline HTML 로 서빙되어
        //   저장형 XSS 가 됐다. CSP 가 script-src 'unsafe-inline' 이라 스크립트도 돈다.
        //   nosniff 는 도움이 안 된다 — 서버가 text/html 이라고 <명시> 했기 때문이다.
        //   확장자는 이미 화이트리스트로 검증되므로, 그 확장자에서 타입을 서버가 정한다.
        String ext = extensionOf(original);
        String contentType = contentTypeForExtension(ext);
        String storedName = message.getRoomId() + "-" + message.getId() + "-" + UUID.randomUUID() + ext;
        Path root = messageAttachmentRoot();
        Path target = root.resolve(storedName).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException("첨부파일 저장 경로가 올바르지 않습니다.");
        }
        try {
            Files.createDirectories(root);
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new BusinessException("첨부파일 저장에 실패했습니다.");
        }
        com.portfolio.worksystem.util.AfterCommit.deleteUploadOnRollback(target, "첨부");

        MessageAttachment attachment = new MessageAttachment();
        attachment.setMessageId(message.getId());
        attachment.setRoomId(message.getRoomId());
        attachment.setOriginalFilename(original);
        attachment.setStoredFilename(storedName);
        attachment.setContentType(contentType);
        attachment.setFileSize(file.getSize());
        attachment.setAttachmentType(attachmentType(contentType, original));
        attachment.setExpiresAt(LocalDateTime.now(SERVICE_ZONE).plusDays(MESSAGE_ATTACHMENT_TTL_DAYS));
        communicationMapper.insertMessageAttachment(attachment);
    }

    public MessageAttachment messageAttachment(CustomUserDetails currentUser, Long attachmentId) {
        MessageAttachment attachment = communicationMapper.findAttachment(attachmentId, currentUser.getUserId());
        if (attachment == null) {
            throw new BusinessException("첨부파일을 찾을 수 없습니다.");
        }
        if (attachment.isExpired()) {
            throw new BusinessException("만료된 첨부파일입니다.");
        }
        // ★ 조회 시점에도 확장자 기준으로 다시 정한다.
        //   저장 시점 정규화만으로는 <이미 저장된 행>(TTL 30일)이 남아 있는 동안 위험하다.
        //   과거에는 클라이언트가 보낸 Content-Type 을 그대로 저장했고, 이미지로 분류되면
        //   inline 으로 서빙되어 저장형 XSS 가 됐다.
        String safeType = contentTypeForExtension(extensionOf(attachment.getOriginalFilename()));
        attachment.setContentType(safeType);
        attachment.setAttachmentType(attachmentType(safeType, attachment.getOriginalFilename()));
        return attachment;
    }

    public Resource messageAttachmentResource(CustomUserDetails currentUser, Long attachmentId) {
        MessageAttachment attachment = messageAttachment(currentUser, attachmentId);
        Path root = messageAttachmentRoot();
        Path file = root.resolve(attachment.getStoredFilename()).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new BusinessException("첨부파일을 찾을 수 없습니다.");
        }
        try {
            return new UrlResource(file.toUri());
        } catch (IOException e) {
            throw new BusinessException("첨부파일을 읽을 수 없습니다.");
        }
    }

    /**
     * 채팅 그리드용 축소 썸네일(JPEG). 짧은 변 기준이 아닌 긴 변을 maxDim으로 맞춰 비율 유지 후 디스크 캐시.
     * 클라이언트가 1:1 cover로 잘라 쓰므로 비율 보존만 하면 된다. 실패/비이미지면 원본 리소스로 폴백.
     */
    public Resource messageImageThumbnail(CustomUserDetails currentUser, Long attachmentId, int width) {
        if (publicDemo) return messageAttachmentResource(currentUser, attachmentId);
        MessageAttachment attachment = messageAttachment(currentUser, attachmentId);
        boolean isImage = attachment.getAttachmentType() != null && attachment.getAttachmentType().equalsIgnoreCase("IMAGE");
        Path root = messageAttachmentRoot();
        Path file = root.resolve(attachment.getStoredFilename()).normalize();
        if (!isImage || !file.startsWith(root) || !Files.isRegularFile(file)) {
            return messageAttachmentResource(currentUser, attachmentId);
        }
        int maxDim = Math.max(120, Math.min(1024, width * 2));
        try {
            Path thumbDir = root.resolve("thumbs");
            Path thumb = thumbDir.resolve(attachment.getStoredFilename() + "." + maxDim + ".jpg").normalize();
            if (thumb.startsWith(root) && Files.isRegularFile(thumb)
                    && Files.getLastModifiedTime(thumb).toMillis() >= Files.getLastModifiedTime(file).toMillis()) {
                return new UrlResource(thumb.toUri());
            }
            java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(file.toFile());
            if (src == null) {
                return messageAttachmentResource(currentUser, attachmentId);
            }
            int sw = src.getWidth();
            int sh = src.getHeight();
            double scale = Math.min(1.0, maxDim / (double) Math.max(sw, sh));
            int tw = Math.max(1, (int) Math.round(sw * scale));
            int th = Math.max(1, (int) Math.round(sh * scale));
            java.awt.image.BufferedImage dst = new java.awt.image.BufferedImage(tw, th, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = dst.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, tw, th);
            g.drawImage(src, 0, 0, tw, th, null);
            g.dispose();
            Files.createDirectories(thumbDir);
            Path tmp = thumbDir.resolve(thumb.getFileName().toString() + ".tmp" + System.nanoTime());
            try (java.io.OutputStream os = Files.newOutputStream(tmp)) {
                javax.imageio.ImageIO.write(dst, "jpg", os);
            }
            try {
                Files.move(tmp, thumb, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveError) {
                Files.deleteIfExists(tmp);
                return messageAttachmentResource(currentUser, attachmentId);
            }
            return new UrlResource(thumb.toUri());
        } catch (IOException e) {
            return messageAttachmentResource(currentUser, attachmentId);
        }
    }

    @Scheduled(cron = "0 20 3 * * *", zone = "Asia/Seoul")
    public void cleanupExpiredMessageAttachments() {
        if (!releaseFeatures.isMessagingEnabled()) return;
        Path root = messageAttachmentRoot();
        for (MessageAttachment attachment : communicationMapper.findExpiredAttachments()) {
            try {
                // ★ 레코드를 먼저 지우고 파일을 나중에 지운다.
                //   반대로 하면(파일 먼저) DB 삭제가 실패했을 때 <목록에는 있는데 열면 깨지는>
                //   첨부가 남는다. 이 순서면 최악이라도 참조 없는 고아 파일만 남고,
                //   그건 사용자에게 보이지 않는다.
                //   이 메서드는 트랜잭션이 아니라 건별로 즉시 커밋되므로 순서가 곧 보장이다.
                communicationMapper.deleteAttachment(attachment.getId());
                if (StringUtils.hasText(attachment.getStoredFilename())) {
                    Path file = root.resolve(attachment.getStoredFilename()).normalize();
                    if (file.startsWith(root)) {
                        Files.deleteIfExists(file);
                    }
                }
            } catch (Exception e) {
                log.warn("만료 첨부 정리 실패: attachmentId={} ({})", attachment.getId(), e.getMessage());
            }
        }
    }

    private String attachmentNotificationBody(CustomUserDetails currentUser, List<MultipartFile> files) {
        String name = displayName(currentUser);
        boolean allImages = !files.isEmpty() && files.stream()
                .allMatch(f -> "IMAGE".equalsIgnoreCase(attachmentType(f.getContentType(), f.getOriginalFilename())));
        if (allImages) {
            return name + ": 이미지를 보냈습니다";
        }
        boolean anyImage = files.stream()
                .anyMatch(f -> "IMAGE".equalsIgnoreCase(attachmentType(f.getContentType(), f.getOriginalFilename())));
        if (anyImage) {
            return name + ": 첨부파일을 보냈습니다";
        }
        return name + ": 파일을 보냈습니다";
    }

    /**
     * 확장자 → Content-Type. <b>서버가 정하는 값만 저장한다.</b>
     *
     * <p>여기 없는 확장자는 전부 {@code application/octet-stream} 이다. 그러면 다운로드가
     * {@code attachmentType} 판정에서 FILE 로 떨어져 {@code attachment} 로 내려가고,
     * 브라우저가 렌더링하지 않는다 — 모르는 형식일수록 안전한 쪽으로 기운다.
     *
     * <p>이미지만 inline 으로 서빙되므로, 이 표에 <b>렌더링 가능한 문서 형식
     * (html·svg·xml 등)을 넣으면 안 된다.</b> 확장자 화이트리스트를 관리 화면에서 넓혀도
     * 이 표가 막아 준다.
     */
    private String contentTypeForExtension(String ext) {
        String e = ext == null ? "" : ext.toLowerCase().replaceFirst("^\\.", "");
        return switch (e) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "pdf" -> "application/pdf";
            case "txt" -> "text/plain";
            case "csv" -> "text/csv";
            default -> "application/octet-stream";
        };
    }

    private String attachmentType(String contentType, String filename) {
        String lowerContent = contentType == null ? "" : contentType.toLowerCase();
        String lowerName = filename == null ? "" : filename.toLowerCase();
        if (lowerContent.startsWith("image/")) return "IMAGE";
        if (lowerContent.startsWith("video/")) return "VIDEO";
        if (lowerName.matches(".*\\.(png|jpg|jpeg|gif|webp)$")) return "IMAGE";
        if (lowerName.matches(".*\\.(mp4|mov|avi|mkv|webm)$")) return "VIDEO";
        return "FILE";
    }

    private String cleanAttachmentName(String filename) {
        String name = StringUtils.hasText(filename) ? Path.of(filename).getFileName().toString() : "attachment";
        name = name.replaceAll("[\\r\\n\\\\/]+", "_").trim();
        return name.isBlank() ? "attachment" : name;
    }

    private String extensionOf(String filename) {
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        String ext = filename.substring(dot).toLowerCase();
        return ext.matches("\\.[a-z0-9]{1,10}") ? ext : "";
    }

    private Path messageAttachmentRoot() {
        return Path.of(messageAttachmentDir).toAbsolutePath().normalize();
    }

    private void insertMessageNotification(Long recipientUserId, Long senderUserId, String title, String body, Long roomId) {
        if (Boolean.TRUE.equals(communicationMapper.isRoomMuted(roomId, recipientUserId))) {
            publishPanelChanged(recipientUserId, "message-muted");
            return;
        }
        Notification notification = new Notification();
        notification.setRecipientUserId(recipientUserId);
        notification.setSenderUserId(senderUserId);
        notification.setType("MESSAGE");
        notification.setTitle(title);
        notification.setBody(body);
        notification.setLinkUrl("/dashboard?messageRoomId=" + roomId);
        communicationMapper.insertNotification(notification);
        publishPanelChanged(recipientUserId, "message");
    }

    private String displayName(CustomUserDetails currentUser) {
        UserAccount user = userMapper.findById(currentUser.getUserId());
        if (user != null && StringUtils.hasText(user.getName())) {
            return user.getName();
        }
        return StringUtils.hasText(currentUser.getName()) ? currentUser.getName() : currentUser.getUsername();
    }

    private List<EmployeePresence> panelEmployees(CustomUserDetails currentUser) {
        if (releaseFeatures.isAttendanceEnabled()) return presenceCacheService.employeePresence(LocalDate.now(SERVICE_ZONE));
        return organizationService.findUsers(currentUser).stream().map(account -> {
            EmployeePresence employee = new EmployeePresence();
            employee.setUserId(account.getId());
            employee.setName(account.getName());
            employee.setTeamId(account.getTeamId());
            employee.setTeamCode(account.getTeamCode());
            employee.setTeamName(account.getTeamName());
            employee.setDept(account.getDept());
            employee.setExtensionNumber(account.getExtensionNumber());
            employee.setMobileNumber(account.getMobileNumber());
            employee.setJobTitle(account.getJobTitle());
            employee.setEmail(account.getEmail());
            employee.setProfileImageFilename(account.getProfileImageFilename());
            employee.setContractor(Boolean.TRUE.equals(account.getContractor()));
            return employee;
        }).toList();
    }

    private List<RightPanelGroup> employeePresenceGroups(CustomUserDetails currentUser) {
        Set<Long> hiddenSystemUserIds = hiddenSystemUserIds();
        List<EmployeePresence> users = panelEmployees(currentUser).stream()
                .filter(user -> !hiddenSystemUserIds.contains(user.getUserId()))
                .toList();
        Map<Long, String> orgRoles = organizationService.orgRoleLabels();
        Map<String, LinkedHashMap<Long, TeamBucket>> groupBuckets = new LinkedHashMap<>();
        for (EmployeePresence user : users) {
            enrichPresence(user);
            user.setOrgRole(orgRoles.get(user.getUserId()));
            groupBuckets
                    .computeIfAbsent(user.getOrgGroupName(), key -> new LinkedHashMap<>())
                    .computeIfAbsent(user.getTeamId() == null ? -1L : user.getTeamId(), key -> new TeamBucket(user.getTeamId(), safeTeamName(user)))
                    .users()
                    .add(user);
        }

        List<RightPanelGroup> groups = new ArrayList<>();
        for (Map.Entry<String, LinkedHashMap<Long, TeamBucket>> groupEntry : groupBuckets.entrySet()) {
            List<RightPanelGroup.RightPanelTeam> teams = groupEntry.getValue().values().stream()
                    .map(bucket -> new RightPanelGroup.RightPanelTeam(bucket.teamId(), bucket.name(), bucket.users()))
                    .toList();
            groups.add(new RightPanelGroup(groupEntry.getKey(), teams));
        }
        return groups;
    }

    private void enrichPresence(EmployeePresence user) {
        user.setOnline(releaseFeatures.isPresenceEnabled() && realtimeHub.isOnline(user.getUserId()));
        if (!StringUtils.hasText(user.getOrgGroupName())) {
            user.setOrgGroupName(resolveOrgGroupName(user.getTeamCode(), user.getTeamName()));
        }
        if (!releaseFeatures.isAttendanceEnabled()) {
            user.setStatus(null);
            user.setStatusLabel(null);
            user.setStatusClass(null);
            return;
        }
        String status = user.getStatus() == null ? "SCHEDULED" : user.getStatus();
        user.setStatusLabel(resolveStatusLabel(status));
        user.setStatusClass(resolveStatusClass(status));
    }

    private String resolveOrgGroupName(String teamCode, String teamName) {
        if ("AUD".equals(teamCode) || "품질관리팀".equals(teamName)) {
            return "품질관리팀";
        }
        if ("FIN".equals(teamCode) || "BIZ".equals(teamCode)) {
            return "경영지원본부";
        }
        if ("STR".equals(teamCode) || "TRA".equals(teamCode) || "SUP".equals(teamCode)) {
            return "사업본부";
        }
        if ("DEV".equals(teamCode) || "SEC".equals(teamCode) || "OPS".equals(teamCode)) {
            return "기술운영본부";
        }
        return "기타";
    }

    private String resolveStatusLabel(String status) {
        return switch (status) {
            case "WORKING", "NORMAL" -> "근무";
            case "LEAVE" -> "연차";
            case "BUSINESS_TRIP" -> "출장";
            case "HOLIDAY" -> "휴무";
            case "OFF_WORK" -> "퇴근";
            case "LATE" -> "지각";
            case "EARLY_LEAVE" -> "조퇴";
            case "REMOTE" -> "재택";
            case "OUTSIDE" -> "외근";
            case "ABSENT" -> "미출근";
            default -> "미출근";
        };
    }

    private String resolveStatusClass(String status) {
        return switch (status) {
            case "WORKING", "NORMAL", "LATE", "REMOTE", "OUTSIDE" -> "work";
            case "LEAVE", "BUSINESS_TRIP" -> "away";
            case "HOLIDAY", "OFF_WORK" -> "off";
            case "ABSENT", "EARLY_LEAVE" -> "absent";
            default -> "pending";
        };
    }

    private String safeTeamName(EmployeePresence user) {
        return StringUtils.hasText(user.getTeamName()) ? user.getTeamName() : "미지정";
    }

    private Set<Long> normalizeRecipients(CustomUserDetails currentUser, List<Long> recipientIds) {
        if (recipientIds == null || recipientIds.isEmpty()) {
            throw new BusinessException("대화할 직원을 선택하세요.");
        }
        Set<Long> memberIds = new LinkedHashSet<>();
        for (Long recipientId : recipientIds) {
            if (recipientId != null) {
                memberIds.add(recipientId);
            }
        }
        memberIds.remove(currentUser.getUserId());
        if (memberIds.isEmpty()) {
            throw new BusinessException("본인 외 대화할 직원을 선택하세요.");
        }
        return memberIds;
    }

    private String resolveRoomTitle(String title, List<UserAccount> recipients) {
        String trimmedTitle = trimToNull(title);
        if (trimmedTitle != null) {
            return trimmedTitle;
        }
        List<String> names = new ArrayList<>(recipients.stream().map(UserAccount::getName).toList());
        if (names.size() == 1) {
            return names.get(0) + "님과의 대화";
        }
        return names.get(0) + " 외 " + (names.size() - 1) + "명";
    }

    private void applyDisplayRoomTitle(MessageRoom room) {
        if (room == null) {
            return;
        }
        String customTitle = trimToNull(room.getCustomTitle());
        if (customTitle != null) {
            room.setTitle(customTitle);
            return;
        }
        String memberNames = trimToNull(room.getMemberNames());
        if (memberNames == null) {
            return;
        }
        List<String> names = java.util.Arrays.stream(memberNames.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
        if (names.isEmpty()) {
            return;
        }
        String quoted = names.stream()
                .map(name -> "'" + name + "'")
                .collect(java.util.stream.Collectors.joining(", "));
        room.setTitle(quoted + "님과의 대화");
    }

    private void publishRoomChanged(Long roomId, Collection<Long> userIds) {
        publishRoomChanged(roomId, userIds, null);
    }

    private void publishRoomChanged(Long roomId, Collection<Long> userIds, MessageMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("roomId", roomId);
        if (message != null) {
            Map<String, Object> messagePayload = new LinkedHashMap<>();
            messagePayload.put("id", message.getId());
            messagePayload.put("roomId", roomId);
            messagePayload.put("senderUserId", message.getSenderUserId());
            messagePayload.put("senderName", message.getSenderUserId() == null ? "" : displayName(userMapper.findById(message.getSenderUserId())));
            messagePayload.put("senderProfileImageFilename", senderProfileImageFilename(message.getSenderUserId()));
            messagePayload.put("body", message.getBody());
            messagePayload.put("attachments", message.getAttachments());
            messagePayload.put("createdAt", message.getCreatedAt() == null ? LocalDateTime.now(SERVICE_ZONE) : message.getCreatedAt());
            payload.put("message", messagePayload);
        }
        afterCommit(() -> {
            for (Long userId : userIds) {
                if (message != null) {
                    realtimeHub.sendToUser(userId, "MESSAGE_CREATED", payload);
                }
                realtimeHub.sendToUser(userId, "MESSAGE_CHANGED", Map.of("roomId", roomId));
                realtimeHub.sendToUser(userId, "PANEL_CHANGED", Map.of("reason", "message"));
            }
        });
    }

    private String displayName(UserAccount user) {
        return user == null || user.getName() == null ? "" : user.getName();
    }

    private String senderProfileImageFilename(Long senderUserId) {
        if (senderUserId == null) {
            return "";
        }
        UserAccount user = userMapper.findById(senderUserId);
        return user == null || user.getProfileImageFilename() == null ? "" : user.getProfileImageFilename();
    }

    private void publishPanelChanged(Long userId, String reason) {
        publishPanelChanged(List.of(userId), reason);
    }

    private void publishPanelChanged(Collection<Long> userIds, String reason) {
        afterCommit(() -> realtimeHub.sendToUsers(userIds, "PANEL_CHANGED", Map.of("reason", reason)));
    }

    private void afterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
            return;
        }
        task.run();
    }

    private void requireRoomMember(CustomUserDetails currentUser, Long roomId) {
        if (!Boolean.TRUE.equals(communicationMapper.isRoomMember(roomId, currentUser.getUserId()))) {
            throw new BusinessException("메시지방 접근 권한이 없습니다.");
        }
    }

    private Set<Long> hiddenSystemUserIds() {
        return Set.copyOf(communicationMapper.findSystemAccountUserIds());
    }

    private void requireNoticeManager(CustomUserDetails currentUser) {
        if (!organizationService.canManageNotice(currentUser)) {
            throw new BusinessException("공지 관리 권한이 없습니다.");
        }
    }

    private void publishPanelChangedAllActiveUsers(String reason) {
        publishPanelChanged(userMapper.findAllActive().stream().map(UserAccount::getId).toList(), reason);
    }

    private String requiredTrim(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(message);
        }
        return value.trim();
    }

    private String sanitizedNoticeBody(String value) {
        String sanitized = NoticeContentUtils.sanitizeHtml(value);
        if (!NoticeContentUtils.hasContent(sanitized)) {
            throw new BusinessException("공지 내용을 입력하세요.");
        }
        return sanitized;
    }

    private String defaultNoticeUrl(Long noticeId) {
        return "/notices/" + noticeId;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private record TeamBucket(Long teamId, String name, List<EmployeePresence> users) {
        private TeamBucket(Long teamId, String name) {
            this(teamId, name, new ArrayList<>());
        }
    }
}
