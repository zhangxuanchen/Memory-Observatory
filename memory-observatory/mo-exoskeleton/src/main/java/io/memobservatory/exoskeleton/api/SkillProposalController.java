package io.memobservatory.exoskeleton.api;

import io.memobservatory.exoskeleton.model.SkillBoard;
import io.memobservatory.exoskeleton.model.SkillDiscoverAccepted;
import io.memobservatory.exoskeleton.model.SkillDiscoverCancel;
import io.memobservatory.exoskeleton.model.SkillDiscoverReport;
import io.memobservatory.exoskeleton.model.SkillDiscoverStatus;
import io.memobservatory.exoskeleton.model.SkillEffectReport;
import io.memobservatory.exoskeleton.model.SkillEvent;
import io.memobservatory.exoskeleton.model.SkillProposal;
import io.memobservatory.exoskeleton.model.SkillVersion;
import io.memobservatory.exoskeleton.skills.FactDiscoverer;
import io.memobservatory.exoskeleton.skills.SkillDiscoverer;
import io.memobservatory.exoskeleton.skills.SkillEffectService;
import io.memobservatory.exoskeleton.skills.SkillProposalRepository;
import io.memobservatory.exoskeleton.tasks.LlmUsageMeter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能发现入口（人面「外骨骼」页），与 §4.10 决策卡片是两条独立的人面。
 *
 * <p><b>为什么采纳/不用只标记状态、不落盘</b>：技能是 mo-server 工作区里的 YAML 文件，
 * 而本模块是另一个进程、<b>不挂载</b>那些目录。落盘是 mo-server 的领域（{@code SkillLibrary}），
 * 故这里只负责记录「人决定了什么」；真正的落盘由 mo-server 的采纳编排器完成。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code GET  /skills?agent=&session=} —— 看板：Agent 清单 + 所选范围内的分类清单与提议</li>
 *   <li>{@code GET  /skills/{id}}            —— 单条提议（供 mo-server 采纳时取 payload）</li>
 *   <li>{@code POST /skills/discover?agent=} —— 手动为某个 Agent 提炼一轮（分类收尾亦按 Agent 自动调）</li>
 *   <li>{@code POST /skills/discover/async?agent=}  —— 异步发起一轮提炼：立即返回，进度见 status</li>
 *   <li>{@code GET  /skills/discover/status?agent=} —— 查提炼进度（含上一次跑完的报告与每类原因）</li>
 *   <li>{@code POST /skills/discover/cancel?agent=} —— 请求取消正在跑的一轮（类间生效，真停）</li>
 *   <li>{@code POST /skills/{id}/adopt}      —— 标记已采纳（落盘在 mo-server）</li>
 *   <li>{@code POST /skills/{id}/dismiss}    —— 标记不用</li>
 *   <li>{@code POST /skills/dismiss-batch}   —— 批量标记不用（逐条独立、HTTP 恒 200、ids 空 400）</li>
 *   <li>{@code GET  /skills/{id}/versions}   —— 版本历史（新 → 旧）；无提议 404</li>
 *   <li>{@code GET  /skills/events}          —— 事件流水（操作历史 + 历史视图，按时间倒序）</li>
 *   <li>{@code GET  /skills/{id}/effect}     —— 效果验证：采纳前 vs 采纳后对照判决（只出结论，不改能力）</li>
     *   <li>{@code GET  /skills/usage}           —— LLM 用量：按「Agent × 能力 × 任务类型」的调用次数与 token</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/exoskeleton/skills")
public class SkillProposalController {

    private static final Logger log = LoggerFactory.getLogger(SkillProposalController.class);
    /** 事件流水的单次上限：越界夹取，不报 400（它是只读窗口，不是校验点）。 */
    private static final int EVENTS_MAX_LIMIT = 500;

    private final SkillDiscoverer discoverer;
    private final FactDiscoverer factDiscoverer;
    private final SkillProposalRepository repo;
    private final SkillEffectService effectService;
    private final LlmUsageMeter usageMeter;

    public SkillProposalController(SkillDiscoverer discoverer, FactDiscoverer factDiscoverer,
                                   SkillProposalRepository repo,
                                   SkillEffectService effectService, LlmUsageMeter usageMeter) {
        this.discoverer = discoverer;
        this.factDiscoverer = factDiscoverer;
        this.repo = repo;
        this.effectService = effectService;
        this.usageMeter = usageMeter;
    }

