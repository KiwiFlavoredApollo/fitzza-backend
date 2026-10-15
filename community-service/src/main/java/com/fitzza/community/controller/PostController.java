package com.fitzza.community.controller;

import com.fitzza.community.config.OpenApiConfig;
import com.fitzza.community.domain.PostCategory;
import com.fitzza.community.domain.PostType;
import com.fitzza.community.dto.ErrorResponse;
import com.fitzza.community.dto.LikeCountResponse;
import com.fitzza.community.dto.LikeStatusResponse;
import com.fitzza.community.dto.PostCreateRequest;
import com.fitzza.community.dto.PostCreatedResponse;
import com.fitzza.community.dto.PostDetailResponse;
import com.fitzza.community.dto.PostPageResponse;
import com.fitzza.community.dto.PostUpdateRequest;
import com.fitzza.community.security.AuthHeaders;
import com.fitzza.community.service.PostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "게시글", description = "커뮤니티 게시글 목록·상세·작성·수정·삭제·좋아요")
@RestController
@RequestMapping("/api/community/posts")
public class PostController {

    private final PostService postService;

    public PostController(PostService postService) {
        this.postService = postService;
    }

    @Operation(
            summary = "게시글 목록",
            description = "최신순. 삭제된 글은 빠진다. 투표 탭은 postType=VOTE로 조회한다. "
                    + "page는 0 이상, size는 1~100이며 벗어나면 400이다. "
                    + "thumbnailUrl은 첨부 이미지, 없으면 투표글의 첫 번째 선택지 이미지다. 둘 다 없으면 null.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공(결과가 없으면 빈 목록)"),
        @ApiResponse(
                responseCode = "400",
                description = "INVALID_REQUEST: 없는 분류, 숫자가 아니거나 범위를 벗어난 page·size",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public PostPageResponse getFeed(
            @Parameter(description = "분류") @RequestParam(name = "category", required = false)
                    PostCategory category,
            @Parameter(description = "글 종류") @RequestParam(name = "postType", required = false)
                    PostType postType,
            @Parameter(description = "제목·내용 검색어") @RequestParam(name = "keyword", required = false)
                    String keyword,
            @Parameter(description = "페이지 번호(0부터)") @RequestParam(name = "page", defaultValue = "0") @Min(0)
                    int page,
            @Parameter(description = "페이지 크기(1~100)") @RequestParam(name = "size", defaultValue = "20")
                    @Min(1) @Max(PostService.MAX_PAGE_SIZE) int size) {
        return postService.getFeed(category, postType, keyword, page, size);
    }

    @Operation(
            summary = "게시글 상세",
            description = "조회할 때마다 조회수가 1 오른다. 토큰을 보내면 liked에 내 좋아요 여부가 담긴다. "
                    + "투표글이면 voteEndAt, voteClosed, voteOptions가 채워진다.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공"),
        @ApiResponse(
                responseCode = "404",
                description = "POST_NOT_FOUND: 없거나 삭제된 글",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{postId}")
    public PostDetailResponse getDetail(
            @PathVariable("postId") Long postId,
            @Parameter(hidden = true) @RequestHeader(name = AuthHeaders.USER_ID, required = false) Long userId) {
        return postService.getDetail(postId, userId);
    }

    @Operation(
            summary = "게시글 작성(일반·투표)",
            description = "제목 100자, 내용 5000자 이하. postType을 보내지 않으면 일반 글이다. "
                    + "투표 글은 postType=VOTE와 options(2~4개)를 함께 보낸다. 마감 시각은 서버가 작성 시각 + 6시간으로 정한다. "
                    + "직접 올린 사진은 source=UPLOAD, itemType=IMAGE로 보내고 itemId는 비운다. 찜한 상품은 source=WISHLIST, "
                    + "itemType=PRODUCT, itemId=상품 ID(숫자)로 보내며, 이미지·상품명·가격은 상품 서비스 값으로 저장한다. "
                    + "AI 추천 코디는 source=AI_RECOMMEND, itemType=COMBO, itemId=코디 ID로 보내며 추천 서비스에서 본인이 받은 "
                    + "코디인지 확인한다. AI 추천 단품(itemType=PRODUCT)은 상품이 있는지만 확인한다. "
                    + "공유 글은 sharedType(WORLDCUP·COMBO·FITTING)과 sharedId(월드컵 이력·추천 코디·가상 피팅 ID)를 함께 보낸다"
                    + "(투표 글은 공유 불가). 본인 결과인지 제공 서비스에서 확인한 뒤 공유 당시 모습을 스냅샷으로 저장한다. "
                    + "가상 피팅은 완료된 결과만 공유할 수 있다.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "작성 성공"),
        @ApiResponse(
                responseCode = "400",
                description = "INVALID_REQUEST: 빈 값이나 길이 초과, 없는 sharedType, sharedType·sharedId 중 하나만 보냄, "
                        + "투표 글에 options가 없거나 일반 글에 options를 보냄, 투표 글에 공유 대상을 보냄 / "
                        + "INVALID_VOTE_OPTIONS: 선택지 구성 오류(중복 등)",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "UNAUTHENTICATED: 로그인 필요",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "403",
                description = "NOT_SHARED_OWNER: 남의 월드컵·코디·피팅 결과 / NOT_WISHLISTED_ITEM: 찜하지 않은 상품을 골랐음 / "
                        + "NOT_RECOMMENDED_ITEM: 남이 받은 추천 코디를 골랐음",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "SHARED_TARGET_NOT_FOUND: 없는 월드컵·코디·피팅 결과",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "409",
                description = "SHARED_TARGET_NOT_READY: 아직 완료되지 않은 가상 피팅",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "503",
                description = "SHARE_LOOKUP_UNAVAILABLE: 상품·추천·피팅 서비스에서 공유 대상을 확인하지 못함 / "
                        + "PRODUCT_LOOKUP_UNAVAILABLE: 상품 서비스에서 찜·추천 상품을 확인하지 못함 / "
                        + "RECOMMENDATION_LOOKUP_UNAVAILABLE: 추천 서비스에서 AI 추천 코디를 확인하지 못함",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PostCreatedResponse create(
            @Parameter(hidden = true) @RequestHeader(AuthHeaders.USER_ID) Long userId,
            @Valid @RequestBody PostCreateRequest request) {
        return postService.create(userId, request);
    }

    @Operation(
            summary = "게시글 수정",
            description = "작성자만 수정할 수 있다. 보내지 않은 항목은 그대로 둔다. 투표글은 options(2~4개)를 보내면 "
                    + "선택지를 통째로 바꾸며, 한 표라도 들어왔거나 마감된 투표는 선택지를 바꿀 수 없다(제목·본문 등은 수정 가능). "
                    + "수정 화면은 게시글 상세 조회의 voteOptions로 기존 선택지를 채운다.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "수정 성공(응답 본문 없음)", content = @Content),
        @ApiResponse(
                responseCode = "400",
                description = "INVALID_REQUEST / NOT_VOTE_POST: 일반 글에 options를 보냄 / "
                        + "INVALID_VOTE_OPTIONS: 선택지 구성 오류",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "UNAUTHENTICATED: 로그인 필요",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "403",
                description = "NOT_AUTHOR: 작성자가 아님 / NOT_WISHLISTED_ITEM: 찜하지 않은 상품을 골랐음",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "POST_NOT_FOUND",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "409",
                description = "CONCURRENT_UPDATE: 다른 수정·삭제 요청과 겹침 / VOTE_OPTIONS_LOCKED: 투표가 시작돼 선택지 수정 불가 / "
                        + "VOTE_CLOSED: 마감된 투표",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "503",
                description = "PRODUCT_LOOKUP_UNAVAILABLE: 상품 서비스에서 찜 상품을 확인하지 못함",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{postId}")
    public void update(
            @Parameter(hidden = true) @RequestHeader(AuthHeaders.USER_ID) Long userId,
            @PathVariable("postId") Long postId,
            @Valid @RequestBody PostUpdateRequest request) {
        postService.update(userId, postId, request);
    }

    @Operation(
            summary = "게시글 삭제",
            description = "작성자만 삭제할 수 있다. 삭제된 글은 목록과 상세에서 보이지 않는다.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "삭제 성공", content = @Content),
        @ApiResponse(
                responseCode = "401",
                description = "UNAUTHENTICATED: 로그인 필요",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "403",
                description = "NOT_AUTHOR: 작성자가 아님",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "POST_NOT_FOUND",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "409",
                description = "CONCURRENT_UPDATE: 다른 수정·삭제 요청과 겹침. 새로고침 후 다시 시도",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{postId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @Parameter(hidden = true) @RequestHeader(AuthHeaders.USER_ID) Long userId,
            @PathVariable("postId") Long postId) {
        postService.delete(userId, postId);
    }

    @Operation(
            summary = "게시글 좋아요 조회",
            description = "좋아요 수와 내가 좋아요했는지(liked)를 돌려준다.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "좋아요 수와 내 좋아요 여부"),
        @ApiResponse(
                responseCode = "401",
                description = "UNAUTHENTICATED: 로그인 필요",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "POST_NOT_FOUND",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{postId}/likes")
    public LikeStatusResponse getLikeStatus(
            @Parameter(hidden = true) @RequestHeader(AuthHeaders.USER_ID) Long userId,
            @PathVariable("postId") Long postId) {
        return postService.getLikeStatus(userId, postId);
    }

    @Operation(
            summary = "게시글 좋아요",
            description = "한 사람당 한 번. 이미 좋아요한 글에 다시 요청해도 같은 결과다.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "현재 좋아요 수"),
        @ApiResponse(
                responseCode = "401",
                description = "UNAUTHENTICATED: 로그인 필요",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "POST_NOT_FOUND",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{postId}/likes")
    public LikeCountResponse like(
            @Parameter(hidden = true) @RequestHeader(AuthHeaders.USER_ID) Long userId,
            @PathVariable("postId") Long postId) {
        return postService.like(userId, postId);
    }

    @Operation(
            summary = "게시글 좋아요 취소",
            description = "좋아요하지 않은 글에 요청해도 같은 결과다.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "현재 좋아요 수"),
        @ApiResponse(
                responseCode = "401",
                description = "UNAUTHENTICATED: 로그인 필요",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "POST_NOT_FOUND",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{postId}/likes")
    public LikeCountResponse unlike(
            @Parameter(hidden = true) @RequestHeader(AuthHeaders.USER_ID) Long userId,
            @PathVariable("postId") Long postId) {
        return postService.unlike(userId, postId);
    }
}
