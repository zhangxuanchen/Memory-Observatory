package io.memobservatory.exoskeleton.skills;

import static io.memobservatory.exoskeleton.skills.SkillContentParser.*;
import static io.memobservatory.exoskeleton.skills.SkillBoardFormatter.*;

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


    private final LlmChatClient llm;
    private final SkillProposalRepository repo;
    private final LlmUsageMeter meter;
    /**
     * 装载回流的读侧（只读）：供看板显示「这个技能被装载过几次」。可为 null（单测替身不注入，
     * 此时看板的 {@code loads} 为空，不影响其余字段）。
     */
    private final SkillLoadRepository loadRepo;
    /**
     * 运行态落库（P1-3）：一轮提炼的进度与终态。可为 null（单测替身不注入，此时运行态不落库，
     * 其余行为不变）。
     */
    private final SkillRunRepository runRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 提炼进度注册表：key = Agent。它既是<b>互斥锁</b>，也是<b>进度面</b>。
     *
     * <p><b>为什么一张表同时干两件事</b>：互斥语义（同一 Agent 不并发重复发 LLM 调用）与进度展示
     * （跑了多少类、跑到哪、要不要取消）都挂在「这个 Agent 本轮跑成什么样」上，拆成两份状态只会让
     * 二者走样。故用一份 {@code ConcurrentHashMap<String, Progress>}。
     *
     * <p><b>前提：单实例部署</b>——本模块是单进程，用进程内的 {@link ConcurrentHashMap} 做标志即可，
     * 不引入 Redis / DB 锁。若将来多实例部署，这里须换成分布式锁。
     *
     * <p><b>生命周期（务必与状态接口一致）</b>：本轮提炼开始时经 {@link #acquire} <b>原子地重置</b>
     * 该 Agent 的条目并置 {@code running=true}；跑完（正常结束或取消）后 {@code running=false} 但
     * <b>条目保留</b>——{@code status} 要靠它读 {@code lastReport} 与最终进度，页面刷新也能据此恢复；
     * 直到<b>下一次同一 Agent 的提炼开始时</b>才整体重置。Agent 数量有界（即遥测里的 Agent），
     * 每 Agent 至多一条，无需清理。
     */
    private final ConcurrentHashMap<String, Progress> progress = new ConcurrentHashMap<>();

    /**
     * 异步提炼的后台线程池：每 Agent 至多一个在跑，任务数有界；用守护线程，不阻塞应用退出。
     */
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "skill-discover");
        t.setDaemon(true);
        return t;
    });

    /** 一个 Agent 本轮的进度（进程内内存态；生命周期见 {@link #progress}）。 */
    private static final class Progress {
        /** 本轮运行 id（落 {@code memory_skill_runs} 的主键；内存态与落库行靠它对应）。 */
        final String runId;
        final Instant startedAt;
        /** 是否在跑：互斥与 status 都靠它。 */
        volatile boolean running = true;
        /** 是否已请求取消：主循环每个类开始前查它。 */
        volatile boolean cancelRequested = false;
        /**
         * 本轮<b>提前停下</b>的原因（P2）：null = 正常跑完；非空取 {@link SkillDiscoverReport} 的
         * {@code STOP_*} 常量。它取代了从前的布尔 {@code cancelled}——停下可能是四件不同的事
         * （取消 / 时长预算 / token 预算 / 熔断），合成一个 true 就答不出「为什么停」，
         * 而收尾的运行态（CANCELLED vs HALTED）与报告的原因都要靠它区分。
         */
        volatile String stopReason = null;
        /** 是否意外中断（后台线程抛了未预期的异常）：收尾据此写 INTERRUPTED，不谎报 DONE。 */
        volatile boolean aborted = false;
        volatile int totalClasses = 0;
        /** 已完成类数：并发提炼时多线程自增，故用原子量（进度面的分母见 {@link #totalClasses}）。 */
        final AtomicInteger doneClasses = new AtomicInteger(0);
        /**
         * 本轮累计 token（各次成功调用 {@code usage.total_tokens} 之和，P2）：供 {@code budget-tokens}
         * 判据与进度面对照。并发提炼时多线程累加，故用原子量。
         */
        final AtomicLong tokens = new AtomicLong();
        /**
         * LLM 连续失败计数（P2 熔断）：送 LLM 的类失败即累加，成功即清零；跳过 / 空类不改变
         * （它们没发调用、对「通道是否在连续失败」没有结论）。并发下用原子量，读到的是近似值——
         * 熔断本就是「尽力而为」的止损，不追求并发下的精确计数。
         */
        final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        volatile String currentClass = null;
        volatile Instant finishedAt = null;
        /** 上一次跑完的报告（跑完才赋值；供 status 读取）。 */
        volatile SkillDiscoverReport lastReport = null;
        /** 本轮每一类没提炼出来的原因（task_type → 中文一句话），供看板类清单展示。 */
        final Map<String, String> reasons = new ConcurrentHashMap<>();

        Progress(String runId, Instant startedAt) {
            this.runId = runId;
            this.startedAt = startedAt;
        }
    }

    @Value("${mo.exoskeleton.skills.enabled:true}")
    private boolean enabled;
    @Value("${mo.exoskeleton.skills.min-turns:30}")
    private int minTurns;
    @Value("${mo.exoskeleton.skills.max-classes-per-run:8}")
    private int maxClasses;
    @Value("${mo.exoskeleton.skills.digest-max-chars:20000}")
    private int digestMaxChars;
    /**
     * 取原文时在 SQL 层的抽样上限（每类最多取几条 turn 进摘要）。
     *
     * <p><b>默认 0 = 不抽样、取全量，行为与从前完全一致</b>。设成正数只是「少读一点、跑快一点」，
     * 但会<b>改变证据集</b>（因而改证据指纹，可能触发一轮重提炼）——故默认关着，
     * 只在看板卡顿、且能接受重提炼时才显式打开（见登记口 B21）。
     */
    @Value("${mo.exoskeleton.skills.sample-turns:0}")
    private int sampleTurns;
    /**
     * 提炼的并发度：同时有几类在跑 LLM。
     *
     * <p><b>默认 1 = 行为与从前完全一致</b>（逐类串行）。调大只是「同时多问几类」——
     * 类与类之间本来就互不依赖（各算各的摘要，各落各的提议），故并发是安全的；
     * 计数与进度走原子量 / 并发容器（见 {@link Progress}）。
     *
     * <p><b>为什么默认不开</b>：并发只是让墙钟时间变短，不改变任何判据；默认关着，
     * 是为了不在没人要求时改变既有行为与模型侧的并发压力（见登记口 B16.5）。
     */
    @Value("${mo.exoskeleton.skills.parallelism:1}")
    private int parallelism;
    /**
     * 单轮总墙钟时长预算（毫秒，P2）：一轮提炼累计耗时达到它就<b>类间</b>提前停下。
     *
     * <p><b>默认 0 = 不启用，行为与从前完全一致</b>。停下的类不丢——断点续跑靠 {@code class_runs} 记账，
     * 下一轮接着跑（见 README / 登记口 A24）。
     */
    @Value("${mo.exoskeleton.skills.budget-ms:0}")
    private long budgetMs;
    /**
     * 单轮总 token 预算（P2）：一轮累计 token（各次成功调用的 {@code total_tokens} 之和）达到它就停下。
     *
     * <p><b>默认 0 = 不启用</b>。它比「类数上限」更贴成本：各类摘要长度差几十倍，按类数封顶仍可能一次烧很多。
     */
    @Value("${mo.exoskeleton.skills.budget-tokens:0}")
    private long budgetTokens;
    /**
     * 熔断阈值（P2）：LLM 连续失败达到它，即停发后续调用，防止「模型侧持续故障还一路空烧」。
     *
     * <p><b>默认 0 = 关闭熔断</b>。成功一次即清零；跳过 / 空类不影响计数（没发调用，没有结论）。
     * 已失败的类下轮重试（FAILED 不参与跳过）。
     */
    @Value("${mo.exoskeleton.skills.breaker-max-failures:0}")
    private int breakerMaxFailures;

    @Autowired
    public SkillDiscoverer(LlmChatClient llm, SkillProposalRepository repo, LlmUsageMeter meter,
                           SkillLoadRepository loadRepo, SkillRunRepository runRepo) {
        this.llm = llm;
        this.repo = repo;
        this.meter = meter;
        this.loadRepo = loadRepo;
        this.runRepo = runRepo;
    }

    /**
     * 三参构造：供单测的手写替身用（不跑 Spring）。装载回流读侧与运行态落库缺省为 null——
     * 那时看板的 {@code loads} 为空、运行态不落库，均不影响提炼与其余看板字段。
     */
    protected SkillDiscoverer(LlmChatClient llm, SkillProposalRepository repo, LlmUsageMeter meter) {
        this(llm, repo, meter, null, null);
    }

    /**
     * 跑一轮提炼（同步）：<b>只针对一个 Agent</b>。幂等——同一 Agent 同一类同一份证据（指纹相同）不重复落。
     *
     * <p><b>行为不变</b>：脚本化调用方仍走这条，签名与报告结构（除把 {@code cancelled} 布尔换成
     * {@code stopReason} 判据外）原样保留；已在跑时同样如实返回「跳过」报告，不重复发 LLM。
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
        Progress p = acquire(agentId);
        if (p == null) {
            // 该 Agent 已有提炼在跑：如实报告「跳过」，不报错、不伪装成功、不重复发 LLM 调用
            log.info("[skill] 该 Agent 正在提炼中，本轮跳过 agent={}", agentId);
            return runningSkip(agentId);
        }
        try {
            SkillDiscoverReport report = discover(agentId, p);
            p.lastReport = report;
            return report;
        } finally {
            finish(p);
        }
    }

    /**
     * 发起一轮提炼（异步）：<b>立即返回</b>，真正的提炼在后台线程跑，不阻塞调用方。
     *
     * <p>与同步 {@link #runOnce} 共用同一个 {@link #acquire} 占位——二者互斥语义完全一致：
     * 同一 Agent 已有提炼在跑时，这里同样<b>如实拒绝</b>（{@code accepted=false}）、不重复发 LLM。
     *
     * @param agentId 要提炼的 Agent
     * @return 受理回执；被拒时 {@code note} 写明缘由（已在跑 / 未启用 / 未指定 Agent）
     */
    public SkillDiscoverAccepted startAsync(String agentId) {
        if (!enabled) {
            return new SkillDiscoverAccepted(false, agentId, false,
                    "未启用：mo.exoskeleton.skills.enabled=false。");
        }
        if (!llm.isEnabled()) {
            return new SkillDiscoverAccepted(false, agentId, false,
                    "未启用：LLM 通道未配置（mo.exoskeleton.tasks.llm.base-url / model 为空）。");
        }
        if (agentId == null || agentId.isBlank()) {
            return new SkillDiscoverAccepted(false, null, false,
                    "未指定 Agent：提炼以「Agent × 任务类型」为单位，须先选定一个 Agent。");
        }
        Progress p = acquire(agentId);
        if (p == null) {
            log.info("[skill] 该 Agent 正在提炼中，异步请求未重复发起 agent={}", agentId);
            return new SkillDiscoverAccepted(false, agentId, true,
                    "该 Agent 正在提炼中，未重复发起（不重复发 LLM 调用）。");
        }
        executor.submit(() -> {
            try {
                p.lastReport = discover(agentId, p);
            } catch (Throwable t) {
                // 后台线程里的意外异常不能让进度卡在 running=true——如实记日志，收尾仍旧置非运行。
                // 同时标 aborted：收尾据此把运行态写成 INTERRUPTED，不把没跑完的一轮谎报成 DONE。
                p.aborted = true;
                log.warn("[skill] 异步提炼异常 agent={} : {}", agentId, t.toString());
            } finally {
                finish(p);
            }
        });
        return new SkillDiscoverAccepted(true, agentId, true,
                "已受理：正在后台提炼，请轮询 status 查看进度。");
    }

    /**
     * 查询某 Agent 的提炼进度（含上一次跑完的报告）。从未提炼过的 Agent 各进度字段如实给 null。
     *
     * <p>进度条目在跑完后<b>保留</b>，故这里能读到最终进度与 {@code lastReport}——页面刷新后
     * 也能据此恢复；直到下一次同 Agent 的提炼开始时才重置（生命周期见 {@link #progress}）。
     */
    public SkillDiscoverStatus status(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return new SkillDiscoverStatus(null, false, null, null, null, null, null, null, false, null, null);
        }
        Progress p = progress.get(agentId);
        // 运行态读数取自落库（进程重启后内存 Progress 已空，只有落库才答得出「上轮被中断」）
        SkillDiscoverStatus.RunStat lastRun = lastRunOf(agentId);
        if (p == null) {
            return new SkillDiscoverStatus(agentId, false, null, null, null, null, null, null, false, null, lastRun);
        }
        Instant end = p.running ? null : (p.finishedAt == null ? Instant.now() : p.finishedAt);
        long elapsed = Duration.between(p.startedAt, end == null ? Instant.now() : end).toMillis();
        return new SkillDiscoverStatus(agentId, p.running, p.startedAt.toString(), elapsed, p.tokens.get(),
                p.totalClasses, p.doneClasses.get(), p.currentClass, p.cancelRequested, p.lastReport, lastRun);
    }

    /**
     * 读某 Agent 最近一轮的落库运行态（只读、旁路）：失败只记日志、返回 null——
     * 运行态是观测旁路，不能因为读不到就把 status 整条带下水。
     */
    private SkillDiscoverStatus.RunStat lastRunOf(String agentId) {
        if (runRepo == null) {
            return null;
        }
        try {
            SkillRunRepository.RunStat r = runRepo.lastRun(agentId);
            if (r == null) {
                return null;
            }
            return new SkillDiscoverStatus.RunStat(r.runId(), r.status(), r.totalClasses(), r.doneClasses(),
                    r.currentClass(),
                    r.startedAt() == null ? null : r.startedAt().toString(),
                    r.finishedAt() == null ? null : r.finishedAt().toString());
        } catch (Exception e) {
            log.warn("[skill] 读取运行态失败（status 按无运行态继续）：{}", e.toString());
            return null;
        }
    }

    /**
     * 请求取消该 Agent 正在跑的本轮提炼。
     *
     * <p><b>取消是真取消</b>：主循环在<b>每个类开始前</b>查这个标志，为真即停并返回已完成的局部结果，
     * 不再往后发任何 LLM 调用——不是只在前端断开等待。正在跑的那个类无法从中途打断（调用已发出）。
     *
     * <p>当前没有在跑的提炼时如实回 {@code cancelRequested=false}，不谎报「已取消」。
     */
    public SkillDiscoverCancel cancel(String agentId) {
        Progress p = (agentId == null || agentId.isBlank()) ? null : progress.get(agentId);
        if (p != null && p.running) {
            p.cancelRequested = true;
            return new SkillDiscoverCancel(true, "已请求取消：本轮会在当前类完成后停下。");
        }
        return new SkillDiscoverCancel(false, "当前没有正在跑的提炼，无需取消。");
    }

    /**
     * 原子占位：把该 Agent 的进度重置为「新一轮开始」并返回；若已有本轮在跑则返回 null。
     *
     * <p>用 {@code compute} 把「读 running + 写占位」并成一次原子操作——两次 async（或 async 与 sync）
     * 同时进来时，只有一次能拿到占位，另一次如实被拒，谁也不重复发 LLM。
     */
    private Progress acquire(String agentId) {
        boolean[] taken = {false};
        Progress p = progress.compute(agentId, (k, cur) -> {
            if (cur != null && cur.running) {
                return cur;                       // 已在跑：原样保留，taken 保持 false
            }
            taken[0] = true;
            // 新一轮开始：整体重置进度与原因，并为本次运行分配 run_id（落库行与内存态靠它对应）
            return new Progress(agentId + "#" + System.currentTimeMillis(), Instant.now());
        });
        if (!taken[0]) {
            return null;
        }
        beginRun(agentId, p);   // 旁路：开场先落一行 RUNNING，随后进度逐类刷新
        return p;
    }

    // ------------------------------------------------------------------
    // 运行态落库（P1-3）：全是旁路——写不进去只记日志，绝不掀翻提炼
    // ------------------------------------------------------------------

    /** 开跑：落一行 RUNNING。失败只记日志（运行态是旁路）。 */
    private void beginRun(String agentId, Progress p) {
        if (runRepo == null) {
            return;
        }
        try {
            runRepo.start(p.runId, agentId);
        } catch (Exception e) {
            log.warn("[skill] 落运行态失败（开跑） agent={} run={} : {}", agentId, p.runId, e.toString());
        }
    }

    /** 记本轮分母（过门槛后的真实类数）。失败只记日志。 */
    private void noteRunTotals(Progress p) {
        if (runRepo == null) {
            return;
        }
        try {
            runRepo.setTotals(p.runId, p.totalClasses);
        } catch (Exception e) {
            log.warn("[skill] 落运行态失败（分母） run={} : {}", p.runId, e.toString());
        }
    }

    /** 记当前类（只作展示）。失败只记日志。 */
    private void noteRunCurrent(Progress p, String currentClass) {
        if (runRepo == null) {
            return;
        }
        try {
            runRepo.setCurrent(p.runId, currentClass);
        } catch (Exception e) {
            log.warn("[skill] 落运行态失败（当前类） run={} : {}", p.runId, e.toString());
        }
    }

    /**
     * 一类完成：进程内计数原子自增 + 落库原子自增。
     *
     * <p><b>两处都要自增</b>：进程内那份供 status 即时读（不每次查库），落库那份供重启后读；
     * 二者同一语义，且都走原子自增以免并发丢更新。落库失败只记日志。
     */
    private void markClassDone(Progress p) {
        p.doneClasses.incrementAndGet();
        if (runRepo == null) {
            return;
        }
        try {
            runRepo.bumpDone(p.runId);
        } catch (Exception e) {
            log.warn("[skill] 落运行态失败（完成一类） run={} : {}", p.runId, e.toString());
        }
    }

    /** 被拒（已在跑）时的同步报告：enabled 仍为 true，计数全 0，如实说明。 */
    private static SkillDiscoverReport runningSkip(String agentId) {
        return new SkillDiscoverReport(true, agentId, 0, 0, 0, 0, 0, null,
                "该 Agent 正在提炼中，本轮跳过（不重复发 LLM 调用）。");
    }

    /**
     * 收尾：清当前类、记完成时刻、最后置非运行（进度条目本身保留，供 status 读取），
     * 并把终态写进 {@code memory_skill_runs}（旁路，失败只记日志）。
     */
    private void finish(Progress p) {
        p.currentClass = null;
        p.finishedAt = Instant.now();
        p.running = false;   // 最后置 false：status 读到 running=false 时其余字段均已就绪
        finishRun(p);
    }

    /**
     * 写运行态终态：意外中断 → {@code INTERRUPTED}，人为取消 → {@code CANCELLED}，
     * 预算 / 熔断提前停下 → {@code HALTED}，其余 → {@code DONE}。
     *
     * <p>为什么「意外中断」也要如实标 INTERRUPTED：后台线程抛了未预期的异常意味着本轮没跑完，
     * 记成 DONE 是谎报成功。失败只记日志——运行态是旁路。
     */
    private void finishRun(Progress p) {
        if (runRepo == null) {
            return;
        }
        String status = p.aborted ? SkillRunRepository.INTERRUPTED
                : SkillDiscoverReport.STOP_CANCEL.equals(p.stopReason) ? SkillRunRepository.CANCELLED
                : p.stopReason != null ? SkillRunRepository.HALTED
                : SkillRunRepository.DONE;
        try {
            runRepo.finish(p.runId, status);
        } catch (Exception e) {
            log.warn("[skill] 落运行态失败（终态 {}） run={} : {}", status, p.runId, e.toString());
        }
    }

    /** 提炼主体：{@link #runOnce} / {@link #startAsync} 已在进入前经 {@link #acquire} 独占该 Agent。 */
    private SkillDiscoverReport discover(String agentId, Progress p) {
        var counts = repo.classDistribution(agentId, null);

        // 先算「本轮真正要处理的类清单」：过 min-turns 门槛、可提炼（排除 other），再按预算截断。
        // totalClasses 取这份清单的长度——不是 TaskType 枚举的 8 个，也不是「已送 LLM」的累加：
        // 分母一开始就稳定，页面能立刻显示「0/2」而不是从 0/0 跳变。
        List<TaskType> eligible = new ArrayList<>();
        for (TaskType type : TaskType.values()) {
            if (type == TaskType.OTHER) {
                continue;   // 「其他」是收容筐，不构成一类可提炼的手艺
            }
            long turns = countOf(counts, type.value());
            if (turns < minTurns) {
                continue;   // 样本不足：不提炼，也不报失败（它在清单里显示为未达标）
            }
            eligible.add(type);
        }
        int budget = Math.max(1, maxClasses);
        List<TaskType> todo = eligible.size() > budget ? eligible.subList(0, budget) : eligible;
        // 预算外没排上的类：写明原因，别让看板只剩空白
        for (int i = budget; i < eligible.size(); i++) {
            p.reasons.put(eligible.get(i).value(),
                    "本轮类数已达上限（max-classes-per-run=" + budget + "），下轮再来。");
        }
        p.totalClasses = todo.size();
        noteRunTotals(p);   // 旁路：把分母落库，重启后也能答出「上轮跑到 done/total」

        Counters c = new Counters();
        boolean stopped = parallelism > 1
                ? runParallel(agentId, todo, counts, p, c)
                : runSequential(agentId, todo, counts, p, c);

        log.info("[skill] 提炼完成 agent={} 送LLM={} 新提议={} 无新增={} 失败={} 未变跳过={} 停下={} tokens={} model={}",
                agentId, c.classes.get(), c.offered.get(), c.quiet.get(), c.failed.get(), c.skipped.get(),
                p.stopReason == null ? "否" : p.stopReason, p.tokens.get(), llm.model());
        String note = stopped ? stopNote(p.stopReason) : ranToEndNote();
        return new SkillDiscoverReport(true, agentId, c.classes.get(), c.offered.get(),
                c.quiet.get(), c.failed.get(), c.skipped.get(), p.stopReason, note);
    }

    /** 正常跑完时的口径说明。 */
    private String ranToEndNote() {
        return "技能由 LLM 按「Agent × 任务类型」提炼（严格口径见 system prompt），只落「待采纳」提议、"
                + "绝不自行装进工作台；样本门槛 " + minTurns + " 条 turn。"
                + "同一 Agent 同一类的证据没变时不再重复提炼——既不重复打扰，也不白花一次模型调用；"
                + "判过而确无技能的类也记账，证据未变则下轮不再重问。";
    }

    /**
     * 提前停下时的一句话原因（人面直接显示）：把「为什么这一轮没跑完」如实说清，
     * 并点明「剩下的类下一轮还会继续」，不让人误以为要手动重来。
     */
    private static String stopNote(String reason) {
        return switch (reason) {
            case SkillDiscoverReport.STOP_CANCEL ->
                    "本轮已按取消请求提前停下，返回的是已完成的局部结果。";
            case SkillDiscoverReport.STOP_BUDGET_TIME ->
                    "本轮已达单轮总时长预算（budget-ms），提前停下；返回的是已完成的局部结果，剩余类下一轮继续。";
            case SkillDiscoverReport.STOP_BUDGET_TOKENS ->
                    "本轮已达单轮总 token 预算（budget-tokens），提前停下；返回的是已完成的局部结果，剩余类下一轮继续。";
            case SkillDiscoverReport.STOP_BREAKER ->
                    "LLM 已连续失败达到熔断阈值（breaker-max-failures），本轮不再发后续调用；"
                            + "已失败的类会在下一轮重试（失败不参与跳过）。";
            default -> "本轮提前停下，返回的是已完成的局部结果。";
        };
    }

    /** 本轮各类结果计数：并发时多线程自增，故用原子量。 */
    private static final class Counters {
        /** 真正送 LLM 的类数（= offered + quiet + failed）。 */
        final AtomicInteger classes = new AtomicInteger();
        final AtomicInteger offered = new AtomicInteger();
        final AtomicInteger quiet = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        /** 证据未变而跳过的类（未送 LLM）。 */
        final AtomicInteger skipped = new AtomicInteger();
    }

    /** 逐类跑（并发度 = 1）：行为与从前完全一致。返回是否因提前停下（取消 / 预算 / 熔断）而中断。 */
    private boolean runSequential(String agentId, List<TaskType> todo,
                                  List<SkillProposalRepository.ClassCount> counts, Progress p, Counters c) {
        for (int i = 0; i < todo.size(); i++) {
            String stop = checkStop(p);
            if (stop != null) {
                markStopped(agentId, todo, i, p, stop);   // 真停：从这类起不再发任何 LLM 调用
                return true;
            }
            TaskType type = todo.get(i);
            p.currentClass = type.value();
            noteRunCurrent(p, p.currentClass);   // 旁路：把「跑到哪一类」落库
            try {
                String outcome = processClass(agentId, type, countOf(counts, type.value()), p);
                tally(outcome, c);
                noteOutcome(p, outcome);   // 熔断计数：成功清零、失败累加
            } finally {
                markClassDone(p);   // 进程内计数 + 落库原子自增
            }
        }
        return false;
    }

    /**
     * 按类并发跑（并发度 &gt; 1）：各类互不依赖，故可同时送 LLM。
     *
     * <p><b>取消 / 预算 / 熔断语义不变</b>：提交每一类前查一次 {@link #checkStop}，命中即停止<b>再提交</b>；
     * 已在跑的类按「在跑的类不可中断」原样跑完，故这里会先把已提交的等完，再记未提交的那些。
     *
     * <p>单类的异常在 {@link #processClass} 内已消化，故这里的 {@code get()} 正常不抛；
     * 兜底捕获只为不让一个类的意外掀翻整轮。
     */
    private boolean runParallel(String agentId, List<TaskType> todo,
                                List<SkillProposalRepository.ClassCount> counts, Progress p, Counters c) {
        int n = Math.max(1, Math.min(parallelism, todo.size()));
        ExecutorService pool = Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, "skill-discover-class");
            t.setDaemon(true);
            return t;
        });
        List<Future<?>> futures = new ArrayList<>();
        int stoppedAt = todo.size();
        String stoppedReason = null;
        try {
            for (int i = 0; i < todo.size(); i++) {
                String stop = checkStop(p);
                if (stop != null) {
                    stoppedAt = i;
                    stoppedReason = stop;
                    break;
                }
                TaskType type = todo.get(i);
                futures.add(pool.submit(() -> {
                    try {
                        String outcome = processClass(agentId, type, countOf(counts, type.value()), p);
                        tally(outcome, c);
                        noteOutcome(p, outcome);   // 熔断计数：成功清零、失败累加
                    } finally {
                        markClassDone(p);   // 进程内计数 + 落库原子自增（并发下禁读改写）
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    log.warn("[skill] 并发提炼单类异常 agent={} : {}", agentId, e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        if (stoppedAt < todo.size()) {
            markStopped(agentId, todo, stoppedAt, p, stoppedReason);
            return true;
        }
        return false;
    }

    /**
     * 类间检查：返回提前停下的原因（null = 继续跑）。串行与并发两条路径共用同一判据，
     * 保证「取消 / 时长预算 / token 预算 / 熔断」四种停法在两个分支里行为一致。
     *
     * <p><b>预算 / 熔断都在「类间」生效</b>（与取消同规矩）：调用一旦发出就无法中途打断，
     * 故只能保证「命中后不再发下一类」。这是代价可接受的边界——它防的是「一路空烧到底」，
     * 不是「精确掐到毫秒」。
     */
    private String checkStop(Progress p) {
        if (p.cancelRequested) {
            return SkillDiscoverReport.STOP_CANCEL;
        }
        if (budgetMs > 0 && Duration.between(p.startedAt, Instant.now()).toMillis() >= budgetMs) {
            return SkillDiscoverReport.STOP_BUDGET_TIME;
        }
        if (budgetTokens > 0 && p.tokens.get() >= budgetTokens) {
            return SkillDiscoverReport.STOP_BUDGET_TOKENS;
        }
        if (breakerMaxFailures > 0 && p.consecutiveFailures.get() >= breakerMaxFailures) {
            return SkillDiscoverReport.STOP_BREAKER;
        }
        return null;
    }

    /**
     * 熔断计数维护（P2）：真正送 LLM 的类，成功（出提议 / 判无技能）即清零、失败即累加；
     * 跳过（{@code SKIPPED}）与空类（{@code EMPTY}）本就没发调用，对「通道是否在连续失败」没有结论，
     * 故不改变计数——否则一次跳过就会把连续失败的证据抹掉。
     */
    private static void noteOutcome(Progress p, String outcome) {
        switch (outcome) {
            case O_OFFERED, O_QUIET -> p.consecutiveFailures.set(0);
            case O_FAILED -> p.consecutiveFailures.incrementAndGet();
            default -> {
                // O_SKIPPED / O_EMPTY：没发调用，不改变连续失败计数
            }
        }
    }

    /**
     * 提前停下时给「尚未处理」的类记原因，并把停下原因钉进进度；串行与并发两条路径共用。
     *
     * <p>记的原因分四种（取消 / 时长预算 / token 预算 / 熔断），看板类清单据此逐类说明
     * 「这一类为什么这轮没跑」——不写清楚，人只看得到一片空白。
     */
    private void markStopped(String agentId, List<TaskType> todo, int from, Progress p, String reason) {
        p.stopReason = reason;   // 收尾据此写 CANCELLED / HALTED
        String why = stopReasonText(reason);
        for (int j = from; j < todo.size(); j++) {
            p.reasons.put(todo.get(j).value(), why);
        }
        log.info("[skill] 本轮提前停下（{}） agent={} 已处理={}/{}",
                reason, agentId, p.doneClasses.get(), p.totalClasses);
    }

    /** 尚未处理的类在看板里显示的一句话原因（按停下方式分）。 */
    private static String stopReasonText(String reason) {
        return switch (reason) {
            case SkillDiscoverReport.STOP_CANCEL -> "本轮已取消，尚未处理。";
            case SkillDiscoverReport.STOP_BUDGET_TIME -> "本轮已达总时长预算，尚未处理（下一轮继续）。";
            case SkillDiscoverReport.STOP_BUDGET_TOKENS -> "本轮已达总 token 预算，尚未处理（下一轮继续）。";
            case SkillDiscoverReport.STOP_BREAKER -> "LLM 连续失败已触达熔断阈值，本轮停止提炼（下一轮重试）。";
            default -> "本轮提前停下，尚未处理。";
        };
    }

    /** 把一类的处理结果归入计数（结果码见 {@link #processClass}）。 */
    private static void tally(String outcome, Counters c) {
        switch (outcome) {
            case O_OFFERED -> {
                c.classes.incrementAndGet();
                c.offered.incrementAndGet();
            }
            case O_QUIET -> {
                c.classes.incrementAndGet();
                c.quiet.incrementAndGet();
            }
            case O_FAILED -> {
                c.classes.incrementAndGet();
                c.failed.incrementAndGet();
            }
            case O_SKIPPED -> c.skipped.incrementAndGet();
            default -> {
                // O_EMPTY：没有可读原文，既不算送 LLM，也不算跳过
            }
        }
    }

    private static final String O_OFFERED = "OFFERED";
    private static final String O_QUIET = "QUIET";
    private static final String O_FAILED = "FAILED";
    private static final String O_SKIPPED = "SKIPPED";
    private static final String O_EMPTY = "EMPTY";

    /**
     * 处理一类：读原文 → 算摘要指纹 → 判「要不要送 LLM」→ 送 → 按结果记账；返回结果码。
     *
     * <p><b>单类的任何失败都在这里消化</b>，不向外抛——故并发跑时一个类炸了不会掀翻整批（纪律二）。
     *
     * <p><b>两道跳过闸门</b>：
     * <ol>
     *   <li>已有提议且指纹相同——同一份证据不重复打扰，也不重复烧模型（原有行为）；</li>
     *   <li>上轮判过「这类无完整闭环技能」（{@code QUIET}）且指纹相同——断点续跑的收敛：
     *       上轮既然已判过、证据又没变，这轮就不该再问一次模型（见 schema.sql 的表注释）。</li>
     * </ol>
     * 注意：{@code FAILED} <b>不参与</b>跳过——把失败也跳过，等于把错误固化下来。
     */
    private String processClass(String agentId, TaskType type, long turns, Progress p) {
        String digest = "";
        String hash = "";
        try {
            // 缺省（sampleTurns<=0）仍走原两参方法：与改造前逐字一致，也让单测替身穿插的取数点不失效。
            List<String> texts = (sampleTurns > 0)
                    ? repo.classTurnTexts(agentId, type.value(), sampleTurns)
                    : repo.classTurnTexts(agentId, type.value());
            digest = EvidenceDigest.render(texts, digestMaxChars);
            if (digest.isBlank()) {
                // 没有可读的对话原文：无从提炼，也不算一类
                p.reasons.put(type.value(), "没有可读的对话原文，无从提炼。");
                return O_EMPTY;
            }
            hash = EvidenceDigest.sha256(agentId + "\n" + type.value() + "\n" + digest);
            SkillProposal existing = repo.find(proposalId(agentId, type));
            if (existing != null && hash.equals(existing.digestHash())) {
                // 证据指纹没变：同一份证据不重复打扰，也不白花一次 LLM 调用。
                // 三种已决 / 未决状态一视同仁——PENDING 已在人面等着、ADOPTED 已装进工作台、
                // DISMISSED 人已经对这份证据说过不用；再问一次都算打扰，且结果被 store 丢弃。
                p.reasons.put(type.value(), "证据未变，本轮跳过（同一份证据不重复提炼）。");
                log.info("[skill] 证据未变，本轮不提炼 agent={} task_type={} status={}",
                        agentId, type.value(), existing.status());
                note(SkillEvent.SKIP, agentId, type.value(), proposalId(agentId, type), existing.version(),
                        hash, "证据指纹未变（既有提议 " + existing.status() + "），本轮跳过，不重复提炼。");
                return O_SKIPPED;
            }
            // 断点续跑：上轮判过「这类无完整闭环技能」且证据未变 → 本轮不再送 LLM
            SkillProposalRepository.ClassRun prior = repo.findClassRun(agentId, type.value());
            if (prior != null && O_QUIET.equals(prior.outcome()) && hash.equals(prior.digestHash())) {
                p.reasons.put(type.value(), "上轮已判此类无完整闭环技能，证据未变，本轮不重复提炼。");
                log.info("[skill] 上轮判过无技能且证据未变，本轮跳过 agent={} task_type={}",
                        agentId, type.value());
                note(SkillEvent.SKIP, agentId, type.value(), proposalId(agentId, type), null,
                        hash, "上轮已判此类无完整闭环技能、证据未变，本轮跳过。");
                return O_SKIPPED;
            }

            Drawn drawn = draw(agentId, type, digest, p);
            if (drawn == null) {
                // 解析不出 JSON：不猜，下轮重试（parse 里已记完整原因）
                String why = "解析失败：模型输出不是合法 JSON 或缺必需字段。";
                p.reasons.put(type.value(), why);
                remember(agentId, type, hash, O_FAILED, turns, why);
                return O_FAILED;
            }
            if (drawn.skill() == null) {
                // 判定「无新增技能」——宁可空缺，不硬凑；记账后，证据未变则下轮不再问
                String why = "LLM 判定此类无完整闭环技能：" + abbrev(drawn.reason());
                p.reasons.put(type.value(), why);
                log.info("[skill] 判定无新增技能 agent={} task_type={} 理由={}",
                        agentId, type.value(), abbrev(drawn.reason()));
                remember(agentId, type, hash, O_QUIET, turns, why);
                return O_QUIET;
            }
            // 日志总结的硬统计（几个会话 / 多少条 / 时间跨度）：纯系统口径，供人核对
            List<String> allSessions = safeClassSessions(agentId, type.value());
            List<String> logFacts = logFacts(allSessions.size(), turns, safeClassSpan(agentId, type.value()));
            // 心路历程 = 日志总结 + 模型自述（四项）+ 确定性证据（依据的会话、会话代表原话），分列供人核对
            SkillProposal.Reasoning reasoning = mergeReasoning(drawn.skill().reasoning(),
                    cappedSessions(allSessions), sampleQuotes(texts), logFacts);
            SkillProposal stored = repo.store(new SkillProposal(
                    proposalId(agentId, type), agentId, type.value(), type.label(), turns,
                    prefixed(drawn.skill().id(), agentId), drawn.skill().name(),
                    drawn.skill().description(), drawn.skill().prompt(), drawn.skill().tools(),
                    SkillProposal.PENDING, hash, null, null, 1, null, reasoning));
            p.reasons.remove(type.value());   // 已出提议：不再需要「为何没提炼」
            remember(agentId, type, hash, O_OFFERED, turns,
                    stored.isPending() ? null : "已出提议（既有提议 " + stored.status() + "，保持不变）。",
                    stored.version());
            return O_OFFERED;
        } catch (Exception e) {
            // 单类失败不影响整批；超时单独点名：这是唯一「摘要太大 / 模型太慢」才会出现的失败
            boolean timeout = e instanceof HttpTimeoutException;
            String why = timeout ? "LLM 超时" : e.getClass().getSimpleName();
            String reason = timeout
                    ? "LLM 超时：该类对话摘要过大或模型过慢，本轮未提炼。"
                    : "提炼失败（" + why + "）：本轮未提炼，下轮重试。";
            p.reasons.put(type.value(), reason);
            log.warn("[skill] 提炼失败 agent={} task_type={} 原因={} turns={} digest={}字 : {}",
                    agentId, type.value(), why, turns, digest.length(), e.toString());
            remember(agentId, type, hash, O_FAILED, turns, reason);
            return O_FAILED;
        }
    }

    /**
     * 记一类的本轮结果（断点续跑缓存 + 事件流水）。记账失败<b>不得影响</b>本轮提炼结果——
     * 单独 try/catch 记日志、照旧返回，把「记账」当成旁路，而不是主流程的一环。
     *
     * <p><b>同时落两处</b>：{@code memory_skill_class_runs}（覆盖式，判「这轮要不要跳过」）
     * 与 {@code memory_skill_events}（只增不改，答「历史上发生过什么」）；分工见 schema.sql。
     */
    private void remember(String agentId, TaskType type, String hash, String outcome, long turns, String reason) {
        remember(agentId, type, hash, outcome, turns, reason, null);
    }

    private void remember(String agentId, TaskType type, String hash, String outcome, long turns,
                          String reason, Integer version) {
        try {
            repo.recordClassRun(agentId, type.value(), hash, outcome, turns, reason);
        } catch (Exception e) {
            log.warn("[skill] 记账失败 agent={} task_type={} outcome={} : {}",
                    agentId, type.value(), outcome, e.toString());
        }
        String kind = switch (outcome) {
            case O_OFFERED -> SkillEvent.OFFER;
            case O_QUIET -> SkillEvent.QUIET;
            default -> SkillEvent.FAILED;
        };
        note(kind, agentId, type.value(), proposalId(agentId, type), version, hash,
                reason != null ? reason : defaultDetail(kind));
    }

    /** 事件 detail 缺失时的兜底说明（人面历史里不至于空着一句）。 */
    private static String defaultDetail(String kind) {
        return SkillEvent.OFFER.equals(kind) ? "本轮提炼出提议。" : kind;
    }

    /** 记一条事件流水（只增不改）。失败只记日志——历史是旁路，不该掀翻提炼本身。 */
    private void note(String kind, String agentId, String taskType, String proposalId,
                      Integer version, String hash, String detail) {
        try {
            repo.recordEvent(kind, SkillEvent.SYSTEM, proposalId, agentId, taskType, version, hash, detail);
        } catch (Exception e) {
            log.warn("[skill] 记事件失败 agent={} task_type={} kind={} : {}",
                    agentId, taskType, kind, e.toString());
        }
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
     * @param limit     每张清单的行数上限（缺省 0 = 不限、返回全量，行为与从前一致）；
     *                  传正数时只取前 N 行，供大库分页，避免一次拉全表拖垮看板。
     */
    public SkillBoard board(String agentId, String sessionId, int limit) {
        String aid = (agentId == null || agentId.isBlank()) ? null : agentId;
        String sid = (aid == null || sessionId == null || sessionId.isBlank()) ? null : sessionId;

        List<SkillBoard.AgentStat> agents = new ArrayList<>();
        for (SkillProposalRepository.AgentStat a : repo.agents(limit)) {
            agents.add(new SkillBoard.AgentStat(a.agentId(), a.sessions(), a.turns(), a.pending()));
        }

        List<SkillBoard.SessionStat> sessions = new ArrayList<>();
        List<SkillBoard.ClassStat> stats = new ArrayList<>();
        List<SkillProposal> proposals = List.of();
        List<SkillBoard.Duplicate> duplicates = List.of();

        if (aid != null) {
            for (SkillProposalRepository.SessionStat s : repo.sessions(aid, limit)) {
                sessions.add(new SkillBoard.SessionStat(
                        s.sessionId(), s.taskType(), labelOf(s.taskType()), s.turns()));
            }
            proposals = repo.listByAgent(aid, limit);
            Map<String, SkillProposal> byType = new HashMap<>();
            for (SkillProposal p : proposals) {
                byType.put(p.taskType(), p);
            }
            // 上一轮提炼时记下的「每类为何没提炼」，供无提议的类展示原因（同步 / 异步两条路径都会写进这份进度）
            Map<String, String> lastReasons = new HashMap<>();
            Progress prog = progress.get(aid);
            if (prog != null) {
                lastReasons.putAll(prog.reasons);
            }
            for (SkillProposalRepository.ClassCount c : repo.classDistribution(aid, sid, limit)) {
                String tt = c.taskType();
                SkillProposal p = byType.get(tt);
                stats.add(new SkillBoard.ClassStat(
                        tt, labelOf(tt), c.sessions(), c.turns(),
                        extractable(tt) && c.turns() >= minTurns,
                        p == null ? null : p.status(),
                        reasonFor(tt, c.turns(), p, lastReasons)));
            }
            duplicates = duplicatesOf(proposals);
        }

        return new SkillBoard(enabled && llm.isEnabled(), minTurns, aid, sid,
                agents, sessions, stats, proposals, duplicates, loads(), taskTypeOptions(),
                "分类清单按「Agent × 任务类型」切开：先选 Agent，再看这个 Agent 分成了哪些类、"
                        + "每类多少会话 / 对话；Session 下拉可再收窄到一个会话。"
                        + "样本门槛 " + minTurns + " 条对话——达标的类才会被提炼技能；"
                        + "「未分类」是还没跑过分类的会话（NULL），与「其他」（判过但归不进）刻意分开。");
    }

    /**
     * 装载回流读数（只读、旁路）：每个技能被 Agent 装配装载过几次。
     *
     * <p>查询失败（如 mo-server 尚未建表）只记日志、返回空清单——回流是旁路，不能因为读不到
     * 就把整张看板带下水。
     */
    private List<SkillBoard.LoadStat> loads() {
        if (loadRepo == null) {
            return List.of();
        }
        try {
            List<SkillBoard.LoadStat> out = new ArrayList<>();
            for (SkillLoadRepository.LoadStat s : loadRepo.bySkill()) {
                out.add(new SkillBoard.LoadStat(s.skillId(), s.loadCount(), s.lastLoadedAt()));
            }
            return out;
        } catch (Exception e) {
            log.warn("[skill] 读取装载回流失败（看板按无回流读数继续）：{}", e.toString());
            return List.of();
        }
    }






    /**
     * 一类「为什么没提炼出技能」的一句话原因（<b>无提议时才有意义</b>；已出提议返回 null）。
     *
     * <p>优先级：不可提炼 &gt; 样本不足 &gt; 上一轮提炼记下的原因（超时 / 解析失败 / 证据未变 / 已取消）
     * &gt; 尚未处理。原因来自最近一次同 Agent 的提炼（{@link Progress#reasons}）——同步与异步两条路径
     * 都会写进这份进度，故看板两条路径都带得上原因。
     */
    private String reasonFor(String taskType, long turns, SkillProposal p, Map<String, String> lastReasons) {
        if (p != null) {
            return null;   // 已提炼出提议：不必再解释「为何没提炼」
        }
        if ("__unclassified__".equals(taskType)) {
            return "还没跑过分类，先完成分类才能提炼。";
        }
        if (!extractable(taskType)) {
            return "「其他」是收容筐，不构成可提炼的一类。";
        }
        if (turns < minTurns) {
            return "样本不足：仅 " + turns + " 条对话，未达门槛 " + minTurns + "。";
        }
        String r = lastReasons.get(taskType);
        if (r != null) {
            return r;
        }
        return "尚未提炼：达标的类会在下一轮提炼时处理。";
    }


    private Drawn draw(String agentId, TaskType type, String digest, Progress p) throws Exception {
        // 记账维度：能力名 = skill，任务类型 = 本类（Agent × 任务类型 一格一账，见 LlmUsageMeter）
        // 同一回调里顺带累加「本轮 token」（P2 预算判据的数据来源）：meter 记的是全局账，
        // p.tokens 记的是本轮消耗——两处都要，前者答「谁烧钱」、后者答「这轮离预算还有多远」。
        String text = llm.chat(SYSTEM, "这类任务（" + type.label() + "）的用户提问（按时间顺序）：\n" + digest,
                (ok, usage) -> {
                    meter.record(agentId, "skill", type.value(), ok, usage);
                    if (usage != null) {
                        p.tokens.addAndGet(usage.totalTokens());
                    }
                });
        return parse(text, type);
    }
















    /**
     * 取「依据的会话」，只读旁路：读不到就返回空（心路仍可用模型自述那一半），绝不掀翻提炼。
     */
    private List<String> safeClassSessions(String agentId, String taskType) {
        try {
            return repo.classSessions(agentId, taskType);
        } catch (Exception e) {
            log.warn("[skill] 读取依据会话失败（心路按无会话继续） agent={} task_type={} : {}",
                    agentId, taskType, e.toString());
            return List.of();
        }
    }

    /** 取「该类对话的时间跨度」，同样只读旁路：读不到就返回 null（硬统计少一项，不掀翻提炼）。 */
    private SkillProposalRepository.Span safeClassSpan(String agentId, String taskType) {
        try {
            return repo.classSpan(agentId, taskType);
        } catch (Exception e) {
            log.warn("[skill] 读取时间跨度失败（日志总结按无跨度继续） agent={} task_type={} : {}",
                    agentId, taskType, e.toString());
            return null;
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
        return new SkillDiscoverReport(false, agentId, 0, 0, 0, 0, 0, null, note);
    }

}