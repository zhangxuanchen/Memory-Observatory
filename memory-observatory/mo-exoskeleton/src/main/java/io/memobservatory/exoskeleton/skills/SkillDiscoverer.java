package io.memobservatory.exoskeleton.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.memobservatory.exoskeleton.model.SkillBoard;
import io.memobservatory.exoskeleton.model.SkillDiscoverReport;
import io.memobservatory.exoskeleton.model.SkillProposal;
import io.memobservatory.exoskeleton.tasks.LlmChatClient;
import io.memobservatory.exoskeleton.tasks.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 技能提炼器：按「任务类型」把对话分门别类，再从每一类里提炼出<strong>可复用技能</strong>。
 *
 * <p><b>为什么按 task_type 而不是 §4.4 的 shape 分桶</b>：技能是「一类任务怎么做」的手艺，
 * 与「这段对话在做什么任务」直接对应；shape 是行为形状（读/写/步数），答不出「这是件什么事」。
 * 故本能力接在已有的 task_type 分类器上，用的就是那 8 个人能读的类名。
 *
 * <p><b>三条纪律</b>（与 {@code TaskClassifier} 同构）：
 * <ol>
 *   <li>不算就不写：LLM 不可达 / 解析失败 → 该类本轮不产生提议，留待下轮，<b>不猜</b>。</li>
 *   <li>单类失败不影响整批：一类抛异常只记日志计入 failed，继续下一类。</li>
 *   <li>绝不自行启用：只落 {@link SkillProposal}（PENDING），装不装进工作台由人在页面上拍板。</li>
 * </ol>
 *
 * <p><b>严格口径的来源</b>：system prompt 沿用工作台「发现技能（WikiSkill 风格，严格模式）」那条
 * 铁律（前端 {@code agentCommonCommands.discover}）。那份口径只活在前端 JS 里，本类是它在服务端的
 * <b>权威副本</b>——两处措辞须保持一致（见登记口 B14）。
 */
@Service
public class SkillDiscoverer {

    private static final Logger log = LoggerFactory.getLogger(SkillDiscoverer.class);

    /** 单条 turn 至少要留这么多字符，否则宁可少取几条 turn（与 TaskClassifier 同一常数）。 */
    private static final int MIN_CHARS_PER_TURN = 200;

    private static final String TOOLS_VOCAB = "readFile, writeFile, editFile, bash, webSearch";

    private static final String SYSTEM = """
            你是「记忆外骨骼」的技能提炼器。给你某一类任务的用户提问记录（按时间顺序，形如 [3/12]）。
            请判断这类任务里是否已沉淀出一个**完整闭环的可复用技能**；若有，整理成技能；若没有，如实说没有。

            【铁律一：技能必须是完整闭环，不是零散碎片】
            · 一个技能必须是一套「从开始到验收」完整自洽的流程或策略：涵盖明确的触发场景、完整步骤、
              每步的输入/输出、异常处理、禁止事项、以及完成判定（验收/自检）。
            · 只有能完整指导 Agent 独立跑通「一类任务」的才够格；单点小技巧（改一个参数、换一个端口、
              加一句注释这类）不足、不许单独成技能。
            · 宁可少而精，不要多而碎。拿不准就输出 null，并在 reason 里说明为何延后。

            【铁律二：只从证据来，不编】
            · 只能依据给你的提问记录里反复出现的做法；不得凭空发明步骤或工具。

            严格只输出一个 JSON 对象，不要任何说明文字、注释或 markdown 代码块标记：
            无技能时： {"skill": null, "reason": "一句话说明为何不够格"}
            有技能时： {"skill": {"id": "<小写短横线命名>", "name": "<展示名>",
              "description": "<一句话能力描述>",
              "prompt": "<完整流程步骤 + 注意/禁止 + 验收自检>",
              "tools": ["readFile","editFile","bash"]}}
            tools 只能从这几个里选：%s。
            """.formatted(TOOLS_VOCAB);

    private final LlmChatClient llm;
    private final SkillProposalRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${mo.exoskeleton.skills.enabled:true}")
    private boolean enabled;
    @Value("${mo.exoskeleton.skills.min-turns:30}")
    private int minTurns;
    @Value("${mo.exoskeleton.skills.max-classes-per-run:8}")
    private int maxClasses;
    @Value("${mo.exoskeleton.skills.digest-max-chars:20000}")
    private int digestMaxChars;

