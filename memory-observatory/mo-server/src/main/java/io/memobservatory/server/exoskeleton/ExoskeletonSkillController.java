package io.memobservatory.server.exoskeleton;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.memobservatory.agentloop.workspace.SkillLibrary;
import io.memobservatory.agentloop.workspace.SkillView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 外骨骼技能提议的「采纳」编排器——<b>唯一需要本服务插手的一步</b>。
 *
 * <p><b>为什么采纳不能由外骨骼自己完成</b>：技能是 mo-server 工作区里的 YAML 文件
 * （{@code .workbench/skill/<id>.yaml}），落盘归 {@link SkillLibrary} 管；而 mo-exoskeleton 是
 * <b>另一个进程、不挂载这些目录</b>。所以外骨骼只能记录「人决定了什么」，真正把技能装进工作台
 * 必须由本服务来做——这就是本类存在的唯一理由。
 *
 * <p><b>其余端点为什么不在这里</b>：看板（{@code GET /skills}）、手动提炼（{@code POST /discover}）、
 * 「不用」（{@code POST /skills/{id}/dismiss}）都只是外骨骼自己库里的读写，一律走通用转发层
 * {@link ExoskeletonProxyController}（{@code /api/v1/exoskeleton/**}）原样透传即可，无需本类复写。
 * 只有 {@code adopt} 因为要「先落盘、再回写状态」而必须由本服务接管：它比转发层的
 * {@code **} 映射更具体，Spring 会优先匹配到这里。
 *
 * <p><b>顺序不可颠倒</b>：先落盘、后标记。若先标记 ADOPTED 再落盘、而落盘失败，提议会显示成
 * 「已采纳」却没有对应技能——状态与事实背离。反过来（落盘成功、标记失败）至少技能是真实存在的，
 * 且重试幂等（同一作用域同 id 覆盖写），故本类选这个顺序，并在结果里如实带出 {@code marked}。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/exoskeleton/skills")
public class ExoskeletonSkillController {

    /** 外骨骼基址，复用与转发层同一个配置项。 */
    private final String baseUrl;
    private final SkillLibrary library;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public ExoskeletonSkillController(
            @Value("${mo.exoskeleton.base-url:http://localhost:8081}") String baseUrl,
            SkillLibrary library,
            ObjectMapper mapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.library = library;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    /**
     * 采纳一条技能提议：取提议 payload → 落成<b>全局</b>技能 → 通知外骨骼标记 ADOPTED。
     *
     * <p>落全局（{@code ws=null}）：技能是外骨骼从「一类任务」里提炼的手艺，不该被某个工作区独占；
     * 全局作用域所有工作区共享。**技能提议已按「Agent × 任务类型」为单位**（见登记口 A15），
     * 但落盘仍用全局作用域——不同 Agent 的同类技能靠 {@code skillId} 的 Agent 前缀
     * （如 {@code agent-harness--doc-refinement}，提炼端已加）互不覆盖，故此处无需再传 agent。
     *
     * @return 成功为 200 + 结果；提议不存在 404；技能落盘冲突（同名）409；
     *         外骨骼不可达 503（如实，不伪装成功）
     */
    @PostMapping("/{id}/adopt")
    public ResponseEntity<Object> adopt(@PathVariable String id) {
        Payload payload;
        try {
            payload = fetch(id);
        } catch (NotFound e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new Fail("没有这条技能提议：" + id));
        } catch (Exception e) {
            return unavailable(e);
        }

        SkillView saved;
        try {
            saved = library.save(payload.skillId(), payload.name(), payload.description(),
                    payload.prompt(), payload.tools(), null, null);
        } catch (IllegalArgumentException e) {
            // 同名技能已在别的（更高/其他）作用域存在——如实回冲突，不覆盖别人的技能
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new Fail(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new Fail(e.getMessage()));
        }

        boolean marked = true;
        try {
            mark(id);
        } catch (Exception e) {
            marked = false;
            // 技能已真实落盘，只有状态回写没成功；不谎报失败，也不谎报全好——如实带出 marked
            log.warn("[skill] 采纳已落盘但状态回写失败 id={} : {}", id, e.toString());
        }

        return ResponseEntity.ok(new AdoptResult(id, saved.id(), saved.scope(), marked,
                marked ? "已启用为全局技能：" + saved.name()
                        : "技能已写入工作台（" + saved.id() + "），但外骨骼状态回写失败，建议重试一次"));
    }

    // ------------------------------------------------------------------
    // 内部：取 payload / 回写状态 / 错误响应
    // ------------------------------------------------------------------

    /** 从外骨骼取提议的完整 payload。 */
    private Payload fetch(String id) throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/exoskeleton/skills/" + enc(id)))
                        .timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() == 404) {
            throw new NotFound();
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("外骨骼取提议失败：HTTP " + resp.statusCode());
        }
        return mapper.readValue(resp.body(), Payload.class);
    }

    /** 通知外骨骼把该提议标记为已采纳（落盘已完成，这一步只是状态回写）。 */
    private void mark(String id) throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/exoskeleton/skills/" + enc(id) + "/adopt"))
                        .timeout(Duration.ofSeconds(15))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("外骨骼标记已采纳失败：HTTP " + resp.statusCode());
        }
    }

    /** 外骨骼不可达：与转发层同一口径——如实回 503，不伪造成功。 */
    private ResponseEntity<Object> unavailable(Exception e) {
        String cause = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new Fail("记忆外骨骼（mo-exoskeleton）不可达，未能采纳：%s。请确认它已启动（%s）"
                        .formatted(cause, baseUrl)));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    /** 提议 payload 的最小投影：只取落盘需要的五个字段，其余（turns/status/…）忽略。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Payload(String skillId, String name, String description,
                           String prompt, List<String> tools) {
    }

    private record AdoptResult(String proposalId, String skillId, String scope,
                               boolean marked, String message) {
    }

    private record Fail(String error) {
    }

    /** 提议不存在（外骨骼回了 404）。 */
    private static class NotFound extends Exception {
    }
}