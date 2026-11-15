package com.portfolio.worksystem.mapper;

import com.portfolio.worksystem.model.WorkMemo;
import com.portfolio.worksystem.model.WorkMemoAttachment;
import com.portfolio.worksystem.model.WorkMemoRecipient;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkMemoMapper {
    // 작성
    void insertMemo(WorkMemo memo);
    void insertRecipient(WorkMemoRecipient recipient);
    void insertAttachment(WorkMemoAttachment attachment);

    // 목록 (받은함 / 보낸함 / 중요함)
    List<WorkMemo> findReceivedMemos(@Param("userId") Long userId,
                                     @Param("query") String query,
                                     @Param("startDate") String startDate,
                                     @Param("endDate") String endDate,
                                     @Param("limit") int limit,
                                     @Param("offset") int offset);

    List<WorkMemo> findSentMemos(@Param("userId") Long userId,
                                 @Param("query") String query,
                                 @Param("startDate") String startDate,
                                 @Param("endDate") String endDate,
                                 @Param("limit") int limit,
                                 @Param("offset") int offset);

    List<WorkMemo> findStarredMemos(@Param("userId") Long userId,
                                    @Param("query") String query,
                                    @Param("startDate") String startDate,
                                    @Param("endDate") String endDate,
                                    @Param("limit") int limit,
                                    @Param("offset") int offset);

    int countReceived(@Param("userId") Long userId, @Param("query") String query,
                      @Param("startDate") String startDate, @Param("endDate") String endDate);
    int countSent(@Param("userId") Long userId, @Param("query") String query,
                  @Param("startDate") String startDate, @Param("endDate") String endDate);
    int countStarred(@Param("userId") Long userId, @Param("query") String query,
                     @Param("startDate") String startDate, @Param("endDate") String endDate);
    int countUnreadReceived(@Param("userId") Long userId);

    // 상세
    WorkMemo findMemo(@Param("memoId") Long memoId, @Param("userId") Long userId);
    List<WorkMemoRecipient> findRecipients(@Param("memoId") Long memoId);
    List<WorkMemoAttachment> findAttachments(@Param("memoId") Long memoId);
    WorkMemoAttachment findAttachment(@Param("attachmentId") Long attachmentId);

    // 상태 변경
    int markRead(@Param("memoId") Long memoId, @Param("userId") Long userId);
    int setStarred(@Param("memoId") Long memoId, @Param("userId") Long userId, @Param("starred") boolean starred);
}
