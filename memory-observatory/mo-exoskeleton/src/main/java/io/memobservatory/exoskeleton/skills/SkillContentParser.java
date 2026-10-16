package io.memobservatory.exoskeleton.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.memobservatory.exoskeleton.model.SkillBoard;
import io.memobservatory.exoskeleton.model.SkillDiscoverAccepted;
import io.memobservatory.exoskeleton.model.SkillDiscoverCancel;
import io.memobservatory.exoskeleton.model.SkillDiscoverReport;
import io.memobservatory.exoskeleton.model.SkillDiscoverStatus;
import io.memobservatory.exoskeleton.model.SkillEvent;
import io.memobservatory.exoskeleton.model.SkillProposal;
import io.memobservatory.exoskeleton.tasks.LlmChatClient;
import io.memobservatory.exoskeleton.tasks.LlmUsageMeter;
import io.memobservatory.exoskeleton.tasks.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link SkillDiscoverer} 拆出的「内容解析 + 质量审查」协作类（无实例状态、不读配置）。
 * 负责：严格口径提示词 SYSTEM、runtime 红灯 / 三维 rubric 的词表校验、
 * LLM 输出 JSON → {@link SkillContentParser.Drawn} 的解析。
 * 门面用 {@code import static} 引这些静态件，调用点文字不变。
 */
final class SkillContentParser {

    private SkillContentParser() {}