    public SkillDiscoverer(LlmChatClient llm, SkillProposalRepository repo) {
        this.llm = llm;
        this.repo = repo;
    }

    /**
     * 跑一轮提炼：<b>只针对一个 Agent</b>。幂等——同一 Agent 同一类同一份证据（指纹相同）不重复落。
     *
     * <p><b>为什么以「Agent × task_type」为单位</b>：技能是「一类任务怎么做」的手艺，
     * 而不同 Agent 干的活、留下的做法各异。把几十个 Agent 的对话混在一起按 task_type 提炼，
     * 得到的是拼接过的伪手艺；故先按 Agent 切开，再在这一刀之内分门别类（登记口 A15）。
     *
     * <p>逐类判定；样本不足（turn 数 &lt; {@code min-turns}）的类跳过——
     * 这是「每类多少对话」这张清单里「是否发现技能」的天然门槛，避免拿几条对话硬凑技能。
     *
     * @param agentId 要提炼的 Agent（{@code memory_turns.agent_id}），必填
     */
    public SkillDiscoverReport runOnce(String agentId) {
        if (!enabled) {
            return disabled(agentId, "未启用：mo.exoskeleton.skills.enabled=false。");
        }
        if (!llm.isEnabled()) {
            return disabled(agentId, "未启用：LLM 通道未配置（mo.exoskeleton.tasks.llm.base-url / model 为空）。");
        }
        if (agentId == null || agentId.isBlank()) {
            return disabled(null, "未指定 Agent：提炼以「Agent × 任务类型」为单位，须先选定一个 Agent。");
        }

        var counts = repo.classDistribution(agentId, null);
        int classes = 0;
        int offered = 0;
        int quiet = 0;
        int failed = 0;
        int skipped = 0;

        for (TaskType type : TaskType.values()) {
            if (type == TaskType.OTHER) {
                continue;   // 「其他」是收容筐，不构成一类可提炼的手艺
            }
            long turns = countOf(counts, type.value());
            if (turns < minTurns) {
                continue;   // 样本不足：不提炼，也不报失败（它在清单里显示为未达标）
            }
            if (classes >= Math.max(1, maxClasses)) {
                break;      // 本轮预算用尽，其余下轮再来
            }
            String digest = "";
            try {
                List<String> texts = repo.classTurnTexts(agentId, type.value());
                digest = renderDigest(texts);
                if (digest.isBlank()) {
                    continue;   // 没有可读的对话原文：无从提炼，也不算一类
                }
                String hash = sha256(agentId + "\n" + type.value() + "\n" + digest);
                SkillProposal existing = repo.find(proposalId(agentId, type));
                if (existing != null && hash.equals(existing.digestHash())) {
                    // 证据指纹没变：同一份证据不重复打扰，也不白花一次 LLM 调用。
                    // 三种已决 / 未决状态一视同仁——PENDING 已在人面等着、ADOPTED 已装进工作台、
                    // DISMISSED 人已经对这份证据说过不用；再问一次都算打扰，且结果被 store 丢弃。
                    skipped++;
                    log.info("[skill] 证据未变，本轮不提炼 agent={} task_type={} status={}",
                            agentId, type.value(), existing.status());
                    continue;
                }
                classes++;      // 从这里起才算「真正送 LLM」

                Drawn drawn = draw(type, digest);
                if (drawn == null) {
                    failed++;   // 解析不出 JSON：不猜，下轮重试（parse 里已记完整原因）
                    continue;
                }
                if (drawn.skill() == null) {
                    quiet++;    // 判定「无新增技能」——宁可空缺，不硬凑
                    log.info("[skill] 判定无新增技能 agent={} task_type={} 理由={}",
                            agentId, type.value(), abbrev(drawn.reason()));
                    continue;
                }
                SkillProposal stored = repo.store(new SkillProposal(
                        proposalId(agentId, type), agentId, type.value(), type.label(), turns,
                        prefixed(drawn.skill().id(), agentId), drawn.skill().name(),
                        drawn.skill().description(), drawn.skill().prompt(), drawn.skill().tools(),
                        SkillProposal.PENDING, hash, null, null));
                if (stored.isPending()) {
                    offered++;
                }
            } catch (Exception e) {
                failed++;   // 单类失败不影响整批
                // 超时单独点名：这是唯一「摘要太大 / 模型太慢」才会出现的失败，与 HTTP 报错、配置错误不是一类病
                String why = (e instanceof HttpTimeoutException) ? "LLM 超时" : e.getClass().getSimpleName();
                log.warn("[skill] 提炼失败 agent={} task_type={} 原因={} turns={} digest={}字 : {}",
                        agentId, type.value(), why, turns, digest.length(), e.toString());
            }
        }

        log.info("[skill] 提炼完成 agent={} 送LLM={} 新提议={} 无新增={} 失败={} 未变跳过={} model={}",
                agentId, classes, offered, quiet, failed, skipped, llm.model());
        return new SkillDiscoverReport(true, agentId, classes, offered, quiet, failed, skipped,
                "技能由 LLM 按「Agent × 任务类型」提炼（严格口径见 system prompt），只落「待采纳」提议、"
                        + "绝不自行装进工作台；样本门槛 " + minTurns + " 条 turn。"
                        + "同一 Agent 同一类的证据没变时不再重复提炼——既不重复打扰，也不白花一次模型调用。");
    }

