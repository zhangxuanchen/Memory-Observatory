package io.memobservatory.exoskeleton.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.memobservatory.exoskeleton.model.SkillDiscoverReport;
import io.memobservatory.exoskeleton.model.SkillEvent;
import io.memobservatory.exoskeleton.model.SkillProposal;
import io.memobservatory.exoskeleton.tasks.LlmChatClient;
import io.memobservatory.exoskeleton.tasks.LlmUsageMeter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Locale;

/**
 * 事实提炼器：从某个 Agent 的全部对话里，提炼关于<b>这个项目 / 代码结构的稳定约定</b>。
 *
 * <p><b>为什么与 {@link SkillDiscoverer} 分开而不是同一轮</b>：技能问「这类任务怎么做」（手艺），
 * 事实答「这个项目长什么样」（稳定知识），两者的<b>粒度都不同</b>——技能是「Agent × 任务类型」，
 * 事实是「每 Agent 一份、不分任务类型」（项目结构约定不属于任何一类任务）。硬塞进同一轮，
 * 会让「8 类清单」的读数与事实混在一起、谁也说不清。故各自独立一轮、各自记账，共用提议表与指纹机制。
 *
 * <p><b>落点</b>：与技能一样，只落 {@link SkillProposal}（PENDING），绝不自行启用。
 * 人采纳后才由 mo-server 写进全局技能库，当一个「常驻注入片段」用。
 *
 * <p><b>为什么复用提议表</b>：事实片段的产物形态与技能一致（name / description / prompt / tools），
 * 且要走同一条「采纳 → 落盘」链路。故用哨兵 {@code task_type=}{@value #FACT_TYPE} 存进同一张表，
 * 一格一 Agent（{@code proposal_id = agent:<id>:task:__fact__}）——不新造一套取数 / 状态流转 / 采纳。
 *
 * <p><b>三条纪律</b>（与 {@code TaskClassifier} / {@code SkillDiscoverer} 同构）：不算就不写、
 * 失败不猜留待下轮、绝不自行启用。
 */
@Service
public class FactDiscoverer {

    private static final Logger log = LoggerFactory.getLogger(FactDiscoverer.class);

    /** 事实在提议表 / 记账表里的哨兵 task_type：一格一 Agent，不属于任何任务类型。 */
    static final String FACT_TYPE = "__fact__";
    /** 展示用中文类名（落在提议表的 task_label，人面卡片直接显示）。 */
    static final String FACT_LABEL = "项目结构约定";

    private static final String O_QUIET = "QUIET";
    private static final String O_FAILED = "FAILED";
    private static final String O_OFFERED = "OFFERED";

    private static final String SYSTEM = """
            你是「记忆外骨骼」的事实提炼器。给你某个 Agent 的用户提问记录（按时间顺序，形如 [3/12]）。
            请提炼出**关于这个项目 / 代码结构的稳定约定**，只提炼这一类，其余一律不提。

            只提炼这些（都属于「项目结构约定」）：
            · 模块划分、目录约定、包的职责边界；
            · 命名规范、文件组织约定；
            · 关键类 / 入口 / 关键路径的职责；
            · 模块之间、服务之间的依赖关系。

            【铁律一：事实是「这个项目是什么样」，不是「怎么做一件事」】
            · 不要提炼操作步骤、流程、技巧——那是技能，不是事实。
            · 不要提人的口味、偏好、禁忌——那不在本次范围内。
            · 不要提一次性的具体任务内容。

            【铁律二：只从证据来，不编】
            · 只能提在提问记录里**反复出现、可直接读出**的约定；只出现一次、或需要你推断的，不提。
            · 拿不准就输出 null，并在 reason 里说明为何不提。

            【铁律三：可读、可核】
            · body 是给人看的正文，分条列出，每条一句话说清是什么约定；宁可少而准。

            严格只输出一个 JSON 对象，不要任何说明文字、注释或 markdown 代码块标记：
            无可提炼事实时： {"fact": null, "reason": "一句话说明为何不提"}
            有事实时： {"fact": {"id": "<小写短横线命名，如 project-conventions>", "name": "<展示名>",
              "description": "<一句话说明这份事实涵盖什么>",
              "body": "<分条列出的事实正文>"}}
            """;