    private static final Logger log = LoggerFactory.getLogger(SkillContentParser.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    static final String TOOLS_VOCAB = "readFile, writeFile, editFile, bash, webSearch";

    /**
     * runtime 红灯（改造 3，借 darwin-skill 的「Runtime 适配性审查」）：绑定单一运行环境的痕迹。
     *
     * <p>被绑死到一个机器 / 一个 runtime 的技能，对别的 Agent 不可用——darwin 那边直接把这类痕迹
     * 当「装不上」的红灯。故这里只拦**一眼可判**的硬痕迹：绝对家目录路径、盘符路径、家目录点目录、
     * 单 runtime 专有命令。刻意保守——宁可放过含混的一句，也不误伤正常措辞（与 {@code LlmRuleDrafter}
     * 的越界校验同一纪律）。
     */
    static final Pattern RUNTIME_RED_LIGHT = Pattern.compile(
            "(/Users/|/home/|[A-Za-z]:\\\\|~/\\.|/plugin install|mcp__claude|PostToolUse)");

    /**
     * dim2 软化措辞（改造 4）：违反「可执行具体性」。词表保守——只拦明确的含糊语，
     * 不拦单独出现的「可能」（它在「可能失败就回滚」这类具体条件里是正常措辞）。
     */
    static final Pattern SOFT_WORDS = Pattern.compile(
            "(酌情|视情况|看情况|建议根据|根据需要|可能的话)");

    /**
     * dim3 反例黑名单的存在标记（改造 4）：技能正文里须出现其一，否则视为「只讲该怎么做、
     * 不讲不该怎么做」。这是**存在性**检查，不判位置——比逐条解析「反例段」稳。
     */
    static final Pattern BLACKLIST_MARKER = Pattern.compile("(禁止|不得|不要|反例|不该)");

    static final String SYSTEM = """
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

            【铁律三：三维硬要求（缺一不可，否则本轮不产出）】
            来源：SkillLens 实测——不做约束时 LLM 判「两份技能哪份更好」准确率仅 46.4%%（比掷硬币还差），
            加上下面三维后升到 73.8%%。故把三维从「评审标准」前移为「产出标准」：
            · ① 失败模式编码：必须写出这类任务里反复出现的坑/失败及其对策，不能只写顺利路径。
            · ② 可执行具体性：步骤要具体到能照做；禁止「酌情 / 视情况 / 看情况 / 建议根据 / 根据需要 /
              可能的话」这类软化措辞——出现即视为不合格。
            · ③ 反例黑名单：必须给出「禁止事项 / 反例」段（用「禁止 / 不得 / 不要 / 反例 / 不该」等措辞），
              明确哪些做法不能做；只讲该怎么做、不讲不该怎么做，视为不合格。
            · 另外：技能必须能跨 Agent / 跨机器复用——不得写死绝对路径（/Users/…、/home/…、盘符路径）、
              家目录点目录（~/.claude/、~/.cursor/ 等）或单一 runtime 的专有命令（/plugin install、
              mcp__claude*、PostToolUse hook）。绑定单一环境的技能一律不合格。

            【心路历程：让人判断这个技能提炼得对不对】
            · 有技能时，另给一份 reasoning（心路历程）：
              logSummary = 一两句话<b>概括这批日志本身</b>——用户近期在这些日志里主要在做什么、反复出现什么做法，
                像给人介绍这批日志（先说「日志里有什么」，再说提炼出了什么），不要罗列单条；
              patterns = 这类任务里反复出现的做法/规律；preferences = 用户反复表露的偏好/习惯；
              keyPoints = 哪个判断点让你认定这是完整闭环技能；
              evidence = 一句话说明这些是从哪些会话的哪些做法归纳出来的。
            · reasoning 也<b>只从提问记录来</b>：看不到就给空数组 / 空串，绝不编造。
              （系统另会附上「依据的会话」「会话代表原话」和「几个会话 / 多少条 / 时间跨度」硬统计，供人核对，你不用重复这些。）

            严格只输出一个 JSON 对象，不要任何说明文字、注释或 markdown 代码块标记：
            无技能时： {"skill": null, "reason": "一句话说明为何不够格"}
            有技能时： {"skill": {"id": "<小写短横线命名>", "name": "<展示名>",
              "description": "<一句话能力描述>",
              "prompt": "<完整流程步骤 + 注意/禁止 + 验收自检>",
              "tools": ["readFile","editFile","bash"],
              "reasoning": {"logSummary": "<一两句话：这批日志里用户在做什么、反复出现什么做法>",
               "patterns": ["<反复出现的做法/规律>"],
               "preferences": ["<用户的偏好/习惯>"],
               "keyPoints": ["<认定它是完整闭环技能的关键判断点>"],
               "evidence": "<一句话：从哪些会话的哪些做法归纳而来>"}}}
            tools 只能从这几个里选：%s。
            """.formatted(TOOLS_VOCAB);

    /** 一个提炼出的技能（LLM 返回）。{@code reasoning} 是模型自述的心路历程（可为 null）。 */
    record DrawnSkill(String id, String name, String description, String prompt, List<String> tools,
                              SkillProposal.Reasoning reasoning) {
    }

    /**
     * 一次提炼的结果：{@code skill=null} 表示「判定为无完整闭环技能」。
     *
     * <p>{@code reason} 是模型自述的「为何不够格」——<b>必须留着</b>：不留就只能知道
     * 「这一类没出技能」，答不出「为什么没出」，等于把失败原因记了一半。
     */
    record Drawn(DrawnSkill skill, String reason) {
    }

    /**
     * 从模型输出里抠 JSON（抄 TaskClassifier.parse：取首个 { 到末个 }），解析失败返回 null。
     *
     * <p>两支失败都在这里落日志（不这么做的话，只有「超时」那一支可见，另一支只能靠计数差反推）：
     * 抠不出 JSON，或抠出来了但 {@code skill} 缺必需字段。
     */
    static Drawn parse(String text, TaskType type) {
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
            String description = skill.path("description").asText("").strip();

            // 改造 3：产物中立——绑定单一运行环境的技能不合格（跨 Agent / 跨机器不可复用），不产出
            String redLight = runtimeRedLight(name + "\n" + description + "\n" + prompt);
            if (redLight != null) {
                log.warn("[skill] 技能绑定单一运行环境 task_type={} 命中「{}」 名称={} 片段={}",
                        type.value(), redLight, name, abbrev(text));
                return new Drawn(null,
                        "技能不适配跨环境复用：疑似绑定单一运行环境（命中「" + redLight + "」），本轮不产出");
            }

            // 改造 4：三维 rubric 的机器校验（dim1 失败模式编码无法机检，由提示词约束）
            String rubric = rubricViolation(prompt);
            if (rubric != null) {
                log.warn("[skill] 技能不合三维 rubric task_type={} 原因={} 名称={} 片段={}",
                        type.value(), rubric, name, abbrev(text));
                return new Drawn(null, rubric);
            }

            return new Drawn(new DrawnSkill(
                    skillId(skill.path("id").asText(""), type),
                    name,
                    description,
                    prompt,
                    tools(skill.path("tools")),
                    llmReasoning(skill.path("reasoning"))), "");
        } catch (Exception ex) {
            log.warn("[skill] 解析失败 task_type={} 原因={} 片段={}",
                    type.value(), ex.getClass().getSimpleName(), abbrev(text));
            return null;
        }
    }