    /** 提议主键：一格「Agent × 一类任务」。 */
    private static String proposalId(String agentId, TaskType type) {
        return "agent:" + agentId + ":task:" + type.value();
    }

    /** 技能 id 带上 Agent 前缀：每个 Agent 一套、互不覆盖（技能仍落全局库，见登记口 A15）。 */
    private static String prefixed(String skillId, String agentId) {
        return agentSlug(agentId) + "--" + skillId;
    }

    /** Agent 作为技能 id 前缀的收敛：小写 + 非字母数字换短横线（与 {@link #skillId} 同一口径）。 */
    private static String agentSlug(String agentId) {
        String v = agentId == null ? "" : agentId.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return v.isEmpty() ? "agent" : v;
    }

    // ------------------------------------------------------------------
    // 看板（只读）：分类清单 + 提议
    // ------------------------------------------------------------------

    /**
     * 「外骨骼」页的看板：Agent 清单（始终全量，选项面）+ 在所选 Agent / Session 范围内的
     * 分类清单与提议。
     *
     * <p><b>未选 Agent 就不给类清单</b>：不拿「全库聚合」冒充「这个 Agent 的分类」。
     * 反过来说，一旦选定了 Agent，那张类清单在该范围内<b>始终全量</b>——它是这个范围的
     * 「分成了哪些类、每类多少对话」总览，也是页面自己的选项面（与 §4.7 同一规矩）。
     *
     * @param agentId   选中的 Agent（可为 null = 还没选）
     * @param sessionId 选中的会话（可为 null；只在选定 Agent 时有意义）
     */
    public SkillBoard board(String agentId, String sessionId) {
        String aid = (agentId == null || agentId.isBlank()) ? null : agentId;
        String sid = (aid == null || sessionId == null || sessionId.isBlank()) ? null : sessionId;

        List<SkillBoard.AgentStat> agents = new ArrayList<>();
        for (SkillProposalRepository.AgentStat a : repo.agents()) {
            agents.add(new SkillBoard.AgentStat(a.agentId(), a.sessions(), a.turns(), a.pending()));
        }

        List<SkillBoard.SessionStat> sessions = new ArrayList<>();
        List<SkillBoard.ClassStat> stats = new ArrayList<>();
        List<SkillProposal> proposals = List.of();

        if (aid != null) {
            for (SkillProposalRepository.SessionStat s : repo.sessions(aid)) {
                sessions.add(new SkillBoard.SessionStat(
                        s.sessionId(), s.taskType(), labelOf(s.taskType()), s.turns()));
            }
            proposals = repo.listByAgent(aid);
            Map<String, SkillProposal> byType = new HashMap<>();
            for (SkillProposal p : proposals) {
                byType.put(p.taskType(), p);
            }
            for (SkillProposalRepository.ClassCount c : repo.classDistribution(aid, sid)) {
                String tt = c.taskType();
                SkillProposal p = byType.get(tt);
                stats.add(new SkillBoard.ClassStat(
                        tt, labelOf(tt), c.sessions(), c.turns(),
                        extractable(tt) && c.turns() >= minTurns,
                        p == null ? null : p.status()));
            }
        }

        return new SkillBoard(enabled && llm.isEnabled(), minTurns, aid, sid,
                agents, sessions, stats, proposals,
                "分类清单按「Agent × 任务类型」切开：先选 Agent，再看这个 Agent 分成了哪些类、"
                        + "每类多少会话 / 对话；Session 下拉可再收窄到一个会话。"
                        + "样本门槛 " + minTurns + " 条对话——达标的类才会被提炼技能；"
                        + "「未分类」是还没跑过分类的会话（NULL），与「其他」（判过但归不进）刻意分开。");
    }

