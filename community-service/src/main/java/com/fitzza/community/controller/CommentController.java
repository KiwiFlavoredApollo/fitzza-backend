package com.fitzza.community.controller;

import com.fitzza.community.config.OpenApiConfig;
import com.fitzza.community.dto.CommentCreateRequest;
import com.fitzza.community.dto.CommentCreatedResponse;
import com.fitzza.community.dto.CommentResponse;
import com.fitzza.community.dto.CommentUpdateRequest;
import com.fitzza.community.dto.ErrorResponse;
import com.fitzza.community.dto.LikeCountResponse;
import com.fitzza.community.security.AuthHeaders;
import com.fitzza.community.service.CommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "댓글", description = "댓글·답글 조회·작성·수정·삭제·좋아요")
@RestController
@RequestMapping("/api/community")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @Operation(
            summary = "댓글 목록",
            description = "답글은 replies에 담긴다(1단계까지). 답글이 남아 있는 삭제된 댓글은 isDeleted=true, author=null로 "
                    + "내려가고 내용은 안내 문구로 바뀐다. 토큰을 보내면 liked에 내 좋아요 여부가 담긴다.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공"),
        @ApiResponse(
                responseCode = "404",
                description = "POST_NOT_FOUND: 없거나 삭제된 글",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/posts/{postId}/comments")
    public List<CommentResponse> getComments(
            @PathVariable("postId") Long postId,
            @Parameter(hidden = true) @RequestHeader(name = AuthHeaders.USER_ID, required = false) Long userId) {
        return commentService.getComments(postId, userId);
    }

    @Operation(
            summary = "댓글·답글 작성",
            description = "500자 이하. 답글이면 parentCommentId에 원 댓글 ID를 넣는다. 답글에는 답글을 달 수 없다.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "작성 성공"),
        @ApiResponse(
                responseCode = "400",
                description = "INVALID_REQUEST: 빈 댓글·500자 초과 / INVALID_PARENT_COMMENT: 답글을 달 수 없는 댓글",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "UNAUTHENTICATED: 로그인 필요",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "POST_NOT_FOUND",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/posts/{postId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentCreatedResponse create(
            @Parameter(hidden = true) @RequestHeader(AuthHeaders.USER_ID) Long userId,
            @PathVariable("postId") Long postId,
            @Valid @RequestBody CommentCreateRequest request) {
        return commentService.create(userId, postId, request);
    }

    @Operation(
            summary = "댓글 수정",
            description = "작성자만 수정할 수 있다.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "수정 성공(응답 본문 없음)", content = @Content),
        @ApiResponse(
                responseCode = "400",
                description = "INVALID_REQUEST: 빈 댓글·500자 초과",
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
                description = "COMMENT_NOT_FOUND",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "409",
                description = "CONCURRENT_UPDATE: 다른 수정·삭제 요청과 겹침. 새로고침 후 다시 시도",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/comments/{commentId}")
    public void update(
            @Parameter(hidden = true) @RequestHeader(AuthHeaders.USER_ID) Long userId,
            @PathVariable("commentId") Long commentId,
            @Valid @RequestBody CommentUpdateRequest request) {
        commentService.update(userId, commentId, request);
    }

    @Operation(
            summary = "댓글 삭제",
            description = "작성자만 삭제할 수 있다. 답글이 남아 있으면 목록에 삭제된 댓글로 계속 보인다.",
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
                description = "COMMENT_NOT_FOUND",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "409",
                description = "CONCURRENT_UPDATE: 다른 수정·삭제 요청과 겹침. 새로고침 후 다시 시도",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @Parameter(hidden = true) @RequestHeader(AuthHeaders.USER_ID) Long userId,
            @PathVariable("commentId") Long commentId) {
        commentService.delete(userId, commentId);
    }

    @Operation(
            summary = "댓글 좋아요",
            description = "한 사람당 한 번. 이미 좋아요한 댓글에 다시 요청해도 같은 결과다.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "현재 좋아요 수"),
        @ApiResponse(
                responseCode = "401",
                description = "UNAUTHENTICATED: 로그인 필요",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "COMMENT_NOT_FOUND",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/comments/{commentId}/likes")
    public LikeCountResponse like(
            @Parameter(hidden = true) @RequestHeader(AuthHeaders.USER_ID) Long userId,
            @PathVariable("commentId") Long commentId) {
        return commentService.like(userId, commentId);
    }

    @Operation(
            summary = "댓글 좋아요 취소",
            description = "좋아요하지 않은 댓글에 요청해도 같은 결과다.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "현재 좋아요 수"),
        @ApiResponse(
                responseCode = "401",
                description = "UNAUTHENTICATED: 로그인 필요",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "COMMENT_NOT_FOUND",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/comments/{commentId}/likes")
    public LikeCountResponse unlike(
            @Parameter(hidden = true) @RequestHeader(AuthHeaders.USER_ID) Long userId,
            @PathVariable("commentId") Long commentId) {
        return commentService.unlike(userId, commentId);
    }
}
