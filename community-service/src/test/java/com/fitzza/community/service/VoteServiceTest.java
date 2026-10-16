package com.fitzza.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fitzza.community.client.VoteItemGateway;
import com.fitzza.community.domain.Post;
import com.fitzza.community.domain.PostCategory;
import com.fitzza.community.domain.PostType;
import com.fitzza.community.domain.Vote;
import com.fitzza.community.domain.VoteItemType;
import com.fitzza.community.domain.VoteOption;
import com.fitzza.community.domain.VoteOptionSource;
import com.fitzza.community.dto.VoteOptionRequest;
import com.fitzza.community.dto.PostCreateRequest;
import com.fitzza.community.dto.PostCreatedResponse;
import com.fitzza.community.dto.VoteResultResponse;
import com.fitzza.community.exception.CommunityApiException;
import com.fitzza.community.exception.ErrorCode;
import com.fitzza.community.repository.CommentRepository;
import com.fitzza.community.repository.IdCount;
import com.fitzza.community.repository.PostRepository;
import com.fitzza.community.repository.VoteOptionRepository;
import com.fitzza.community.repository.VoteRepository;
import com.fitzza.community.votesupport.VoteOptionsReplaced;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

class VoteServiceTest {

    private static final Long AUTHOR_ID = 7L;
    private static final Long VOTER_ID = 8L;
    private static final Long POST_ID = 10L;
    private static final Long OPTION_A = 100L;
    private static final Long OPTION_B = 101L;
    private static final String IMAGE_URL = "https://cdn.example.com/options/1.png";

    // 선택지 확인은 VerifiedVoteItemGatewayTest에서 따로 본다. 여기서는 요청 이미지를 그대로 쓴다.
    private static final VoteItemGateway PASS_THROUGH_GATEWAY = (userId, source, itemType, itemId, imageUrl) ->
            new VoteItemGateway.VoteItemSnapshot(imageUrl, null, null);

