package com.fitzza.community.controller;

import com.fitzza.community.config.OpenApiConfig;
import com.fitzza.community.dto.ErrorResponse;
import com.fitzza.community.dto.PollCloseRequest;
import com.fitzza.community.dto.VoteCastResponse;
import com.fitzza.community.dto.VoteClosedResponse;
import com.fitzza.community.dto.VotePostCreateRequest;
import com.fitzza.community.dto.VotePostCreatedResponse;
import com.fitzza.community.dto.VoteRequest;
import com.fitzza.community.dto.VoteResultResponse;
import com.fitzza.community.security.AuthHeaders;
import com.fitzza.community.service.VoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "투표", description = "투표 게시글 작성, 투표 참여, 결과 조회, 조기 종료")
@RestController
@RequestMapping("/api/community/posts")
public class VoteController {

    private final VoteService voteService;

    public VoteController(VoteService voteService) {
        this.voteService = voteService;
    }

    @Operation(
            summary = "투표 게시글 작성",
            description = "선택지는 2~4개. 마감 시각은 서버가 작성 시각 + 6시간으로 정한다. 직접 올린 사진은 "
                    + "source=UPLOAD, itemType=IMAGE로 보내고 itemId는 비운다. 찜한 상품은 source=WISHLIST, "
                    + "itemType=PRODUCT, itemId=상품 ID로 보내며, 이미지·상품명·가격은 상품 서비스 값으로 저장한다.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "작성 성공"),
        @ApiResponse(
                responseCode = "400",
                description = "INVALID_REQUEST: 빈 값·선택지 개수 / INVALID_VOTE_OPTIONS: 선택지 구성 오류(중복 등)",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "UNAUTHENTICATED: 로그인 필요",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "403",
                description = "NOT_WISHLISTED_ITEM: 내가 찜하지 않은 상품을 골랐음",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "503",
                description = "PRODUCT_LOOKUP_UNAVAILABLE: 상품 서비스에서 찜 상품을 확인하지 못함",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/vote")
    @ResponseStatus(HttpStatus.CREATED)
    public VotePostCreatedResponse createVotePost(
            @Parameter(hidden = true) @RequestHeader(AuthHeaders.USER_ID) Long userId,
            @Valid @RequestBody VotePostCreateRequest request) {
        return voteService.createVotePost(userId, request);
    }

    @Operation(
            summary = "투표 참여",
            description = "한 사람당 한 표. 이미 투표했으면 처음 고른 선택지를 그대로 돌려준다(바꿀 수 없음).",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "내가 고른 선택지"),
        @ApiResponse(
                responseCode = "400",
                description = "NOT_VOTE_POST: 투표글이 아님 / INVALID_VOTE_OPTION: 이 글의 선택지가 아님",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "UNAUTHENTICATED: 로그인 필요",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "POST_NOT_FOUND",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "409",
                description = "VOTE_CLOSED: 마감된 투표",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{postId}/poll/votes")
    public VoteCastResponse vote(
            @Parameter(hidden = true) @RequestHeader(AuthHeaders.USER_ID) Long userId,
            @PathVariable("postId") Long postId,
            @Valid @RequestBody VoteRequest request) {
        return voteService.vote(userId, postId, request.voteOptionId());
    }

    @Operation(
            summary = "투표 결과 조회",
            description = "토큰 없이도 조회된다. 토큰을 보내면 myVoteOptionId에 내 선택이 담기고, 아니면 null이다.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공"),
        @ApiResponse(
                responseCode = "400",
                description = "NOT_VOTE_POST: 투표글이 아님",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "POST_NOT_FOUND",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{postId}/poll")
    public VoteResultResponse getResults(
            @PathVariable("postId") Long postId,
            @Parameter(hidden = true) @RequestHeader(name = AuthHeaders.USER_ID, required = false) Long userId) {
        return voteService.getResults(postId, userId);
    }

    @Operation(
            summary = "투표 조기 종료",
            description = "작성자만 종료할 수 있다. 바디는 {\"closed\": true}이며, 이미 종료된 투표에 다시 요청해도 "
                    + "closed=true다. 다시 여는 요청(closed=false)은 받지 않는다.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "종료됨"),
        @ApiResponse(
                responseCode = "400",
                description = "NOT_VOTE_POST: 투표글이 아님 / INVALID_REQUEST: closed가 없거나 false",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
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
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{postId}/poll")
    public VoteClosedResponse close(
            @Parameter(hidden = true) @RequestHeader(AuthHeaders.USER_ID) Long userId,
            @PathVariable("postId") Long postId,
            @Valid @RequestBody PollCloseRequest request) {
        return voteService.close(userId, postId);
    }
}
