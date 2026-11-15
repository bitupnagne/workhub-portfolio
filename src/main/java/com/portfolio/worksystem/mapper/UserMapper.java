package com.portfolio.worksystem.mapper;

import com.portfolio.worksystem.model.MemberStatus;
import com.portfolio.worksystem.model.UserAccount;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {
    UserAccount findByUsername(String username);
    UserAccount findByEmployeeNumber(String employeeNumber);
    UserAccount findById(Long id);
    List<String> findRolesByUserId(Long userId);
    List<UserAccount> findAllActive();
    List<UserAccount> findAll();
    List<UserAccount> findByTeamId(Long teamId);
    List<UserAccount> findByRole(String roleCode);
    List<UserAccount> findOrderableMembers(@Param("teamId") Long teamId);
    void updateMemberOrder(@Param("userId") Long userId, @Param("memberOrder") int memberOrder);
    List<MemberStatus> findMemberStatuses(@Param("teamId") Long teamId, @Param("weekKey") String weekKey);
    void insertUser(UserAccount user);
    /** [BOOT-1] 최초 설치용 슈퍼관리자. 조직 소속이 없는 시스템 계정이라 insertUser와 컬럼 구성이 다르다. */
    void insertSystemAdmin(@Param("username") String username,
                           @Param("password") String password,
                           @Param("name") String name);
    void updateUser(UserAccount user);
    void updateBasicInfo(UserAccount user);
    void updatePassword(@Param("userId") Long userId, @Param("password") String password);
    void updateMustChangePassword(@Param("userId") Long userId, @Param("value") boolean value);
    void updateOtp(@Param("userId") Long userId, @Param("otpSecret") String otpSecret, @Param("otpEnabled") boolean otpEnabled);
    void updateUserTeam(@Param("userId") Long userId, @Param("teamId") Long teamId);
    void updateProfileImageFilename(@Param("userId") Long userId, @Param("profileImageFilename") String profileImageFilename);
    void updateSignatureFilename(@Param("userId") Long userId, @Param("signatureFilename") String signatureFilename);
    int countActiveUsersByTeamId(Long teamId);
    void deactivateUser(Long userId);
    void addRole(@Param("userId") Long userId, @Param("roleCode") String roleCode);
    void removeRole(@Param("userId") Long userId, @Param("roleCode") String roleCode);
}