    /** 技能 id：LLM 给的做一次收敛（小写 + 非字母数字换短横线）；空则由任务类型派生。 */
    static String skillId(String raw, TaskType type) {
        String v = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return v.isEmpty() ? type.value().replace('_', '-') + "-skill" : v;
    }

    /** 工具白名单收敛：只留词表内的，去掉重复与空值。 */
    static List<String> tools(JsonNode arr) {
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

    /**
     * 改造 3：扫技能正文有没有绑定单一运行环境的红灯，命中返回命中的信号词，否则 null。
     *
     * <p>提示词里已写明「不得写死路径 / 单 runtime 命令」（铁律三末条），这里再加一道机器校验——
     * 提示词是一句话、不是闸门，模型未必每次都听。命中即不产出该技能（与 {@code LlmRuleDrafter}
     * 的越界校验同一处理：不写、并在 reason 里说明真因，不做静默丢弃）。
     */
    static String runtimeRedLight(String text) {
        Matcher m = RUNTIME_RED_LIGHT.matcher(text);
        return m.find() ? m.group() : null;
    }

    /**
     * 改造 4：三维 rubric 的机器校验，返回不合格原因（合格返回 null）。
     *
     * <p>只机检得动两维：dim2（不得有软化措辞）与 dim3（须有反例黑名单标记）。dim1（失败模式编码）
     * 无法可靠机检——「有没有写失败模式」是语义判断，词表拦不住，故由提示词约束。
     * 这是如实标注的能力边界，不是漏做。
     */
    static String rubricViolation(String prompt) {
        Matcher soft = SOFT_WORDS.matcher(prompt);
        if (soft.find()) {
            return "技能不合三维 rubric：含软化措辞「" + soft.group() + "」（违反可执行具体性）";
        }
        if (!BLACKLIST_MARKER.matcher(prompt).find()) {
            return "技能不合三维 rubric：缺「禁止事项 / 反例」清单（只讲该怎么做、不讲不该怎么做）";
        }
        return null;
    }

    /**
     * 读模型自述的心路历程节点。缺失 / 非对象 → null（旧模型没这个字段时照旧可用）；
     * 只取模型该给的四项（patterns / preferences / keyPoints / evidence），
     * sessions / samples 由系统另行从证据里填——模型自述与确定性证据分列，便于人核对（A2）。
     */
    static SkillProposal.Reasoning llmReasoning(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
            return null;
        }
        SkillProposal.Reasoning r = new SkillProposal.Reasoning(
                textList(node.path("patterns")),
                textList(node.path("preferences")),
                textList(node.path("keyPoints")),
                node.path("evidence").asText("").strip(),
                List.of(), List.of(),
                node.path("logSummary").asText("").strip(), List.of());
        return r.isEmpty() ? null : r;
    }

    /** 读一个「字符串数组」节点：非数组即空；逐项 strip、去空、去重。 */
    static List<String> textList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode t : arr) {
                String v = t.asText("").strip();
                if (!v.isEmpty() && !out.contains(v)) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    /** 日志里的一句话截断：模型自述可能很长，只留开头。 */
    static String abbrev(String s) {
        if (s == null || s.isBlank()) {
            return "(模型未给理由)";
        }
        String v = s.replaceAll("\\s+", " ").strip();
        return v.length() <= 200 ? v : v.substring(0, 200) + "…";
    }
}