    /** 一类的展示标签；未分类单列（含 NULL），不用「其他」顶替。 */
    private static String labelOf(String taskType) {
        if (taskType == null || "__unclassified__".equals(taskType)) {
            return "未分类";
        }
        return TaskType.fromOrOther(taskType).label();
    }

    /** 该类是否属于「可提炼」的类型：排除未分类与收容筐 other。 */
    private static boolean extractable(String taskType) {
        if ("__unclassified__".equals(taskType)) {
            return false;
        }
        for (TaskType t : TaskType.values()) {
            if (t.value().equals(taskType)) {
                return t != TaskType.OTHER;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 单类提炼
    // ------------------------------------------------------------------

    /** 一个提炼出的技能（LLM 返回）。 */
    private record DrawnSkill(String id, String name, String description, String prompt, List<String> tools) {
    }

    /**
     * 一次提炼的结果：{@code skill=null} 表示「判定为无完整闭环技能」。
     *
     * <p>{@code reason} 是模型自述的「为何不够格」——<b>必须留着</b>：不留就只能知道
     * 「这一类没出技能」，答不出「为什么没出」，等于把失败原因记了一半。
     */
    private record Drawn(DrawnSkill skill, String reason) {
    }

    private Drawn draw(TaskType type, String digest) throws Exception {
        String text = llm.chat(SYSTEM, "这类任务（" + type.label() + "）的用户提问（按时间顺序）：\n" + digest);
        return parse(text, type);
    }

    /**
     * 从模型输出里抠 JSON（抄 TaskClassifier.parse：取首个 { 到末个 }），解析失败返回 null。
     *
     * <p>两支失败都在这里落日志（不这么做的话，只有「超时」那一支可见，另一支只能靠计数差反推）：
     * 抠不出 JSON，或抠出来了但 {@code skill} 缺必需字段。
     */
    private Drawn parse(String text, TaskType type) {
        if (text == null || text.isBlank()) {
            log.warn("[skill] 解析失败 task_type={} 原因=模型输出为空", type.value());
            return null;
        }
        int s = text.indexOf('{');
        int e = text.lastIndexOf('}');
        if (s < 0 || e <= s) {
            log.warn("[skill] 解析失败 task_type={} 原因=输出里没有 JSON 输出长度={} 片段={}",
                    type.value(), text.length(), abbrev(text));
            return null;
        }
        try {
            JsonNode node = mapper.readTree(text.substring(s, e + 1));
            JsonNode skill = node.path("skill");
            if (skill.isMissingNode() || skill.isNull()) {
                return new Drawn(null, node.path("reason").asText(""));
            }
            String name = skill.path("name").asText("").strip();
            String prompt = skill.path("prompt").asText("").strip();
            if (name.isEmpty() || prompt.isEmpty()) {
                // 缺必需字段 = 不合格的提炼，按「没提炼出来」处理，不硬塞一条空技能
                log.warn("[skill] 提炼不合格 task_type={} 原因=缺 name/prompt 片段={}",
                        type.value(), abbrev(text));
                return new Drawn(null, "模型输出的技能缺 name 或 prompt");
            }
            return new Drawn(new DrawnSkill(
                    skillId(skill.path("id").asText(""), type),
                    name,
                    skill.path("description").asText("").strip(),
                    prompt,
                    tools(skill.path("tools"))), "");
        } catch (Exception ex) {
            log.warn("[skill] 解析失败 task_type={} 原因={} 片段={}",
                    type.value(), ex.getClass().getSimpleName(), abbrev(text));
            return null;
        }
    }

    /** 技能 id：LLM 给的做一次收敛（小写 + 非字母数字换短横线）；空则由任务类型派生。 */
    private static String skillId(String raw, TaskType type) {
        String v = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return v.isEmpty() ? type.value().replace('_', '-') + "-skill" : v;
    }

    /** 工具白名单收敛：只留词表内的，去掉重复与空值。 */
    private static List<String> tools(JsonNode arr) {
        List<String> allowed = List.of("readFile", "writeFile", "editFile", "bash", "webSearch");
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode t : arr) {
                String v = t.asText("").strip();
                if (allowed.contains(v) && !out.contains(v)) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 摘要与指纹
    // ------------------------------------------------------------------

    /**
     * 一类任务的摘要：<b>按 turn 均匀抽样</b>（与 TaskClassifier 同法），代表性覆盖整类。
     * 放不下时按步长隔条取，首尾必取——技能要看的是「反复出现的做法」，中段不能整段丢。
     *
     * <p><b>预算必须真的兑现</b>：每条前面那截 {@code [3/12] } 和分隔换行也是字符。
     * 只拿「正文之和」跟 {@code digest-max-chars} 比，长类会一路超出去——实测 {@code refactor}
     * 575 条时摘要实得 23890 字、预算却是 20000（多出来的正是 575 条各自的前缀）。
     * 故这里先把骨架开销扣掉再分配正文预算，末尾才真正落在 {@code digestMaxChars} 以内。
     */
    private String renderDigest(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return "";
        }
        int n = texts.size();
        long total = 0;
        for (String t : texts) {
            total += t.length() + 1;
        }
        List<Integer> all = indexes(n, 1);
        int allOverhead = overhead(all, n);
        if (total + allOverhead <= digestMaxChars) {
            return render(texts, all, Math.max(1, digestMaxChars - allOverhead));
        }
        int maxTurns = Math.max(1, digestMaxChars / MIN_CHARS_PER_TURN);
        if (n <= maxTurns) {
            return render(texts, all, Math.max(1, (digestMaxChars - allOverhead) / n));
        }
        int step = (int) Math.ceil((double) n / maxTurns);
        List<Integer> idx = indexes(n, step);
        int overhead = overhead(idx, n);
        return render(texts, idx, Math.max(1, (digestMaxChars - overhead) / idx.size()));
    }

    /** 选中条目自带的骨架开销：每条一个形如 {@code [3/12] } 的前缀，外加条目之间那一个换行。 */
    private static int overhead(List<Integer> idx, int n) {
        int digitsN = Integer.toString(n).length();
        int sum = 0;
        for (int k = 0; k < idx.size(); k++) {
            sum += 4 + Integer.toString(idx.get(k) + 1).length() + digitsN;  // "[" + i + "/" + n + "] "
            if (k > 0) {
                sum += 1;                                                    // 前缀前的分隔换行
            }
        }
        return sum;
    }

    private static List<Integer> indexes(int n, int step) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < n; i += step) {
            idx.add(i);
        }
        if (idx.get(idx.size() - 1) != n - 1) {
            idx.add(n - 1);
        }
        return idx;
    }

    private String render(List<String> texts, List<Integer> idx, int perTurn) {
        int n = texts.size();
        // perTurn 已经是「扣掉前缀与换行之后的正文预算」（见 renderDigest），这里不再另留余量
        int cap = Math.max(1, perTurn);
        StringBuilder sb = new StringBuilder(Math.min(digestMaxChars + 64, 8192));
        for (int i : idx) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append('[').append(i + 1).append('/').append(n).append("] ").append(clipOne(texts.get(i), cap));
        }
        return sb.toString();
    }

    private static String clipOne(String s, int limit) {
        if (s.length() <= limit) {
            return s;
        }
        int head = (int) (limit * 0.7);
        return s.substring(0, head) + "…" + s.substring(s.length() - (limit - head));
    }

    /** 证据指纹：SHA-256(任务类型 + 摘要)。变了才允许把已「不用」的类重新提上来。 */
    private static String sha256(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static long countOf(List<SkillProposalRepository.ClassCount> counts, String taskType) {
        for (SkillProposalRepository.ClassCount c : counts) {
            if (taskType.equals(c.taskType())) {
                return c.turns();
            }
        }
        return 0;
    }

    private static SkillDiscoverReport disabled(String agentId, String note) {
        return new SkillDiscoverReport(false, agentId, 0, 0, 0, 0, 0, note);
    }

    /** 日志里的一句话截断：模型自述可能很长，只留开头。 */
    private static String abbrev(String s) {
        if (s == null || s.isBlank()) {
            return "(模型未给理由)";
        }
        String v = s.replaceAll("\\s+", " ").strip();
        return v.length() <= 200 ? v : v.substring(0, 200) + "…";
    }
}