    private final PostRepository postRepository = mock(PostRepository.class);
    private final VoteOptionRepository voteOptionRepository = mock(VoteOptionRepository.class);
    private final VoteRepository voteRepository = mock(VoteRepository.class);
    private final CommentRepository commentRepository = mock(CommentRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final VoteService voteService = new VoteService(
            postRepository, voteOptionRepository, voteRepository, PASS_THROUGH_GATEWAY, commentRepository,
            eventPublisher);

    @Test
    void createStoresAVotePostThatClosesInSixHoursWithItsOptionsInOrder() {
        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> {
            Post saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", POST_ID);
            return saved;
        });
        Instant before = Instant.now();

        PostCreatedResponse response = voteService.createVotePost(
                AUTHOR_ID,
                request(
                        new VoteOptionRequest(VoteOptionSource.WISHLIST, VoteItemType.PRODUCT, 55L, IMAGE_URL),
                        new VoteOptionRequest(VoteOptionSource.UPLOAD, VoteItemType.IMAGE, null, IMAGE_URL)));

        assertThat(response.postId()).isEqualTo(POST_ID);
        ArgumentCaptor<Post> post = ArgumentCaptor.forClass(Post.class);
        verify(postRepository).save(post.capture());
        assertThat(post.getValue().getVoteEndAt())
                .isBetween(before.plus(Post.VOTE_DURATION), Instant.now().plus(Post.VOTE_DURATION));
        assertThat(post.getValue().getPostType()).isEqualTo(PostType.VOTE);
        assertThat(post.getValue().getImageUrl()).isEqualTo("https://cdn.example.com/cover.png");
        assertThat(post.getValue().getUserId()).isEqualTo(AUTHOR_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VoteOption>> options = ArgumentCaptor.forClass(List.class);
        verify(voteOptionRepository).saveAll(options.capture());
        assertThat(options.getValue()).hasSize(2);
        VoteOption first = options.getValue().get(0);
        assertThat(first.getPostId()).isEqualTo(POST_ID);
        assertThat(first.getLabel()).isEqualTo("A");
        assertThat(first.getItemId()).isEqualTo("55");
        assertThat(first.getImageUrl()).isEqualTo(IMAGE_URL);
        VoteOption second = options.getValue().get(1);
        assertThat(second.getLabel()).isEqualTo("B");
        assertThat(second.getItemType()).isEqualTo(VoteItemType.IMAGE);
        assertThat(second.getItemId()).isNull();
    }

    @Test
    void createRejectsOptionsThatDoNotFitTogether() {
        VoteOptionRequest product =
                new VoteOptionRequest(VoteOptionSource.WISHLIST, VoteItemType.PRODUCT, 55L, IMAGE_URL);
        VoteOptionRequest upload = new VoteOptionRequest(VoteOptionSource.UPLOAD, VoteItemType.IMAGE, null, IMAGE_URL);

        assertRejected(() -> voteService.createVotePost(AUTHOR_ID, request(product)), ErrorCode.INVALID_VOTE_OPTIONS);
        assertRejected(
                () -> voteService.createVotePost(AUTHOR_ID, request(product, product)),
                ErrorCode.INVALID_VOTE_OPTIONS);
        assertRejected(
                () -> voteService.createVotePost(
                        AUTHOR_ID,
                        request(
                                upload,
                                new VoteOptionRequest(
                                        VoteOptionSource.WISHLIST, VoteItemType.IMAGE, null, IMAGE_URL))),
                ErrorCode.INVALID_VOTE_OPTIONS);
        assertRejected(
                () -> voteService.createVotePost(
                        AUTHOR_ID,
                        request(
                                upload,
                                new VoteOptionRequest(
                                        VoteOptionSource.AI_RECOMMEND, VoteItemType.COMBO, null, IMAGE_URL))),
                ErrorCode.INVALID_VOTE_OPTIONS);
        verifyNoInteractions(postRepository, voteOptionRepository);
    }

    @Test
    void voteRecordsTheChoiceOnce() {
        when(postRepository.findVisibleForUpdate(POST_ID)).thenReturn(Optional.of(openVotePost()));
        when(voteRepository.findByPostIdAndUserId(POST_ID, VOTER_ID)).thenReturn(Optional.empty());
        when(voteOptionRepository.existsByIdAndPostId(OPTION_A, POST_ID)).thenReturn(true);

        assertThat(voteService.vote(VOTER_ID, POST_ID, OPTION_A).voteOptionId()).isEqualTo(OPTION_A);

        verify(voteRepository).insertIfAbsent(anyString(), eq(POST_ID), eq(VOTER_ID), eq(OPTION_A));
    }

    @Test
    void votingAgainReturnsTheFirstChoiceWithoutAnotherVote() {
        Vote firstVote = voteFor(OPTION_A);
        when(postRepository.findVisibleForUpdate(POST_ID)).thenReturn(Optional.of(closedVotePost()));
        when(voteRepository.findByPostIdAndUserId(POST_ID, VOTER_ID)).thenReturn(Optional.of(firstVote));

        assertThat(voteService.vote(VOTER_ID, POST_ID, OPTION_B).voteOptionId()).isEqualTo(OPTION_A);

        verify(voteRepository, never()).insertIfAbsent(anyString(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void voteIsRefusedOnceTheDeadlineHasPassedOrTheAuthorClosedIt() {
        when(voteRepository.findByPostIdAndUserId(POST_ID, VOTER_ID)).thenReturn(Optional.empty());

        when(postRepository.findVisibleForUpdate(POST_ID)).thenReturn(Optional.of(closedVotePost()));
        assertRejected(() -> voteService.vote(VOTER_ID, POST_ID, OPTION_A), ErrorCode.VOTE_CLOSED);

        Post closedEarly = openVotePost();
        closedEarly.closeVote();
        when(postRepository.findVisibleForUpdate(POST_ID)).thenReturn(Optional.of(closedEarly));
        assertRejected(() -> voteService.vote(VOTER_ID, POST_ID, OPTION_A), ErrorCode.VOTE_CLOSED);

        verify(voteRepository, never()).insertIfAbsent(anyString(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void voteIsRefusedForANormalPostAMissingPostOrAnOptionOfAnotherPost() {
        when(postRepository.findVisibleForUpdate(POST_ID)).thenReturn(Optional.of(normalPost()));
        assertRejected(() -> voteService.vote(VOTER_ID, POST_ID, OPTION_A), ErrorCode.NOT_VOTE_POST);

        when(postRepository.findVisibleForUpdate(POST_ID)).thenReturn(Optional.empty());
        assertRejected(() -> voteService.vote(VOTER_ID, POST_ID, OPTION_A), ErrorCode.POST_NOT_FOUND);

        when(postRepository.findVisibleForUpdate(POST_ID)).thenReturn(Optional.of(openVotePost()));
        when(voteRepository.findByPostIdAndUserId(POST_ID, VOTER_ID)).thenReturn(Optional.empty());
        when(voteOptionRepository.existsByIdAndPostId(OPTION_A, POST_ID)).thenReturn(false);
        assertRejected(() -> voteService.vote(VOTER_ID, POST_ID, OPTION_A), ErrorCode.INVALID_VOTE_OPTION);

        verify(voteRepository, never()).insertIfAbsent(anyString(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void resultsCountEachOptionAndTellTheViewerTheirChoice() {
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Optional.of(openVotePost()));
        when(voteOptionRepository.findByPostIdOrderByDisplayOrderAsc(POST_ID))
                .thenReturn(List.of(option(OPTION_A, 1), option(OPTION_B, 2)));
        Vote myVote = voteFor(OPTION_A);
        when(voteRepository.countByOption(POST_ID)).thenReturn(List.of(new IdCount(OPTION_A, 3L)));
        when(voteRepository.findByPostIdAndUserId(POST_ID, VOTER_ID)).thenReturn(Optional.of(myVote));
        when(commentRepository.countSupportByOption(POST_ID)).thenReturn(List.of(new IdCount(OPTION_B, 2L)));

        VoteResultResponse results = voteService.getResults(POST_ID, VOTER_ID);

        assertThat(results.total()).isEqualTo(3);
        assertThat(results.closed()).isFalse();
        assertThat(results.myVoteOptionId()).isEqualTo(OPTION_A);
        assertThat(results.options()).hasSize(2);
        assertThat(results.options().get(0).label()).isEqualTo("A");
        assertThat(results.options().get(0).count()).isEqualTo(3);
        assertThat(results.options().get(0).ratio()).isEqualTo(1.0);
        assertThat(results.options().get(1).label()).isEqualTo("B");
        assertThat(results.options().get(1).count()).isZero();
        assertThat(results.options().get(1).ratio()).isZero();
        assertThat(results.options().get(0).supportCommentCount()).isZero();
        assertThat(results.options().get(1).supportCommentCount()).isEqualTo(2);
    }

    @Test
    void resultsAreOpenToAnonymousViewersAndHandleAVoteNobodyAnswered() {
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Optional.of(closedVotePost()));
        when(voteOptionRepository.findByPostIdOrderByDisplayOrderAsc(POST_ID))
                .thenReturn(List.of(option(OPTION_A, 1), option(OPTION_B, 2)));
        when(voteRepository.countByOption(POST_ID)).thenReturn(List.of());

        VoteResultResponse results = voteService.getResults(POST_ID, null);

        assertThat(results.total()).isZero();
        assertThat(results.closed()).isTrue();
        assertThat(results.myVoteOptionId()).isNull();
        assertThat(results.options().get(0).ratio()).isZero();
        verify(voteRepository, never()).findByPostIdAndUserId(anyLong(), anyLong());
    }

    @Test
    void resultsOfANormalPostAreRefused() {
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Optional.of(normalPost()));

        assertRejected(() -> voteService.getResults(POST_ID, null), ErrorCode.NOT_VOTE_POST);
    }

    @Test
    void onlyTheAuthorCanCloseTheVoteEarly() {
        Post post = openVotePost();
        when(postRepository.findVisibleForUpdate(POST_ID)).thenReturn(Optional.of(post));

        assertRejected(() -> voteService.close(VOTER_ID, POST_ID), ErrorCode.NOT_AUTHOR);
        assertThat(post.isVoteClosedAt(Instant.now())).isFalse();

        assertThat(voteService.close(AUTHOR_ID, POST_ID).closed()).isTrue();
        assertThat(post.isVoteClosedAt(Instant.now())).isTrue();
    }

    @Test
    void authorCanReplaceTheOptionsOfAnOpenVoteNobodyAnswered() {
        when(postRepository.findVisibleForUpdate(POST_ID)).thenReturn(Optional.of(openVotePost()));
        when(voteRepository.existsByPostId(POST_ID)).thenReturn(false);

        voteService.replaceOptions(AUTHOR_ID, POST_ID, List.of(
                new VoteOptionRequest(VoteOptionSource.UPLOAD, VoteItemType.IMAGE, null, IMAGE_URL),
                new VoteOptionRequest(VoteOptionSource.WISHLIST, VoteItemType.PRODUCT, 56L, IMAGE_URL),
                new VoteOptionRequest(VoteOptionSource.WISHLIST, VoteItemType.PRODUCT, 57L, IMAGE_URL)));

        InOrder order = inOrder(voteOptionRepository);
        order.verify(voteOptionRepository).deleteByPostId(POST_ID);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VoteOption>> options = ArgumentCaptor.forClass(List.class);
        order.verify(voteOptionRepository).saveAll(options.capture());
        assertThat(options.getValue())
                .extracting(VoteOption::getPostId, VoteOption::getLabel, VoteOption::getItemId)
                .containsExactly(
                        tuple(POST_ID, "A", null),
                        tuple(POST_ID, "B", "56"),
                        tuple(POST_ID, "C", "57"));
        verify(commentRepository).clearSupportByPostId(POST_ID);
        verify(eventPublisher).publishEvent(new VoteOptionsReplaced(POST_ID));
    }

    @Test
    void optionsCannotBeReplacedOnceSomeoneVoted() {
        when(postRepository.findVisibleForUpdate(POST_ID)).thenReturn(Optional.of(openVotePost()));
        when(voteRepository.existsByPostId(POST_ID)).thenReturn(true);

        assertRejected(
                () -> voteService.replaceOptions(AUTHOR_ID, POST_ID, twoUploads()), ErrorCode.VOTE_OPTIONS_LOCKED);
        verify(voteOptionRepository, never()).deleteByPostId(anyLong());
        verifyNoInteractions(commentRepository, eventPublisher);
    }

    @Test
    void optionsCannotBeReplacedOnAClosedVoteANormalPostOrByAnotherUser() {
        when(postRepository.findVisibleForUpdate(POST_ID)).thenReturn(Optional.of(closedVotePost()));
        assertRejected(() -> voteService.replaceOptions(AUTHOR_ID, POST_ID, twoUploads()), ErrorCode.VOTE_CLOSED);

        when(postRepository.findVisibleForUpdate(POST_ID)).thenReturn(Optional.of(normalPost()));
        assertRejected(() -> voteService.replaceOptions(AUTHOR_ID, POST_ID, twoUploads()), ErrorCode.NOT_VOTE_POST);

        when(postRepository.findVisibleForUpdate(POST_ID)).thenReturn(Optional.of(openVotePost()));
        assertRejected(() -> voteService.replaceOptions(VOTER_ID, POST_ID, twoUploads()), ErrorCode.NOT_AUTHOR);

        when(postRepository.findVisibleForUpdate(POST_ID)).thenReturn(Optional.empty());
        assertRejected(() -> voteService.replaceOptions(AUTHOR_ID, POST_ID, twoUploads()), ErrorCode.POST_NOT_FOUND);
        verify(voteOptionRepository, never()).deleteByPostId(anyLong());
    }

    @Test
    void replacementOptionsFollowTheSameRulesAsCreation() {
        when(postRepository.findVisibleForUpdate(POST_ID)).thenReturn(Optional.of(openVotePost()));
        VoteOptionRequest product =
                new VoteOptionRequest(VoteOptionSource.WISHLIST, VoteItemType.PRODUCT, 55L, IMAGE_URL);

        assertRejected(
                () -> voteService.replaceOptions(AUTHOR_ID, POST_ID, List.of(product, product)),
                ErrorCode.INVALID_VOTE_OPTIONS);
        verify(voteOptionRepository, never()).deleteByPostId(anyLong());
    }

    private static List<VoteOptionRequest> twoUploads() {
        VoteOptionRequest upload = new VoteOptionRequest(VoteOptionSource.UPLOAD, VoteItemType.IMAGE, null, IMAGE_URL);
        return List.of(upload, upload);
    }

    private static PostCreateRequest request(VoteOptionRequest... options) {
        return new PostCreateRequest(
                "뭐가 나을까요", "골라주세요", PostCategory.COORDI_QUESTION, "https://cdn.example.com/cover.png",
                null, null, PostType.VOTE, List.of(options));
    }

    private static Post openVotePost() {
        return votePost(Instant.now());
    }

    private static Post closedVotePost() {
        return votePost(Instant.now().minus(Post.VOTE_DURATION).minus(Duration.ofMinutes(1)));
    }

    private static Post votePost(Instant createdAt) {
        Post post = Post.createVote(AUTHOR_ID, "제목", "내용", PostCategory.COORDI_QUESTION, createdAt);
        ReflectionTestUtils.setField(post, "id", POST_ID);
        return post;
    }

    private static Post normalPost() {
        Post post = Post.createNormal(AUTHOR_ID, "제목", "내용", PostCategory.DAILY, null);
        ReflectionTestUtils.setField(post, "id", POST_ID);
        return post;
    }

    private static VoteOption option(Long optionId, int displayOrder) {
        VoteOption option = VoteOption.of(
                POST_ID, displayOrder, VoteOptionSource.UPLOAD, VoteItemType.IMAGE, null, IMAGE_URL, null, null);
        ReflectionTestUtils.setField(option, "id", optionId);
        return option;
    }

    // 스텁을 거는 도중에 다른 스텁을 만들면 Mockito가 실패하므로, when(...) 바깥에서 먼저 만들어 둔다.
    private static Vote voteFor(Long optionId) {
        Vote vote = mock(Vote.class);
        when(vote.getVoteOptionId()).thenReturn(optionId);
        return vote;
    }

    private static void assertRejected(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(CommunityApiException.class)
                .extracting(exception -> ((CommunityApiException) exception).getErrorCode())
                .isEqualTo(expected);
    }
}
