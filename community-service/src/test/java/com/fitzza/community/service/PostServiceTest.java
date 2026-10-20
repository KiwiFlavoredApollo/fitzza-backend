package com.fitzza.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fitzza.community.client.SharedTargetGateway;
import com.fitzza.community.client.SharedTargetGateway.SharedTarget;
import com.fitzza.community.client.UserDirectory;
import com.fitzza.community.domain.Post;
import com.fitzza.community.domain.PostCategory;
import com.fitzza.community.domain.PostLikeId;
import com.fitzza.community.domain.PostType;
import com.fitzza.community.domain.SharedItem;
import com.fitzza.community.domain.SharedSnapshot;
import com.fitzza.community.domain.SharedType;
import com.fitzza.community.domain.VoteItemType;
import com.fitzza.community.domain.VoteOption;
import com.fitzza.community.domain.VoteOptionSource;
import com.fitzza.community.dto.LikeCountResponse;
import com.fitzza.community.dto.LikeStatusResponse;
import com.fitzza.community.dto.PostCreateRequest;
import com.fitzza.community.dto.PostCreatedResponse;
import com.fitzza.community.dto.PostDetailResponse;
import com.fitzza.community.dto.PostPageResponse;
import com.fitzza.community.dto.PostSummaryResponse;
import com.fitzza.community.dto.PostUpdateRequest;
import com.fitzza.community.dto.VoteOptionRequest;
import com.fitzza.community.exception.CommunityApiException;
import com.fitzza.community.exception.ErrorCode;
import com.fitzza.community.repository.CommentRepository;
import com.fitzza.community.repository.IdCount;
import com.fitzza.community.repository.PostImage;
import com.fitzza.community.repository.PostLikeRepository;
import com.fitzza.community.repository.PostRepository;
import com.fitzza.community.repository.VoteOptionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

class PostServiceTest {

    private static final Long AUTHOR_ID = 7L;
    private static final Long OTHER_ID = 8L;
    private static final Long POST_ID = 10L;
    private static final String IMAGE_URL = "https://cdn.example.com/posts/1.png";
    private static final String OPTION_IMAGE_URL = "https://cdn.example.com/options/a.png";

    private final PostRepository postRepository = mock(PostRepository.class);
    private final PostLikeRepository postLikeRepository = mock(PostLikeRepository.class);
    private final CommentRepository commentRepository = mock(CommentRepository.class);
    private final VoteOptionRepository voteOptionRepository = mock(VoteOptionRepository.class);
    private final UserDirectory userDirectory = mock(UserDirectory.class);
    private final VoteService voteService = mock(VoteService.class);
    private final SharedTargetGateway sharedTargetGateway = mock(SharedTargetGateway.class);
    private final PostService postService = new PostService(
            postRepository,
            postLikeRepository,
            commentRepository,
            voteOptionRepository,
            userDirectory,
            voteService,
            sharedTargetGateway);

    @Test
    void createStoresTrimmedNormalPostForTheHeaderUser() {
        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> {
            Post saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", POST_ID);
            return saved;
        });

        PostCreatedResponse response = postService.create(
                AUTHOR_ID, new PostCreateRequest("  오늘의 코디  ", "  내용입니다  ", PostCategory.DAILY, null, null, null, null));

