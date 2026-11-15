package com.portfolio.worksystem.mapper;

import com.portfolio.worksystem.model.AppRelease;
import com.portfolio.worksystem.model.AppReleasePatch;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AppReleaseMapper {
    void insert(AppRelease release);

    void updatePackageInfo(AppRelease release);

    void insertPatch(AppReleasePatch patch);

    List<AppReleasePatch> findPatchesByReleaseId(@Param("releaseId") Long releaseId);

    AppReleasePatch findPatchByReleaseVersionAndBaseVersion(@Param("version") String version,
                                                            @Param("baseVersion") String baseVersion);

    List<AppRelease> findAll();

    List<AppRelease> findActive();

    AppRelease findByVersion(@Param("version") String version);

    AppRelease findById(@Param("id") Long id);

    void deactivate(@Param("id") Long id);

    void activate(@Param("id") Long id);
}
