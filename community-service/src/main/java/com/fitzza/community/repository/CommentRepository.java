package com.fitzza.community.repository;

import com.fitzza.community.domain.Comment;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    List<Comment> findByPostIdOrderByCreatedAtAscIdAsc(Long postId);

    long countByPostIdAndDeletedFalse(Long postId);

    @Query("select new com.fitzza.community.repository.IdCount(c.postId, count(c)) "
            + "from Comment c where c.postId in :postIds and c.deleted = false group by c.postId")
    List<IdCount> countActiveByPostIds(@Param("postIds") Collection<Long> postIds);

    // 투표 결과의 선택지별 "이 선택지를 추천한 댓글 수". 지운 댓글은 세지 않는다.
    @Query("select new com.fitzza.community.repository.IdCount(c.supportedOptionId, count(c)) from Comment c"
            + " where c.postId = :postId and c.deleted = false and c.supportedOptionId is not null"
            + " group by c.supportedOptionId")
    List<IdCount> countSupportByOption(@Param("postId") Long postId);

    // 판정 결과를 저장한다. 판정하는 동안 댓글이 수정·삭제되면 version이 올라가 아무 행도 바꾸지 않는다
    // (수정된 내용은 수정 뒤에 다시 판정한다). 작성자의 수정과 겹쳐도 그 요청을 실패시키지 않도록 version은 올리지 않는다.
    @Transactional
    @Modifying
    @Query("update Comment c set c.supportedOptionId = :optionId"
            + " where c.id = :commentId and c.version = :version and c.deleted = false")
    int updateSupport(
            @Param("commentId") Long commentId,
            @Param("version") long version,
            @Param("optionId") Long optionId);

    // 선택지를 바꾸면 선택지 ID가 새로 생기므로 이전 판정을 모두 지운다.
    @Modifying(flushAutomatically = true)
    @Query("update Comment c set c.supportedOptionId = null where c.postId = :postId")
    int clearSupportByPostId(@Param("postId") Long postId);
}
