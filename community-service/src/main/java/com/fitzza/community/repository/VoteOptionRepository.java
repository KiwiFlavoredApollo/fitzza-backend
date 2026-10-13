package com.fitzza.community.repository;

import com.fitzza.community.domain.VoteOption;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VoteOptionRepository extends JpaRepository<VoteOption, Long> {

    List<VoteOption> findByPostIdOrderByDisplayOrderAsc(Long postId);

    boolean existsByIdAndPostId(Long id, Long postId);

    // 목록 썸네일용으로 글마다 첫 번째 선택지(A)의 이미지를 한 번에 가져온다.
    @Query("select new com.fitzza.community.repository.PostImage(o.postId, o.imageUrl) from VoteOption o"
            + " where o.postId in :postIds and o.displayOrder = 1")
    List<PostImage> findFirstOptionImages(@Param("postIds") Collection<Long> postIds);
}
