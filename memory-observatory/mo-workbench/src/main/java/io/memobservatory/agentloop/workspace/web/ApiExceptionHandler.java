/*******************************************************************************
 * 【模块】Workspace-Agent · workspace · web
 * 【文件】ApiExceptionHandler.java（io.memobservatory.agentloop.workspace.web）
 * 【核心功能】把工作区/Agent 校验类的 IllegalArgumentException 转成 HTTP 400，
 *            body 返回 { error: 文案 }，便于前端直接展示；其余异常转 500。
 * 【核心改动】2026-08-23 新增。chat 端点自行捕获并 SSE 返回，不受此影响。
 *******************************************************************************/
package io.memobservatory.agentloop.workspace.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常处理器：非法参数（用户路径不存在/不是目录、Agent 缺失等）返回 400，
 * 其余未捕获异常返回 500，统一 JSON 结构 { error: ... }。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getMessage() == null ? "非法请求" : e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> serverError(Exception e) {
        log.error("未捕获异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "服务器错误: " + e));
    }
}