        assertThat(response.postId()).isEqualTo(POST_ID);
        ArgumentCaptor<Post> captor = ArgumentCaptor.forClass(Post.class);
        verify(postRepository).save(captor.capture());
        Post saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(AUTHOR_ID);
        assertThat(saved.getPostType()).isEqualTo(PostType.NORMAL);
        assertThat(saved.getTitle()).isEqualTo("오늘의 코디");
        assertThat(saved.getContent()).isEqualTo("내용입니다");
        assertThat(saved.getCategory()).isEqualTo(PostCategory.DAILY);
        assertThat(saved.isShared()).isFalse();
        verifyNoInteractions(sharedTargetGateway);
    }

    @Test
    void createOfAVotePostIsHandedToTheVoteService() {
        PostCreateRequest request = new PostCreateRequest(
                "뭐가 나을까요", "골라주세요", PostCategory.FASHION, null, null, PostType.VOTE, List.of(
                        new VoteOptionRequest(VoteOptionSource.WISHLIST, VoteItemType.PRODUCT, 55L, OPTION_IMAGE_URL),
                        new VoteOptionRequest(VoteOptionSource.WISHLIST, VoteItemType.PRODUCT, 56L, OPTION_IMAGE_URL)));
        when(voteService.createVotePost(AUTHOR_ID, request)).thenReturn(new PostCreatedResponse(POST_ID));

        assertThat(postService.create(AUTHOR_ID, request).postId()).isEqualTo(POST_ID);
        verify(postRepository, never()).save(any(Post.class));
        verifyNoInteractions(sharedTargetGateway);
    }

    @Test
    void createSharingAWorldcupResultStoresTheTargetAndItsSnapshot() {
        SharedSnapshot snapshot = new SharedSnapshot(55L, "Fitzza", "오버핏 셔츠", 39000, OPTION_IMAGE_URL);
        when(sharedTargetGateway.resolve(AUTHOR_ID, SharedType.WORLDCUP, 9L))
                .thenReturn(new SharedTarget(snapshot, List.of()));
        when(postRepository.save(any(Post.class))).then(invocation -> invocation.getArgument(0));

        postService.create(AUTHOR_ID, new PostCreateRequest(
                "월드컵 결과", "우승 상품이에요", PostCategory.FASHION, SharedType.WORLDCUP, 9L, null, null));

        ArgumentCaptor<Post> captor = ArgumentCaptor.forClass(Post.class);
        verify(postRepository).save(captor.capture());
        assertThat(captor.getValue().getSharedType()).isEqualTo(SharedType.WORLDCUP);
        assertThat(captor.getValue().getSharedId()).isEqualTo(9L);
        assertThat(captor.getValue().getSharedSnapshot()).isSameAs(snapshot);
    }

    @Test
    void createSharingAComboStoresItsItemsWithTheSnapshot() {
        SharedSnapshot snapshot = new SharedSnapshot(null, null, null, 89000, OPTION_IMAGE_URL, "잘 어울려요");
        SharedItem shirt = new SharedItem(1L, "셔츠", 39000, OPTION_IMAGE_URL);
        when(sharedTargetGateway.resolve(AUTHOR_ID, SharedType.COMBO, 31L))
                .thenReturn(new SharedTarget(snapshot, List.of(shirt)));
        when(postRepository.save(any(Post.class))).then(invocation -> invocation.getArgument(0));

        postService.create(AUTHOR_ID, new PostCreateRequest(
                "추천 코디", "어때요", PostCategory.FASHION, SharedType.COMBO, 31L, null, null));

        ArgumentCaptor<Post> captor = ArgumentCaptor.forClass(Post.class);
        verify(postRepository).save(captor.capture());
        assertThat(captor.getValue().getSharedType()).isEqualTo(SharedType.COMBO);
        assertThat(captor.getValue().getSharedSnapshot()).isSameAs(snapshot);
        assertThat(captor.getValue().getSharedItems()).containsExactly(shirt);
    }

    @Test
    void createSharingSomeoneElsesResultSavesNothing() {
        when(sharedTargetGateway.resolve(AUTHOR_ID, SharedType.WORLDCUP, 9L))
                .thenThrow(new CommunityApiException(ErrorCode.NOT_SHARED_OWNER));

        assertThatThrownBy(() -> postService.create(AUTHOR_ID, new PostCreateRequest(
                        "월드컵 결과", "남의 결과", PostCategory.FASHION, SharedType.WORLDCUP, 9L, null, null)))
                .isInstanceOf(CommunityApiException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_SHARED_OWNER);
        verify(postRepository, never()).save(any(Post.class));
    }

    @Test
    void feedUsesTheSharedSnapshotImageWhenThereIsNoOtherImage() {
        Post shared = post(3L, AUTHOR_ID);
        shared.attachShared(SharedType.WORLDCUP, 9L,
                new SharedSnapshot(55L, "Fitzza", "오버핏 셔츠", 39000, OPTION_IMAGE_URL));
        when(postRepository.findAll(ArgumentMatchers.<Specification<Post>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(shared), PageRequest.of(0, 20), 1));

        PostPageResponse feed = postService.getFeed(null, null, null, 0, 20);

        assertThat(feed.content().get(0).thumbnailUrl()).isEqualTo(OPTION_IMAGE_URL);
        verifyNoInteractions(voteOptionRepository);
    }

    @Test
    void feedFillsCountsAndNicknamesForThePage() {
        Post newer = post(2L, AUTHOR_ID);
        Post older = post(1L, OTHER_ID);
        when(postRepository.findAll(ArgumentMatchers.<Specification<Post>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(newer, older), PageRequest.of(0, 20), 2));
        when(postLikeRepository.countByPostIds(List.of(2L, 1L))).thenReturn(List.of(new IdCount(2L, 3L)));
        when(commentRepository.countActiveByPostIds(List.of(2L, 1L))).thenReturn(List.of(new IdCount(1L, 5L)));
        when(userDirectory.findNicknames(List.of(AUTHOR_ID, OTHER_ID))).thenReturn(Map.of(AUTHOR_ID, "fitzza"));

        PostPageResponse feed = postService.getFeed(PostCategory.FASHION, null, "코디", 0, 20);

        assertThat(feed.page()).isZero();
        assertThat(feed.totalPages()).isEqualTo(1);
        assertThat(feed.content()).hasSize(2);
        assertThat(feed.content().get(0).postId()).isEqualTo(2L);
        assertThat(feed.content().get(0).nickname()).isEqualTo("fitzza");
        assertThat(feed.content().get(0).thumbnailUrl()).isNull();
        assertThat(feed.content().get(0).likeCount()).isEqualTo(3);
        assertThat(feed.content().get(0).commentCount()).isZero();
        assertThat(feed.content().get(1).postId()).isEqualTo(1L);
        assertThat(feed.content().get(1).nickname()).isNull();
        assertThat(feed.content().get(1).likeCount()).isZero();
        assertThat(feed.content().get(1).commentCount()).isEqualTo(5);
    }

    // 투표글은 첫 번째 선택지 이미지를 썸네일로 쓰고, 일반 글은 선택지를 조회하지 않는다.
    @Test
    void voteUsesItsFirstOptionImageAsTheThumbnail() {
        Post voteWithOptionImage = votePost(3L);
        Post voteWithoutOptionImage = votePost(4L);
        Post normal = post(5L, AUTHOR_ID);
        when(postRepository.findAll(ArgumentMatchers.<Specification<Post>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(
                        List.of(voteWithOptionImage, voteWithoutOptionImage, normal), PageRequest.of(0, 20), 3));
        when(voteOptionRepository.findFirstOptionImages(List.of(3L, 4L)))
                .thenReturn(List.of(new PostImage(3L, OPTION_IMAGE_URL)));

        PostPageResponse feed = postService.getFeed(null, null, null, 0, 20);

        assertThat(feed.content())
                .extracting(PostSummaryResponse::thumbnailUrl)
                .containsExactly(OPTION_IMAGE_URL, null, null);
        verify(voteOptionRepository).findFirstOptionImages(List.of(3L, 4L));
    }

    @Test
    void feedClampsPagingAndSkipsLookupsWhenNothingMatches() {
        when(postRepository.findAll(ArgumentMatchers.<Specification<Post>>any(), any(Pageable.class)))
                .thenReturn(Page.<Post>empty());

        PostPageResponse feed = postService.getFeed(null, null, null, -3, 500);

        assertThat(feed.content()).isEmpty();
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(postRepository).findAll(ArgumentMatchers.<Specification<Post>>any(), captor.capture());
        Pageable pageable = captor.getValue();
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(PostService.MAX_PAGE_SIZE);
        assertThat(pageable.getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        verifyNoInteractions(postLikeRepository, commentRepository, userDirectory);
    }

    @Test
    void detailShowsTheSharedTargetAndItsSnapshot() {
        Post post = post(POST_ID, AUTHOR_ID);
        post.attachShared(SharedType.WORLDCUP, 9L, new SharedSnapshot(55L, "Fitzza", "오버핏 셔츠", 39000, OPTION_IMAGE_URL));
        when(postRepository.incrementViewCount(POST_ID)).thenReturn(1);
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Optional.of(post));

        PostDetailResponse detail = postService.getDetail(POST_ID, null);

        assertThat(detail.shared().type()).isEqualTo(SharedType.WORLDCUP);
        assertThat(detail.shared().id()).isEqualTo(9L);
        assertThat(detail.shared().snapshot().productName()).isEqualTo("오버핏 셔츠");
        assertThat(detail.shared().snapshot().imageUrl()).isEqualTo(OPTION_IMAGE_URL);
    }

    @Test
    void detailCountsTheViewAndTellsWhetherTheViewerLikedIt() {
        Post post = post(POST_ID, AUTHOR_ID);
        when(postRepository.incrementViewCount(POST_ID)).thenReturn(1);
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Optional.of(post));
        when(postLikeRepository.existsById(new PostLikeId(POST_ID, OTHER_ID))).thenReturn(true);
        when(postLikeRepository.countByPostId(POST_ID)).thenReturn(4L);
        when(commentRepository.countByPostIdAndDeletedFalse(POST_ID)).thenReturn(2L);
        when(userDirectory.findNicknames(List.of(AUTHOR_ID))).thenReturn(Map.of(AUTHOR_ID, "fitzza"));

        PostDetailResponse detail = postService.getDetail(POST_ID, OTHER_ID);

        verify(postRepository).incrementViewCount(POST_ID);
        assertThat(detail.postId()).isEqualTo(POST_ID);
        assertThat(detail.author().userId()).isEqualTo(AUTHOR_ID);
        assertThat(detail.author().nickname()).isEqualTo("fitzza");
        assertThat(detail.liked()).isTrue();
        assertThat(detail.likeCount()).isEqualTo(4);
        assertThat(detail.commentCount()).isEqualTo(2);
        assertThat(detail.voteEndAt()).isNull();
        assertThat(detail.voteClosed()).isFalse();
        assertThat(detail.voteOptions()).isEmpty();
        assertThat(detail.shared()).isNull();
        verifyNoInteractions(voteOptionRepository);
    }

    @Test
    void detailOfAVotePostCarriesItsOptionsAndDeadline() {
        Post votePost = Post.createVote(AUTHOR_ID, "제목", "내용", PostCategory.COORDI_QUESTION, Instant.now());
        ReflectionTestUtils.setField(votePost, "id", POST_ID);
        VoteOption option = VoteOption.of(
                POST_ID, 1, VoteOptionSource.WISHLIST, VoteItemType.PRODUCT, "55", IMAGE_URL, "셔츠", 39000);
        ReflectionTestUtils.setField(option, "id", 100L);
        when(postRepository.incrementViewCount(POST_ID)).thenReturn(1);
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Optional.of(votePost));
        when(voteOptionRepository.findByPostIdOrderByDisplayOrderAsc(POST_ID)).thenReturn(List.of(option));

        PostDetailResponse detail = postService.getDetail(POST_ID, null);

        assertThat(detail.postType()).isEqualTo(PostType.VOTE);
        assertThat(detail.voteEndAt()).isEqualTo(votePost.getVoteEndAt());
        assertThat(detail.voteClosed()).isFalse();
        assertThat(detail.voteOptions()).hasSize(1);
        assertThat(detail.voteOptions().get(0).voteOptionId()).isEqualTo(100L);
        assertThat(detail.voteOptions().get(0).label()).isEqualTo("A");
        assertThat(detail.voteOptions().get(0).imageUrl()).isEqualTo(IMAGE_URL);
    }

    @Test
    void detailForAnonymousViewerIsNeverLiked() {
        when(postRepository.incrementViewCount(POST_ID)).thenReturn(1);
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID))
                .thenReturn(Optional.of(post(POST_ID, AUTHOR_ID)));

        PostDetailResponse detail = postService.getDetail(POST_ID, null);

        assertThat(detail.liked()).isFalse();
        assertThat(detail.author().nickname()).isNull();
        verify(postLikeRepository, never()).existsById(any());
    }

    @Test
    void detailOfMissingOrDeletedPostIsNotFound() {
        when(postRepository.incrementViewCount(POST_ID)).thenReturn(0);

        assertThatThrownBy(() -> postService.getDetail(POST_ID, OTHER_ID))
                .isInstanceOf(CommunityApiException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.POST_NOT_FOUND);
        verify(postRepository, never()).findByIdAndDeletedAtIsNull(anyLong());
    }

    @Test
    void updateChangesOnlyTheFieldsThatWereSent() {
        Post post = post(POST_ID, AUTHOR_ID);
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Optional.of(post));
        PostUpdateRequest request = new PostUpdateRequest();
        request.setTitle("  새 제목  ");

        postService.update(AUTHOR_ID, POST_ID, request);

        assertThat(post.getTitle()).isEqualTo("새 제목");
        assertThat(post.getContent()).isEqualTo("내용");
        assertThat(post.getCategory()).isEqualTo(PostCategory.FASHION);
        verifyNoInteractions(voteService);
    }

    @Test
    void updateHandsSentOptionsToTheVoteServiceBeforeChangingTheText() {
        Post post = votePost(POST_ID);
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Optional.of(post));
        List<VoteOptionRequest> options = List.of(
                new VoteOptionRequest(VoteOptionSource.WISHLIST, VoteItemType.PRODUCT, 55L, OPTION_IMAGE_URL),
                new VoteOptionRequest(VoteOptionSource.WISHLIST, VoteItemType.PRODUCT, 56L, OPTION_IMAGE_URL));
        PostUpdateRequest request = new PostUpdateRequest();
        request.setTitle("바뀐 투표");
        request.setOptions(options);

        postService.update(AUTHOR_ID, POST_ID, request);

        verify(voteService).replaceOptions(AUTHOR_ID, POST_ID, options);
        assertThat(post.getTitle()).isEqualTo("바뀐 투표");
    }

    @Test
    void rejectedOptionsStopTheWholeUpdate() {
        PostUpdateRequest request = new PostUpdateRequest();
        request.setTitle("바뀐 투표");
        request.setOptions(List.of());
        doThrow(new CommunityApiException(ErrorCode.VOTE_OPTIONS_LOCKED))
                .when(voteService).replaceOptions(AUTHOR_ID, POST_ID, List.of());

        assertThatThrownBy(() -> postService.update(AUTHOR_ID, POST_ID, request))
                .isInstanceOf(CommunityApiException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VOTE_OPTIONS_LOCKED);
        verify(postRepository, never()).findByIdAndDeletedAtIsNull(anyLong());
    }

    @Test
    void updateByAnotherUserIsRejected() {
        Post post = post(POST_ID, AUTHOR_ID);
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Optional.of(post));
        PostUpdateRequest request = new PostUpdateRequest();
        request.setTitle("남의 글 고치기");

        assertThatThrownBy(() -> postService.update(OTHER_ID, POST_ID, request))
                .isInstanceOf(CommunityApiException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_AUTHOR);
        assertThat(post.getTitle()).isEqualTo("제목");
    }

    @Test
    void deleteKeepsTheRowAndMarksItDeleted() {
        Post post = post(POST_ID, AUTHOR_ID);
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Optional.of(post));

        postService.delete(AUTHOR_ID, POST_ID);

        assertThat(post.isDeleted()).isTrue();
        verify(postRepository, never()).delete(any(Post.class));
    }

    @Test
    void deleteByAnotherUserIsRejected() {
        Post post = post(POST_ID, AUTHOR_ID);
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Optional.of(post));

        assertThatThrownBy(() -> postService.delete(OTHER_ID, POST_ID))
                .isInstanceOf(CommunityApiException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_AUTHOR);
        assertThat(post.isDeleted()).isFalse();
    }

    @Test
    void likeInsertsOnceAndReturnsTheCurrentCount() {
        when(postRepository.existsByIdAndDeletedAtIsNull(POST_ID)).thenReturn(true);
        when(postLikeRepository.countByPostId(POST_ID)).thenReturn(1L);

        LikeCountResponse response = postService.like(OTHER_ID, POST_ID);

        verify(postLikeRepository).insertIfAbsent(POST_ID, OTHER_ID);
        assertThat(response.likeCount()).isEqualTo(1);
    }

    @Test
    void unlikeRemovesTheLikeAndReturnsTheCurrentCount() {
        when(postRepository.existsByIdAndDeletedAtIsNull(POST_ID)).thenReturn(true);
        when(postLikeRepository.countByPostId(POST_ID)).thenReturn(0L);

        LikeCountResponse response = postService.unlike(OTHER_ID, POST_ID);

        verify(postLikeRepository).deleteLike(POST_ID, OTHER_ID);
        assertThat(response.likeCount()).isZero();
    }

    @Test
    void likeStatusReturnsTheCountAndWhetherTheUserLikedIt() {
        when(postRepository.existsByIdAndDeletedAtIsNull(POST_ID)).thenReturn(true);
        when(postLikeRepository.countByPostId(POST_ID)).thenReturn(4L);
        when(postLikeRepository.existsById(new PostLikeId(POST_ID, OTHER_ID))).thenReturn(true);

        LikeStatusResponse response = postService.getLikeStatus(OTHER_ID, POST_ID);

        assertThat(response.likeCount()).isEqualTo(4);
        assertThat(response.liked()).isTrue();
    }

    @Test
    void likeStatusOfMissingOrDeletedPostIsNotFound() {
        when(postRepository.existsByIdAndDeletedAtIsNull(POST_ID)).thenReturn(false);

        assertThatThrownBy(() -> postService.getLikeStatus(OTHER_ID, POST_ID))
                .isInstanceOf(CommunityApiException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.POST_NOT_FOUND);
    }

    @Test
    void likeOnMissingOrDeletedPostIsNotFound() {
        when(postRepository.existsByIdAndDeletedAtIsNull(POST_ID)).thenReturn(false);

        assertThatThrownBy(() -> postService.like(OTHER_ID, POST_ID))
                .isInstanceOf(CommunityApiException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.POST_NOT_FOUND);
        verify(postLikeRepository, never()).insertIfAbsent(anyLong(), anyLong());
    }

    private static Post votePost(Long postId) {
        Post post = Post.createVote(AUTHOR_ID, "둘 중 뭐가 나아요", "골라주세요", PostCategory.FASHION, Instant.now());
        ReflectionTestUtils.setField(post, "id", postId);
        return post;
    }

    private static Post post(Long postId, Long userId) {
        Post post = Post.createNormal(userId, "제목", "내용", PostCategory.FASHION);
        ReflectionTestUtils.setField(post, "id", postId);
        return post;
    }
}
