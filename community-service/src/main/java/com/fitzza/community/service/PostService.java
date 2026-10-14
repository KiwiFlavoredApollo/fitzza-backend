package com.fitzza.community.service;

import com.fitzza.community.client.SharedTargetGateway;
import com.fitzza.community.client.UserDirectory;
import com.fitzza.community.domain.Post;
import com.fitzza.community.domain.PostCategory;
import com.fitzza.community.domain.PostLikeId;
import com.fitzza.community.domain.PostType;
import com.fitzza.community.domain.SharedSnapshot;
import com.fitzza.community.dto.AuthorResponse;
import com.fitzza.community.dto.LikeCountResponse;
import com.fitzza.community.dto.LikeStatusResponse;
import com.fitzza.community.dto.PostCreateRequest;
import com.fitzza.community.dto.PostCreatedResponse;
import com.fitzza.community.dto.PostDetailResponse;
import com.fitzza.community.dto.PostPageResponse;
import com.fitzza.community.dto.PostSummaryResponse;
import com.fitzza.community.dto.PostUpdateRequest;
import com.fitzza.community.dto.SharedResponse;
import com.fitzza.community.dto.VoteOptionResponse;
import com.fitzza.community.exception.CommunityApiException;
import com.fitzza.community.exception.ErrorCode;
import com.fitzza.community.repository.CommentRepository;
import com.fitzza.community.repository.IdCount;
import com.fitzza.community.repository.PostImage;
import com.fitzza.community.repository.PostLikeRepository;
import com.fitzza.community.repository.PostRepository;
import com.fitzza.community.repository.PostSpecifications;
import com.fitzza.community.repository.VoteOptionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PostService {

    public static final int MAX_PAGE_SIZE = 100;

    // 같은 시각에 쓰인 글의 순서가 페이지마다 바뀌지 않도록 ID를 보조 정렬로 둔다.
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final PostRepository postRepository;
    private final PostLikeRepository postLikeRepository;
    private final CommentRepository commentRepository;
    private final VoteOptionRepository voteOptionRepository;
    private final UserDirectory userDirectory;
    private final VoteService voteService;
    private final SharedTargetGateway sharedTargetGateway;

    public PostService(
            PostRepository postRepository,
            PostLikeRepository postLikeRepository,
            CommentRepository commentRepository,
            VoteOptionRepository voteOptionRepository,
            UserDirectory userDirectory,
            VoteService voteService,
            SharedTargetGateway sharedTargetGateway) {
        this.postRepository = postRepository;
        this.postLikeRepository = postLikeRepository;
        this.commentRepository = commentRepository;
        this.voteOptionRepository = voteOptionRepository;
        this.userDirectory = userDirectory;
        this.voteService = voteService;
        this.sharedTargetGateway = sharedTargetGateway;
    }

    @Transactional(readOnly = true)
    public PostPageResponse getFeed(PostCategory category, PostType postType, String keyword, int page, int size) {
        Pageable pageable =
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), NEWEST_FIRST);
        Page<Post> posts = postRepository.findAll(PostSpecifications.feed(category, postType, keyword), pageable);
        if (posts.isEmpty()) {
            return PostPageResponse.of(List.of(), posts);
        }

        List<Long> postIds = posts.getContent().stream().map(Post::getId).toList();
        Map<Long, Long> likeCounts = IdCount.toMap(postLikeRepository.countByPostIds(postIds));
        Map<Long, Long> commentCounts = IdCount.toMap(commentRepository.countActiveByPostIds(postIds));
        Map<Long, String> nicknames = userDirectory.findNicknames(
                posts.getContent().stream().map(Post::getUserId).toList());
        Map<Long, String> firstOptionImages = firstOptionImagesOf(posts.getContent());

        List<PostSummaryResponse> content = posts.getContent().stream()
                .map(post -> new PostSummaryResponse(
                        post.getId(),
                        post.getTitle(),
                        post.getCategory(),
                        post.getPostType(),
                        nicknames.get(post.getUserId()),
                        post.getCreatedAt(),
                        thumbnailOf(post, firstOptionImages),
                        likeCounts.getOrDefault(post.getId(), 0L),
                        commentCounts.getOrDefault(post.getId(), 0L)))
                .toList();
        return PostPageResponse.of(content, posts);
    }

    // 썸네일은 첨부 이미지, 투표글의 첫 번째 선택지 이미지, 공유 대상 스냅샷 이미지 순으로 고른다.
    private static String thumbnailOf(Post post, Map<Long, String> firstOptionImages) {
        if (hasText(post.getImageUrl())) {
            return post.getImageUrl();
        }
        String optionImage = firstOptionImages.get(post.getId());
        if (hasText(optionImage)) {
            return optionImage;
        }
        SharedSnapshot snapshot = post.getSharedSnapshot();
        return snapshot != null && hasText(snapshot.getImageUrl()) ? snapshot.getImageUrl() : null;
    }

    // 첨부 이미지가 없는 투표글만 선택지 이미지를 찾는다. 해당하는 글이 없으면 조회하지 않는다.
    private Map<Long, String> firstOptionImagesOf(List<Post> posts) {
        List<Long> voteIdsWithoutImage = posts.stream()
                .filter(post -> post.isVote() && !hasText(post.getImageUrl()))
                .map(Post::getId)
                .toList();
        if (voteIdsWithoutImage.isEmpty()) {
            return Map.of();
        }
        return PostImage.toMap(voteOptionRepository.findFirstOptionImages(voteIdsWithoutImage));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    // 상세를 열 때마다 조회수가 오른다. viewerId는 비로그인이면 null이다.
    @Transactional
    public PostDetailResponse getDetail(Long postId, Long viewerId) {
        if (postRepository.incrementViewCount(postId) == 0) {
            throw new CommunityApiException(ErrorCode.POST_NOT_FOUND);
        }
        Post post = findVisiblePost(postId);
        boolean liked = viewerId != null && postLikeRepository.existsById(new PostLikeId(postId, viewerId));
        String nickname = userDirectory.findNicknames(List.of(post.getUserId())).get(post.getUserId());
        List<VoteOptionResponse> voteOptions = post.isVote()
                ? voteOptionRepository.findByPostIdOrderByDisplayOrderAsc(postId).stream()
                        .map(VoteOptionResponse::from)
                        .toList()
                : List.of();
        return new PostDetailResponse(
                post.getId(),
                post.getTitle(),
                post.getContent(),
                post.getCategory(),
                post.getPostType(),
                new AuthorResponse(post.getUserId(), nickname),
                post.getImageUrl(),
                SharedResponse.of(post),
                postLikeRepository.countByPostId(postId),
                liked,
                commentRepository.countByPostIdAndDeletedFalse(postId),
                post.getViewCount(),
                post.getCreatedAt(),
                post.getUpdatedAt(),
                post.getVoteEndAt(),
                post.isVote() && post.isVoteClosedAt(Instant.now()),
                voteOptions);
    }

    @Transactional
    public PostCreatedResponse create(Long userId, PostCreateRequest request) {
        // 남의 결과를 공유하려는 요청은 글을 만들기 전에 거른다.
        SharedSnapshot snapshot = request.sharedType() == null
                ? null
                : sharedTargetGateway.resolve(userId, request.sharedType(), request.sharedId());
        Post post = Post.createNormal(
                userId, request.title(), request.content(), request.category(), request.imageUrl());
        if (snapshot != null) {
            post.attachShared(request.sharedType(), request.sharedId(), snapshot);
        }
        return new PostCreatedResponse(postRepository.save(post).getId());
    }

    @Transactional
    public void update(Long userId, Long postId, PostUpdateRequest request) {
        // 선택지를 먼저 바꾼다. 거절되면 같은 트랜잭션의 제목·본문 변경도 함께 취소된다.
        if (request.getOptions() != null) {
            voteService.replaceOptions(userId, postId, request.getOptions());
        }
        Post post = findOwnPost(userId, postId);
        if (request.getTitle() != null) {
            post.changeTitle(request.getTitle());
        }
        if (request.getContent() != null) {
            post.changeContent(request.getContent());
        }
        if (request.getCategory() != null) {
            post.changeCategory(request.getCategory());
        }
        if (request.isImageUrlSent()) {
            post.changeImageUrl(request.getImageUrl());
        }
    }

    @Transactional
    public void delete(Long userId, Long postId) {
        findOwnPost(userId, postId).delete();
    }

    @Transactional(readOnly = true)
    public LikeStatusResponse getLikeStatus(Long userId, Long postId) {
        requireVisiblePost(postId);
        return new LikeStatusResponse(
                postLikeRepository.countByPostId(postId),
                postLikeRepository.existsById(new PostLikeId(postId, userId)));
    }

    @Transactional
    public LikeCountResponse like(Long userId, Long postId) {
        requireVisiblePost(postId);
        postLikeRepository.insertIfAbsent(postId, userId);
        return new LikeCountResponse(postLikeRepository.countByPostId(postId));
    }

    @Transactional
    public LikeCountResponse unlike(Long userId, Long postId) {
        requireVisiblePost(postId);
        postLikeRepository.deleteLike(postId, userId);
        return new LikeCountResponse(postLikeRepository.countByPostId(postId));
    }

    private Post findOwnPost(Long userId, Long postId) {
        Post post = findVisiblePost(postId);
        if (!post.isWrittenBy(userId)) {
            throw new CommunityApiException(ErrorCode.NOT_AUTHOR);
        }
        return post;
    }

    private Post findVisiblePost(Long postId) {
        return postRepository
                .findByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new CommunityApiException(ErrorCode.POST_NOT_FOUND));
    }

    private void requireVisiblePost(Long postId) {
        if (!postRepository.existsByIdAndDeletedAtIsNull(postId)) {
            throw new CommunityApiException(ErrorCode.POST_NOT_FOUND);
        }
    }
}