    /**
     * 看板：Agent 清单（始终全量）+ 所选 Agent / Session 范围内的分类清单与提议。
     * 未选 Agent 时类清单为空——不拿全库聚合冒充「这个 Agent 的分类」。
     *
     * <p><b>{@code limit} 缺省 0 = 不限，行为与从前一致</b>：大库可按需传正数，
     * 每张清单只取前 N 行（Agent / Session / 分类 / 提议一致），避免一次拉全表拖垮看板。
     */
    @GetMapping
    public SkillBoard board(
            @RequestParam(name = "agent", required = false) String agent,
            @RequestParam(name = "session", required = false) String session,
            @RequestParam(name = "limit", required = false, defaultValue = "0") int limit) {
        return discoverer.board(agent, session, limit);
    }

    /** 单条提议的完整 payload（mo-server 采纳编排时按 id 取）。 */
    @GetMapping("/{id}")
    public ResponseEntity<SkillProposal> get(@PathVariable String id) {
        SkillProposal p = repo.find(id);
        return p == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(p);
    }

    /** 手动为某个 Agent 提炼一轮；幂等，可反复调用。未给 agent 时如实返回「未指定 Agent」。 */
    @PostMapping("/discover")
    public SkillDiscoverReport discover(
            @RequestParam(name = "agent", required = false) String agent) {
        return discoverer.runOnce(agent);
    }

    /**
     * 异步发起一轮提炼：<b>立即返回</b>，不阻塞。真正的提炼在后台跑，进度与结果经
     * {@link #discoverStatus} 查询。已在跑时 {@code accepted=false}，不重复发 LLM 调用。
     */
    @PostMapping("/discover/async")
    public SkillDiscoverAccepted discoverAsync(
            @RequestParam(name = "agent", required = false) String agent) {
        return discoverer.startAsync(agent);
    }

    /** 查询某 Agent 的提炼进度（含上一次跑完的报告）。空闲态 {@code running=false} 仍能读到 {@code lastReport}。 */
    @GetMapping("/discover/status")
    public SkillDiscoverStatus discoverStatus(
            @RequestParam(name = "agent", required = false) String agent) {
        return discoverer.status(agent);
    }

    /** 请求取消某 Agent 正在跑的一轮提炼：类间生效、真停（不再往后发 LLM 调用）。 */
    @PostMapping("/discover/cancel")
    public SkillDiscoverCancel discoverCancel(
            @RequestParam(name = "agent", required = false) String agent) {
        return discoverer.cancel(agent);
    }

    /**
     * 为某个 Agent 提炼一轮「项目结构约定」事实（每 Agent 一份，不分任务类型）。
     *
     * <p><b>与 /discover 是两条独立的人面</b>：技能问「这类任务怎么做」（一格一「Agent × 任务类型」），
     * 事实答「这个项目长什么样」（一格一 Agent）。两者共用提议表与「采纳 → 落盘」链路，
     * 但粒度不同，故各自独立触发、各自记账（见 {@link FactDiscoverer}）。
     */
    @PostMapping("/discover/fact")
    public SkillDiscoverReport discoverFact(
            @RequestParam(name = "agent", required = false) String agent) {
        return factDiscoverer.runOnce(agent);
    }

    /** 标记已采纳（技能是否真的落到工作台由 mo-server 保证）。 */
    @PostMapping("/{id}/adopt")
    public ResponseEntity<SkillProposal> adopt(@PathVariable String id) {
        return mark(id, SkillProposal.ADOPTED);
    }

    /** 标记不用：同一类同一份证据不再重复提。 */
    @PostMapping("/{id}/dismiss")
    public ResponseEntity<SkillProposal> dismiss(@PathVariable String id) {
        return mark(id, SkillProposal.DISMISSED);
    }

    /**
     * 批量「不用」：对一串提议逐条独立地标记 {@code DISMISSED}。
     *
     * <p><b>为什么逐条独立、HTTP 恒 200</b>：勾了 5 条，其中 1 条已被别处删掉，合理结果是
     * 「4 条标记成功、1 条如实报没找到」，而不是整批失败（与 mo-server 的 {@code adopt-batch} 同口径）。
     * {@code ids} 为空是唯一的 400——空勾选是调用方用错了，与「某条不存在」不同。
     */
    @PostMapping("/dismiss-batch")
    public ResponseEntity<Object> dismissBatch(@RequestBody BatchRequest req) {
        List<String> ids = (req == null || req.ids() == null) ? List.of() : req.ids();
        if (ids.isEmpty()) {
            return ResponseEntity.badRequest().body(new Fail("没有勾选任何提议：ids 为空。"));
        }
        List<BatchItem> results = new ArrayList<>(ids.size());
        int ok = 0;
        for (String id : ids) {
            if (repo.find(id) == null) {
                results.add(new BatchItem(id, false, "没有这条技能提议"));
                continue;
            }
            mark(id, SkillProposal.DISMISSED);
            ok++;
            results.add(new BatchItem(id, true, "已标记不用。"));
        }
        return ResponseEntity.ok(new BatchResult(results.size(), ok, results.size() - ok,
                "批量不用完成：成功 " + ok + " 条、失败 " + (results.size() - ok) + " 条（失败原因见各条）。",
                results));
    }

