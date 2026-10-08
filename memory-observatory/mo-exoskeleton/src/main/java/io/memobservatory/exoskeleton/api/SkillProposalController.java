package io.memobservatory.exoskeleton.api;

import io.memobservatory.exoskeleton.model.SkillBoard;
import io.memobservatory.exoskeleton.model.SkillDiscoverReport;
import io.memobservatory.exoskeleton.model.SkillProposal;
import io.memobservatory.exoskeleton.skills.SkillDiscoverer;
import io.memobservatory.exoskeleton.skills.SkillProposalRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
 *   <li>{@code POST /skills/{id}/adopt}      —— 标记已采纳（落盘在 mo-server）</li>
 *   <li>{@code POST /skills/{id}/dismiss}    —— 标记不用</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/exoskeleton/skills")
public class SkillProposalController {

    private final SkillDiscoverer discoverer;
    private final SkillProposalRepository repo;

    public SkillProposalController(SkillDiscoverer discoverer, SkillProposalRepository repo) {
        this.discoverer = discoverer;
        this.repo = repo;
    }

    /**
     * 看板：Agent 清单（始终全量）+ 所选 Agent / Session 范围内的分类清单与提议。
     * 未选 Agent 时类清单为空——不拿全库聚合冒充「这个 Agent 的分类」。
     */
    @GetMapping
    public SkillBoard board(
            @RequestParam(name = "agent", required = false) String agent,
            @RequestParam(name = "session", required = false) String session) {
        return discoverer.board(agent, session);
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

    private ResponseEntity<SkillProposal> mark(String id, String status) {
        if (repo.find(id) == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(repo.mark(id, status));
    }
}