    private final LlmChatClient llm;
    private final SkillProposalRepository repo;
    private final LlmUsageMeter meter;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${mo.exoskeleton.skills.min-turns:30}")
    private int minTurns;
    @Value("${mo.exoskeleton.skills.digest-max-chars:20000}")
    private int digestMaxChars;

    public FactDiscoverer(LlmChatClient llm, SkillProposalRepository repo, LlmUsageMeter meter) {
        this.llm = llm;
        this.repo = repo;
        this.meter = meter;
    }

    /**
     * 为某个 Agent 提炼一轮事实。同步执行（一轮只一次 LLM 调用），幂等可反复调用。
     *
     * <p>复用 {@link SkillDiscoverReport} 作为回执：事实一轮只有「一份」，故 {@code classes} 至多为 1。
     */
    public SkillDiscoverReport runOnce(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return new SkillDiscoverReport(true, null, 0, 0, 0, 0, 0, null,
                    "未指定 Agent：事实按 Agent 提炼，请先选一个 Agent。");
        }
        if (!llm.isEnabled()) {
            return new SkillDiscoverReport(false, agentId, 0, 0, 0, 0, 0, null,
                    "未启用：LLM 通道未配置（mo.exoskeleton.tasks.llm.base-url / model 为空）。");
        }
        String pid = proposalId(agentId);
        String digest = "";
        String hash = "";
        long turns = 0;
        try {
            List<String> texts = repo.agentTurnTexts(agentId);
            turns = texts.size();
            if (turns < minTurns) {
                return quiet(agentId, 0, "样本不足：仅 " + turns + " 条对话，未达门槛 " + minTurns + "，暂不提炼事实。");
            }
            digest = EvidenceDigest.render(texts, digestMaxChars);
            if (digest.isBlank()) {
                return quiet(agentId, 0, "没有可读的对话原文，无从提炼。");
            }
            hash = EvidenceDigest.sha256(agentId + "\n" + FACT_TYPE + "\n" + digest);

            // 跳过闸门一：已有提议且指纹相同——同一份证据不重复打扰、不白花一次调用
            SkillProposal existing = repo.find(pid);
            if (existing != null && hash.equals(existing.digestHash())) {
                log.info("[fact] 证据未变，本轮不提炼 agent={} status={}", agentId, existing.status());
                note(SkillEvent.SKIP, agentId, pid, existing.version(), hash,
                        "证据指纹未变（既有提议 " + existing.status() + "），本轮跳过，不重复提炼。");
                return skipped(agentId, "证据未变，本轮跳过（同一份证据不重复提炼）。");
            }
            // 跳过闸门二：上轮判过「无稳定事实」（QUIET）且指纹相同
            SkillProposalRepository.ClassRun prior = repo.findClassRun(agentId, FACT_TYPE);
            if (prior != null && O_QUIET.equals(prior.outcome()) && hash.equals(prior.digestHash())) {
                log.info("[fact] 上轮判过无事实且证据未变，本轮跳过 agent={}", agentId);
                note(SkillEvent.SKIP, agentId, pid, null, hash,
                        "上轮已判无稳定事实、证据未变，本轮跳过。");
                return skipped(agentId, "上轮已判无稳定事实，证据未变，本轮不重复提炼。");
            }

            Drawn drawn = draw(agentId, digest);
            if (drawn == null) {
                String why = "解析失败：模型输出不是合法 JSON 或缺必需字段。";
                log.warn("[fact] 解析失败 agent={}（原输出见上一条 warn）", agentId);
                remember(agentId, hash, O_FAILED, turns, why);
                return new SkillDiscoverReport(true, agentId, 1, 0, 0, 1, 0, null, why);
            }
            if (drawn.fact() == null) {
                String why = "LLM 判定无稳定事实可提炼：" + abbrev(drawn.reason());
                log.info("[fact] 判定无事实 agent={} 理由={}", agentId, abbrev(drawn.reason()));
                remember(agentId, hash, O_QUIET, turns, why);
                return quiet(agentId, 1, why);
            }
            SkillProposal stored = repo.store(new SkillProposal(
                    pid, agentId, FACT_TYPE, FACT_LABEL, turns,
                    prefixed(drawn.fact().id(), agentId), drawn.fact().name(),
                    drawn.fact().description(), drawn.fact().body(), List.of(),
                    SkillProposal.PENDING, hash, null, null, 1, null, null));
            remember(agentId, hash, O_OFFERED, turns,
                    stored.isPending() ? null : "已出提议（既有提议 " + stored.status() + "，保持不变）。",
                    stored.version());
            log.info("[fact] 提炼完成 agent={} 新提议={} turns={}", agentId, stored.isPending(), turns);
            return new SkillDiscoverReport(true, agentId, 1, 1, 0, 0, 0, null,
                    "已提炼「" + FACT_LABEL + "」事实片段（每 Agent 一份）；只落「待采纳」提议，"
                            + "采纳后由 mo-server 写进全局技能库，当常驻注入片段用。");
        } catch (Exception e) {
            boolean timeout = e instanceof HttpTimeoutException;
            String why = timeout
                    ? "LLM 超时：对话摘要过大或模型过慢，本轮未提炼。"
                    : "事实提炼失败（" + e.getClass().getSimpleName() + "）：本轮未提炼，下轮重试。";
            log.warn("[fact] 提炼失败 agent={} 原因={} turns={} digest={}字 : {}",
                    agentId, why, turns, digest.length(), e.toString());
            if (!hash.isEmpty()) {
                remember(agentId, hash, O_FAILED, turns, why);
            }
            return new SkillDiscoverReport(true, agentId, hash.isEmpty() ? 0 : 1, 0, 0, 1, 0, null, why);
        }
    }

    // ------------------------------------------------------------------
    // LLM 调用与解析
    // ------------------------------------------------------------------

    /** 一次事实提炼的结果：{@code fact=null} 表示「判定无可提炼的稳定事实」。 */
    private record Drawn(Fact fact, String reason) {
    }

    private record Fact(String id, String name, String description, String body) {
    }

    private Drawn draw(String agentId, String digest) throws Exception {
        // 记账维度：能力名 = fact，任务类型 = 哨兵（事实不分任务类型，如实记 __fact__，见 LlmUsageMeter）
        String text = llm.chat(SYSTEM, "该 Agent 的用户提问（按时间顺序）：\n" + digest,
                (ok, usage) -> meter.record(agentId, "fact", FACT_TYPE, ok, usage));
        return parse(text);
    }

    /** 从模型输出里抠 JSON（取首个 { 到末个 }），解析失败返回 null。 */
    private Drawn parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int s = text.indexOf('{');
        int e = text.lastIndexOf('}');
        if (s < 0 || e <= s) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(text.substring(s, e + 1));
            JsonNode fact = node.path("fact");
            if (fact.isMissingNode() || fact.isNull()) {
                return new Drawn(null, node.path("reason").asText(""));
            }
            String name = textOrJoin(fact.path("name"));
            String body = textOrJoin(fact.path("body"));
            if (name.isEmpty() || body.isEmpty()) {
                // 缺必需字段 = 不合格，按「没提炼出来」处理，不硬塞一条空事实。
                // 必须把模型原输出记下来：否则「为什么没出事实」无从回答（同 B14.1 的教训）。
                log.warn("[fact] 提炼不合格：缺 name 或 body；模型输出={}", preview(text));
                return new Drawn(null, "模型输出的事实缺 name 或 body");
            }
            return new Drawn(new Fact(
                    skillId(textOrJoin(fact.path("id"))),
                    name,
                    textOrJoin(fact.path("description")),
                    body), "");
        } catch (Exception ex) {
            log.warn("[fact] 解析异常：{}；模型输出={}", ex.getClass().getSimpleName(), preview(text));
            return null;
        }
    }

    /** 事实 id：LLM 给的做一次收敛；空则用默认名。 */
    private static String skillId(String raw) {
        String v = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return v.isEmpty() ? "project-conventions" : v;
    }

    /**
     * 取一个字段的文本：字符串直接取，<b>字符串数组则按行拼起来</b>。
     *
     * <p><b>为什么要容忍数组</b>：prompt 里说 body 是「分条列出」的正文，模型时常把它写成
     * JSON 数组（实测 qwen3 就把 body 回成了 5 条字符串的数组）。用 {@code asText()} 取数组只会得到
     * 空串，于是明明提炼出了事实却被判成「缺 body」——这是解析器的口味，不是模型没答。两条形态都收。
     */
    private static String textOrJoin(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isArray()) {
            List<String> parts = new java.util.ArrayList<>();
            for (JsonNode item : node) {
                String s = item.asText("").strip();
                if (!s.isEmpty()) {
                    parts.add(s);
                }
            }
            return String.join("\n", parts);
        }
        return node.asText("").strip();
    }

    // ------------------------------------------------------------------
    // 记账与提议主键
    // ------------------------------------------------------------------

    /**
     * 记一类的本轮结果（断点续跑缓存 + 事件流水）。记账失败<b>不得影响</b>本轮结果——单独 try/catch，当旁路。
     */
    private void remember(String agentId, String hash, String outcome, long turns, String reason) {
        remember(agentId, hash, outcome, turns, reason, null);
    }

    private void remember(String agentId, String hash, String outcome, long turns,
                          String reason, Integer version) {
        try {
            repo.recordClassRun(agentId, FACT_TYPE, hash, outcome, turns, reason);
        } catch (Exception e) {
            log.warn("[fact] 记账失败 agent={} outcome={} : {}", agentId, outcome, e.toString());
        }
        String kind = switch (outcome) {
            case O_OFFERED -> SkillEvent.OFFER;
            case O_QUIET -> SkillEvent.QUIET;
            default -> SkillEvent.FAILED;
        };
        note(kind, agentId, proposalId(agentId), version, hash,
                reason != null ? reason : "本轮提炼出事实片段。");
    }

    /** 记一条事件流水（只增不改）。失败只记日志——历史是旁路，不该掀翻提炼本身。 */
    private void note(String kind, String agentId, String proposalId, Integer version,
                      String hash, String detail) {
        try {
            repo.recordEvent(kind, SkillEvent.SYSTEM, proposalId, agentId, FACT_TYPE, version, hash, detail);
        } catch (Exception e) {
            log.warn("[fact] 记事件失败 agent={} kind={} : {}", agentId, kind, e.toString());
        }
    }

    /** 提议主键：一格一 Agent（事实不分任务类型，故用哨兵）。 */
    private static String proposalId(String agentId) {
        return "agent:" + agentId + ":task:" + FACT_TYPE;
    }

    /** 事实 id 带 Agent 前缀：每个 Agent 一份、互不覆盖（片段仍落全局库，与技能同一口径）。 */
    private static String prefixed(String id, String agentId) {
        return agentSlug(agentId) + "--" + id;
    }

    private static String agentSlug(String agentId) {
        String v = agentId == null ? "" : agentId.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return v.isEmpty() ? "agent" : v;
    }

    private static SkillDiscoverReport quiet(String agentId, int classes, String note) {
        return new SkillDiscoverReport(true, agentId, classes, 0, classes, 0, 0, null, note);
    }

    private static SkillDiscoverReport skipped(String agentId, String note) {
        return new SkillDiscoverReport(true, agentId, 0, 0, 0, 0, 1, null, note);
    }

    private static String abbrev(String s) {
        if (s == null || s.isBlank()) {
            return "(模型未给理由)";
        }
        String v = s.replaceAll("\\s+", " ").strip();
        return v.length() <= 200 ? v : v.substring(0, 200) + "…";
    }

    /** 记日志用的模型原输出预览：够看清它到底回了什么形状（比 reason 留得长）。 */
    private static String preview(String s) {
        if (s == null || s.isBlank()) {
            return "(空输出)";
        }
        String v = s.replaceAll("\\s+", " ").strip();
        return v.length() <= 800 ? v : v.substring(0, 800) + "…";
    }
}