    /**
     * 版本历史（新 → 旧）：这条提议一路演进过来，每一版的内容快照。
     * 无此提议时 404——版本历史属于某条提议，提议都不存在就无从谈起。
     */
    @GetMapping("/{id}/versions")
    public ResponseEntity<List<SkillVersion>> versions(@PathVariable String id) {
        if (repo.find(id) == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(repo.listVersions(id));
    }

    /**
     * 事件流水（新 → 旧）：既含人的操作（采纳 / 不用），也含系统的提炼轮次（出提议 / 无技能 / 失败 / 跳过 / 出新版 / 去重）。
     *
     * <p><b>为什么 {@code limit} 越界只夹取、不报 400</b>：这是只读的观察窗口，不是校验点——
     * 夹到上限比报错更符合「看一眼历史」，也免去前端为一处参数再加错误分支。
     */
    @GetMapping("/events")
    public List<SkillEvent> events(
            @RequestParam(name = "agent", required = false) String agent,
            @RequestParam(name = "limit", required = false, defaultValue = "100") int limit,
            @RequestParam(name = "kind", required = false) String kind) {
        int n = Math.max(1, Math.min(limit, EVENTS_MAX_LIMIT));
        return repo.recentEvents(agent, n, kind);
    }

    /**
     * 效果验证：对已采纳的提议做「采纳前 vs 采纳后」对照判决（准 / 失败 / 回合三项）。
     *
     * <p><b>只出结论，不做动作</b>：撤回采纳是人拍板的事，本端点不自动改任何能力与状态。
     * 未采纳（无采纳时刻）或样本不足时，判决为 {@code UNKNOWN}（不可测），如实返回而非假装通过。
     */
    @GetMapping("/{id}/effect")
    public ResponseEntity<SkillEffectReport> effect(@PathVariable String id) {
        SkillEffectReport r = effectService.report(id);
        return r == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(r);
    }

    /**
     * LLM 用量：按「Agent × 能力 × 任务类型」的调用次数与 token 读数（进程内累加，重启归零）。
     *
     * <p><b>只读不改</b>：这是观察成本结构的窗口，不是计费系统——不落库、不做额度控制。
     * 用它回答「谁在烧钱、烧在哪一类上」，据此决定要不要调小 {@code digest-max-chars} 或收敛提炼范围。
     */
    @GetMapping("/usage")
    public List<LlmUsageMeter.Row> usage() {
        return usageMeter.rows();
    }

    private ResponseEntity<SkillProposal> mark(String id, String status) {
        SkillProposal before = repo.find(id);
        if (before == null) {
            return ResponseEntity.notFound().build();
        }
        SkillProposal updated = repo.mark(id, status);
        recordHumanEvent(updated, status);
        return ResponseEntity.ok(updated);
    }

    /**
     * 把「人拍板」这一动作记进事件流水（{@code actor=HUMAN}）。
     *
     * <p><b>为什么这里要吞异常</b>：事件流水是历史的旁路——记不上不该让「采纳 / 不用」失败；
     * 状态已经改了，如实返回即可，只在日志里留一句。
     */
    private void recordHumanEvent(SkillProposal p, String status) {
        if (p == null) {
            return;
        }
        boolean adopt = SkillProposal.ADOPTED.equals(status);
        try {
            repo.recordEvent(adopt ? SkillEvent.ADOPT : SkillEvent.DISMISS, SkillEvent.HUMAN,
                    p.proposalId(), p.agentId(), p.taskType(), p.version(), p.digestHash(),
                    adopt ? "人已采纳：技能落进工作台技能库（第 " + p.version() + " 版）。"
                          : "人已标记不用：同一份证据不再重复提。");
        } catch (Exception e) {
            log.warn("[skill] 记事件失败 id={} status={} : {}", p.proposalId(), status, e.toString());
        }
    }

    /** 批量请求：要处理的提议 id 列表（包级可见：供单测直接构造，不经 HTTP）。 */
    record BatchRequest(List<String> ids) {
    }

    /** 批量逐条结果：{@code ok=false} 表示该 id 不存在或处理失败，原因在 {@code message}。 */
    private record BatchItem(String proposalId, boolean ok, String message) {
    }

    private record BatchResult(int total, int succeeded, int failed, String message, List<BatchItem> results) {
    }

    private record Fail(String error) {
    }
}